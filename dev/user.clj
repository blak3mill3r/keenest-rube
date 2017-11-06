(ns user
  (:require [mount.core :refer [start stop]]
            [clojure.tools.namespace.repl :refer [refresh]]))

(defn go    []  (start)   :ready                      )
(defn reset []  (stop)    (refresh :after 'user/go)   )

#_(go)
#_(reset)

;; for this to work, you have to already have a Kubernetes namespace called "playground"
;; and you're running kubectl proxy --port=8080
;; (or you have modified playground.clj)

#_(-> @pods count) ;; => 0, presumably

#_(swap! pods assoc :my-awesome-podz
         {:metadata
          {:labels {:app "bunk" :special "false"} :namespace "playground" :name "my-awesome-podz"}
          :spec
          {:containers
           [{:name
             "two-peas"
             :env
             [{:name "REDIS_URL" :value "prolly.xwykgm.ng.0001.use1.cache.amazonaws.com:6379"}]
             :ports
             [{:containerPort 420, :protocol "TCP"}]
             :image
             "1337.dkr.ecr.us-east-1.amazonaws.com/two-peas:v1.0"}]
           :restartPolicy "Always",
           :terminationGracePeriodSeconds 30}})

#_(-> @pods
      :my-awesome-podz
      :status
      :containerStatuses
      first
      :state
      :waiting
      :message)
;; => "rpc error: code = 2 desc = Error response from daemon: {\"message\":\"denied: Could not resolve registry id from host 1337.dkr.ecr.us-east-1.amazonaws.com\"}"

;; because there is no such registry
;; and no such container
