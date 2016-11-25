(defproject riemann-config "0.1.0-SNAPSHOT"
  :description "Riemann config"
  :url "http://github.com/shmish111/rimann-rkt"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [riemann "0.2.11"]]

  :plugins [[lein-cljfmt "0.3.0"]]

  :profiles {:dev {:source-paths ["src"]
                   :dependencies [[cheshire "5.6.3"]]}}

  :aliases {"format" ["cljfmt" "fix"]})
