(ns rube.api.swagger
  ""
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [com.rpl.specter :refer :all]))

(def v1-resource-names
  #{ "configmaps" "endpoints" "events" "limitranges" "persistentvolumeclaims" "pods" "podtemplates" "replicationcontrollers" "resourcequotas" "secrets" "serviceaccounts" "services" })

(def apps-v1-beta1-resource-names
  #{ "deployments" "statefulsets" })

(def extensions-v1-beta1-resource-names
  #{ "daemonsets" "deployments" "horizontalpodautoscalers" "ingresses" "jobs" "networkpolicies" "replicasets" })

(def supported-resources
  #{"replicationcontrollers"
    "pods"
    "services"
    "deployments"
    "statefulsets"
    "persistentvolumeclaims"
    ;; "jobs" ;; broken
    })

(defn prefix [resource]
  (condp get (name resource)
    v1-resource-names                  "/api/v1"
    apps-v1-beta1-resource-names       "/apis/apps/v1beta1"
    extensions-v1-beta1-resource-names "/apis/extensions/v1beta1"
    (throw (ex-info "Unknown resource" {:resource resource}))))

(defn path-pattern
  [resource-kind]
  (str (prefix resource-kind)"/namespaces/{namespace}/"(name resource-kind)))

(defn path-pattern-one
  [resource-kind]
  (str (prefix resource-kind)"/namespaces/{namespace}/"(name resource-kind)"/{name}"))

(def success? #{200 201 202})

;; copied from clj-kubernetes-api
#_(defn swagger-spec [version]
    (-> (str "swagger/" version ".json")
        io/resource
        slurp
        (json/read-str :key-fn keyword)))

#_(def zapis (:apis (swagger-spec "v1")))

#_(doseq [{:keys [path description operations]} zapis
          :when (and (re-matches #".*watch.*" path)
                     (re-matches #".*namespace.*" path)
                     (not (re-matches #".*\{name\}.*" path)))]
    (println path))
