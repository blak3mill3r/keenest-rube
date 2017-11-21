(ns rube.core
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [<!! timeout <! go-loop]]
            [taoensso.timbre :as timbre]
            [rube.lens :refer [resource-lens]]
            [rube.api.swagger :as api]
            [com.stuartsierra.component :as component]
            [rube.request :refer [request watch-request]]))

(declare watch-init!)

;;(def whitelist #{"jobs"})

(defrecord KubernetesInformer [server username password namespace whitelist]
  component/Lifecycle
  (start [this]
    (let [resource-map (cond-> @(api/gen-resource-map server)
                         whitelist (select-keys whitelist))
          supported-resources (set (keys resource-map))
          kill-ch (a/chan) ;; Used for canceling in-flight requests
          kube-atom (atom {:context this})]
      (timbre/info "Starting Kubernetes Informer")
      (doseq [resource-name supported-resources]
        (timbre/debug "Initializing watch for: " resource-name)
        (watch-init! kube-atom resource-map resource-name kill-ch))
      (assoc this
             ::api/resource-map resource-map
             ::kube-atom kube-atom
             ::informer (reduce-kv
                         (fn [acc k v]
                           (assoc acc k (resource-lens kube-atom resource-map (keyword k)))) {}
                         @kube-atom)
             ::kill-ch kill-ch)))
  (stop [this]
    (timbre/info "Stopping Kubernetes Informer")
    (when-let [kc (get this ::kill-ch)]
      (timbre/info "Stopping in-flight Kubernetes Informer requests")
      (a/close! kc))
    (dissoc this ::api/resource-map)))

(defmethod print-method KubernetesInformer
  [v ^java.io.Writer w]
  (.write w "<KubernetesCluster>"))

(defn new-kubernetes-informer
  "Create a Kubernetes informer component, allowing for subscription and
  manipulation of Kubernetes resources.

  Returns a KubernetesInformer:

  :rube.core/kube-atom - atom containing a live-updating view of cluster state.
  :rube.core/informer - map of resource types to atoms allowing swap! and reset!
                        operations to modify cluster state."
  [{:keys [server namespace username password whitelist] :as opts}]
  (map->KubernetesInformer opts))

(defn update-from-snapshot
  "Incorporate `items`, a snapshot of the existing set of resources of a given kind."
  [resource-name items]
  #(assoc % (keyword resource-name)
          (into {}
                (for [object items]
                  (let [name (get-in object ["metadata" "name"])]
                    [(str name) object])))))

(defn update-from-event
  "Map from k8s watch-stream messages -> state transition functions for the kube atom."
  [resource-name name type object]
  (timbre/infof "Applying %s %s update event to resource %s" resource-name type name)
  (condp get type
    #{ "DELETED" }          #(update % (keyword resource-name) dissoc name)
    #{ "ADDED" "MODIFIED" } #(update % (keyword resource-name) assoc name object)
    identity))

(defn- reconnect-or-bust
  "Called when the watch channel closes (network problem?)."
  [kube-atom resource-map resource-name kill-ch & {:keys [max-wait] :or {max-wait 8000}}]
  (loop [wait 500]
    (timbre/debug "Attempting to reconnect...")
    (or (get #{:connected} (watch-init! kube-atom resource-map resource-name kill-ch))
        (do (<!! (timeout wait))
            (if (> wait max-wait) :gave-up (recur (* 2 wait)))))))

(defn- state-watch-loop! [kube-atom resource-map resource-name watch-ch kill-ch]
  (go-loop [msg (<! watch-ch)
            type (get msg "type")
            object (get msg "object")]
    (timbre/debug "State update recieved")
    (if msg
      (do
        (swap! kube-atom (update-from-event resource-name (get-in object ["metadata" "name"])
                                            type
                                            object))
        (recur (<! watch-ch)
               (get msg "type")
               (get msg "object")))
      (case (reconnect-or-bust kube-atom resource-map resource-name kill-ch)
        :connected (timbre/info "Kubernetes Informer reconnected.")
        :gave-up   (timbre/error "Kubernetes Informer gave up trying to reconnect.")))))

(defn- state-watch-loop-init!
  "Start updating the state with a k8s watch stream. `body` is the response with the current list of items and the `resourceVersion`."
  [kube-atom resource-map resource-name body kill-ch]
  (let [v (get-in body ["metadata" "resourceVersion"])
        items (get-in body ["items"])]
    (swap! kube-atom (update-from-snapshot resource-name items))
    (let [ctx (:context @kube-atom)]
      (state-watch-loop! kube-atom
                         resource-map
                         resource-name
                         (watch-request
                          ctx v {:method :get :path (api/path-pattern
                                                     resource-map
                                                     resource-name) :kill-ch kill-ch})
                         kill-ch)))
  :connected)

(defn- watch-init!
  "Do an initial GET request to list the existing resources of the given kind, then start tailing the watch stream."
  [kube-atom resource-map resource-name kill-ch]
  (let [ctx (:context @kube-atom)
        {:keys [body status] :as response} (<!! (request
                                                 ctx {:method :get :path
                                                      (api/path-pattern resource-map
                                                                        resource-name)}))]
    (case status 200
          (state-watch-loop-init! kube-atom resource-map resource-name body kill-ch)
          response)))

(comment (let [s (component/start (map->KubernetesInformer {:server "http://127.0.0.1:8001"

                                                            :namespace "default"
                                                            :whitelist #{"namespaces"}

                                                            }))]
           (swap! (:namespaces (::informer s)) assoc "newns" {"metadata" {"name" "newns"}})
           (Thread/sleep 1000)
           (swap! (:namespaces (::informer s)) dissoc "newns")
           (Thread/sleep 1000)
           (component/stop s)))
