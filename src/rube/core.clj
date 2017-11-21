(ns rube.core
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [<!! timeout]]
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
      (timbre/info "Starting Kubernetes")
      (doseq [resource-name supported-resources]
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
    (when-let [kc (get this ::kill-ch)]
      (a/close! kc))
    (dissoc this ::api/resource-map)))

(defmethod print-method KubernetesInformer
  [v ^java.io.Writer w]
  (.write w "<KubernetesCluster>"))

(defn new-kubernetes-informer [{:keys [server username password whitelist namespace] :as opts}]
  (map->KubernetesInformer opts))

(defn disconnect!
  "Tear down a kube atom"
  [kube-atom]
  (if-let [ch (-> @kube-atom :kill-ch)]
    (do (println "Closing kill chan") (a/close! ch))
    (println "Hmm, there's no kill chan?"))
  (reset! kube-atom {:state :disconnected}))

(defn context
  "Helper function to create a kube context"
  [server namespace & {:keys [username password]}]
  {:server server
   :namespace namespace
   :username username
   :password password})

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
  (println "Updating " resource-name name type)
  (condp get type
    #{ "DELETED" }          #(update % (keyword resource-name) dissoc name)
    #{ "ADDED" "MODIFIED" } #(update % (keyword resource-name) assoc name object)
    identity))

(defn- reconnect-or-bust
  "Called when the watch channel closes (network problem?)."
  [kube-atom resource-map resource-name kill-ch & {:keys [max-wait] :or {max-wait 8000}}]
  (loop [wait 500]
    (or (get #{:connected} (watch-init! kube-atom resource-map resource-name kill-ch))
        (do (<!! (timeout wait))
            (if (> wait max-wait) :gave-up (recur (* 2 wait)))))))

(defn- state-watch-loop! [kube-atom resource-map resource-name watch-ch kill-ch]
  (a/thread
    (loop []
      (let [msg (<!! watch-ch)
            type (get msg "type")
            object (get msg "object")]
        (case msg nil (case (reconnect-or-bust kube-atom resource-map resource-name kill-ch)
                        :connected (println "Reconnected!")
                        :gave-up   (println "Gave up!"))
              (do
                (swap! kube-atom (update-from-event resource-name (get-in object ["metadata" "name"])
                                                    type
                                                    object))
                (recur)))))))

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


;; (def rl (resource-lens (::kube-atom s) (::api/resource-map s) :jobs))

;; (= (let [v (str (rand-int 1000))]
;;      (swap! rl assoc-in ["pyroclast-admin" "metadata" "labels" "foo"] v)
;;      v)
;;    (get-in @rl ["pyroclast-admin" "metadata" "labels" "foo"]))
;; ;;(component/stop s)
