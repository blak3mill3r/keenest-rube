(defproject keenest-rube "0.1.0-alpha0"
  :description "The state of a Kubernetes cluster, abstracted as a value in a Clojure atom."
  :url "https://github.com/blak3mill3r/keenest-rube"
  :license {:name "MIT"
            :url "https://github.com/blak3mill3r/keenest-rube/blob/master/LICENSE"}

  :dependencies [[org.clojure/clojure "1.9.0-alpha17" :scope "provided"]
                 [org.clojure/core.async "0.3.442"]
                 [org.clojure/data.json "0.2.6"]
                 [aleph "0.4.4-alpha4"]
                 [com.taoensso/timbre "4.10.0"]
                 [funcool/lentes "1.2.0"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.clojure/core.match "0.3.0-alpha5"]]

  :profiles {:dev
             {:dependencies [[org.clojure/tools.namespace "0.2.11"]]
              :plugins []
              :source-paths ["dev" "src"]}})
