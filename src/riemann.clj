(require '[clojure.edn :as edn])
(require '[clojure.tools.logging :as log])
(require '[clojure.string :as str])
(require '[org.httpkit.client :as http])
(require '[cheshire.core :as json])
(require '[riemann.email :refer [mailer]])
;;
(require '[riemann.config :refer :all])
(require '[riemann.core :as core])
(require '[riemann.streams :refer :all])
(require '[riemann.logging :as logging])
(require '[riemann.folds :as folds])
(require '[riemann.influxdb :refer [influxdb] :as i])
(require '[riemann.slack :refer [slack slack-escape]])
(require '[riemann.time :as time])
(require '[riemann.transport.websockets :as ws])
(require '[riemann.transport.sse :as sse])

(logging/init)

;; Add a health check HTTP endpoint to the websocket server
(alter-var-root #'ws/ws-handler
  (fn [ws-handler]
    (fn [core stats]
      (let [ws-f (ws-handler core stats)]
        (fn [req]
          (if (re-matches #"/health" (:uri req))
            {:status 200}
            (ws-f req)))))))

;; Add a health check HTTP endpoint to the sse server
(alter-var-root #'sse/sse-handler
  (fn [sse-handler]
    (fn [core stats headers]
      (let [sse-f (sse-handler core stats headers)]
        (fn [req]
          (if (re-matches #"/health" (:uri req))
            {:status 200}
            (sse-f req)))))))

; Listen on the local interface over TCP (5555), UDP (5555), and websockets
; (5556), and sse (5558)
(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server {:host host})
  (sse-server {:host host}))

(def email (mailer {:from (System/getenv "EMAIL_FROM")
                    :host "postfix.default.svc.cluster.local"}))

(defn add-default-metric
  [{:keys [metric] :as event}]
  (if metric
    event
    (assoc event :metric 1)))

(defn stringfy-vals
  [m]
  (into {} (for [[k v] m] [k (cond
                               (number? v) v
                               (and (string? v) (= \: (first v))) (str/replace-first v \: nil)
                               :else (str v))])))

(defn handle-ex-info
  [f]
  (fn [events]
    (try (f events)
         (catch clojure.lang.ExceptionInfo e
           (log/error e "error:" (ex-data e) ", events:" events)))))

(def mappings
  {:event {:properties {:id          {:type "string" :fields {:raw {:type "string" :index "not_analyzed"}}}
                        :description {:type "string"}
                        :tags        {:type "string"}
                        :service     {:type "string" :fields {:raw {:type "string" :index "not_analyzed"}}}
                        :time        {:type "date"}
                        :state       {:type "string"}
                        :host        {:type "string" :fields {:raw {:type "string" :index "not_analyzed"}}}
                        :ttl         {:type "long"}
                        :environment {:type "string"}
                        :buffer-size {:type "long"}
                        :metric      {:type "double"}}}})

(defn elasticsearch
  "Send a load of events to elasticsearch"
  [{:keys [uri index type] :or {uri "http://localhost:9200" index "riemann" type "event"}}]
  (fn stream [events]
    (let [callback (fn [response]
                     (let [errors? (or (not= 200 (:status response))
                                     (->> (-> response :body (json/decode true) :items)
                                       (map :index)
                                       (map :status)
                                       (remove #(= % 201))
                                       count
                                       pos?))]
                       (when errors?
                         (core/stream! @core {:service "elasticsearch" :state "failure" :time (time/unix-time) :description (pr-str response)})
                         (log/error "Elasticsearch error:" (pr-str response))
                         (prn events))))
          events (if (sequential? events) events (list events))
          action (json/encode {"index" {"_index" index "_type" type}})
          json-events (->> events
                        (map (fn [e] (-> e
                                       (update-in [:time] long)
                                       (update-in [:time] (partial * 1000)))))
                        (map json/encode))
          requests (interleave (repeat action) (repeat "\n") json-events (repeat "\n"))]
      (http/request {:url              (format "%s/_bulk" uri)
                     :throw-exceptions false
                     :method           :post
                     :body             (str/join requests)
                     :as               :text}
        callback))))

(def elasticsearch-uri "http://localhost:9200")

(def elasticsearch-index "riemann")

(def elasticsearch-batch-sender
  (where (not (state "expired"))
    (async-queue! :es-agg {:queue-size      10000
                           :core-pool-size  1
                           :max-pool-size   4
                           :keep-alive-time 60000}
      (batch 100 1/10 (elasticsearch {:uri elasticsearch-uri :index elasticsearch-index})))))

(defn kibana-link-builder
  [kibana-root host service state]
  (let [service (str/replace service #"/" "%2F")
        query (str "host:%22" host "%22%20AND%20state:" state "%20AND%20service:%22" service "%22")]
    (str kibana-root
      "/discover/xxx?_g=(refreshInterval:(display:Off,pause:!f,value:0),time:(from:now-1h,mode:quick,to:now))&_a=(columns:!(_source),filters:!(),index:riemann,interval:auto,query:(query_string:(analyze_wildcard:!t,query:'"
      query
      "')),sort:!(time,desc))")))

(def slack-credentials {:account "", :token ""})

(defn slack-failure-formatter
  "Format an exception event as a Slack attachment with a series of fields."
  [{:keys [host service state description exception]}]
  (let [kibana-root ""
        host-field {:title "Host",
                    :value (slack-escape (or host "-")),
                    :short true}
        service-field {:title "Service",
                        :value (slack-escape (or service "-")),
                        :short true}
        link-field {:title "View on Kibana"
                    :value (format "<%s|here>" (kibana-link-builder kibana-root host service state))}
        fields (conj [host-field service-field link-field]
                 {:title "Description"
                  :value (if exception
                           (->> exception :message str slack-escape)
                           (->> description str slack-escape))})]
    {:text (format "%s transition to %s" host state),
     :attachments
           [{:color  (get {"ok"      "good"
                           "warn"    "warning"
                           "failure" "danger"} state "#439FE0")
             :fields fields}]}))

(defn alerts-slacker [channel]
  (slack slack-credentials {:username  "Riemann bot"
                            :channel   channel
                            :icon      ":boom:"
                            :formatter slack-failure-formatter}))

(defn- idempotent-update-in
  "Like update-in, but does nothing if the key is not there"
  [m ks f & more]
  (if (get-in m ks)
    (apply update-in m ks f more)
    m))

(def example-alerts
  (where (and
           (or (state "warn") (state "failure"))
           (service #".*import.*")
           (host #"some-ns-.*"))
    (rollup 5 3600 (email "x@y.com"))))

;; alerts for jmx stuff
(def cpu-alerts
  (let [threshold 80]
    (where
      (service "java.lang:type=OperatingSystem.ProcessCpuLoad")
      (by [:host]
        (changed (fn [{:keys [metric]}]
                   (and (number? metric) (> metric threshold)))
          {:init false}
          (fn [{:keys [metric] :as e}]
            (when (> metric threshold)
              (throttle 1 300 (alerts-slacker e)))))))))

(defn percent
  "x : y = ? : 100"
  [x y]
  (when (and x y)
    (double (/ (* x 100) y))))

(def memory-alerts
  (let [threshold 95]
    (by [:host]
      (project [(service "java.lang:type=Memory.HeapMemoryUsage.used")
                (service "java.lang:type=Memory.HeapMemoryUsage.max")]
        (smap (partial folds/fold* percent)
          (with :service "java.lang:type=Memory.HeapMemoryUsage.percent"
            (changed (fn [{:keys [metric]}]
                       (and (number? metric) (> metric threshold)))
              {:init false}
              (fn [{:keys [metric] :as e}]
                (when (> metric threshold)
                  (throttle 1 300 (alerts-slacker e)))))))))))

(def elasticsearch-alerts
  (where (and
           (state "failure")
           (service "elasticsearch"))
    (throttle 1 3600 (email "some email addresses"))))

; Expire old events from the index every 5 seconds.
(periodically-expire 5)

(let [index (index)]
  ; Inbound events will be passed to these streams:
  (streams
    (smap #(-> %
             (idempotent-update-in [:exception] edn/read-string)
             (idempotent-update-in [:health-data] edn/read-string)
             (assoc :uuid (str (java.util.UUID/randomUUID))))
      (default :ttl 45
        index
        (where (service #".*")
          elasticsearch-batch-sender)
        #_prn
        #_elasticsearch-alerts
        #_cpu-alerts
        #_memory-alerts))))
