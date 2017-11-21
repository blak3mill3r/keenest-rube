(ns rube.api.swagger
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [aleph.http :as http]
            [byte-streams :as bs]
            [manifold.deferred :as md]
            [com.stuartsierra.component :as component]
            [clojure.data.json :as json]))

(def gen-resource-map
  "Queries Kubernetes for watchable resources, returning a map of resource name
  to URI path suitable for get operations."
  (memoize
   (fn [server]
     (-> (md/chain (http/get (str server "/swagger.json")
                             {:accept "application/json"})
                   (fn [resp]
                     (let [body (bs/to-string (:body resp))
                           paths (get (json/read-str body) "paths")]
                       (into {}
                             (comp
                              (filter (fn [[k v]]
                                        (and (re-matches #".*watch.*" k)
                                             (re-matches #".*namespace.*" k)
                                             (not (re-matches #".*\{name\}.*" k)))))
                              (map (fn [[k v]]
                                     [(last (str/split k #"/"))
                                      (str/replace k #"/watch" "")])))
                             paths))))
         (md/catch Exception
             (fn [e]
               (throw (ex-info "Could not connect to Kubernetes API server swagger server" {:status 404}))))))))

(defn path-pattern
  "Return a resource path URI for a resource, pre-templated with `{namespace}`"
  [resource-map resource]
  (if-let [resource-path (get-in resource-map [resource])]
    resource-path
    (throw (ex-info (format "Resource path %s not found." resource) {}))))

(defn path-pattern-one
  "Return a resource path URI for a specific resource, pre-templated with `{namespace}` and `{name}`"
  [resource-map resource]
  (str (path-pattern resource-map resource) "/{name}"))
