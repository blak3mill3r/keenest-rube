(ns playground
  (:require
   [mount.core :as mount :refer [defstate]]
   [rube.core :as k]))

(defstate kube
  :start (k/intern-resources
          (k/cluster
           {:server "http://localhost:8080"
            :namespace "playground"}))
  :stop (k/disconnect! kube))
