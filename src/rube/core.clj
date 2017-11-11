(ns rube.core
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [<!! timeout]]
            [rube.lens :refer [resource-lens]]
            [rube.api.swagger :as api]
            [rube.request :refer [request watch-request]]
            [rube.state :refer [update-from-snapshot update-from-event]]))

(declare state-watch-loop! watch-init!)

(defn cluster
  "Make a kube atom"
  [ctx]
  (let [kill-ch (a/chan)
        kube-atom (atom {:kill-ch kill-ch :context ctx})]
    (doseq [resource-name api/supported-resources]
      (let [o (watch-init! kube-atom resource-name kill-ch)]
        (or (get #{:connected} o) (throw (ex-info "Could not initialize resource watchers" {:e o})))))
    kube-atom))

(defn disconnect!
  "Tear down a kube atom"
  [kube-atom]
  (if-let [ch (-> @kube-atom :kill-ch)]
    (do (println "Closing kill chan") (a/close! ch))
    (println "Hmm, there's no kill chan?"))
  (reset! kube-atom {:state :disconnected}))

(defn intern-resources
  "Intern in the current namespace a symbol named after each kind of k8s resource.
  These are lenses with side-effects including making requests to the k8s API."
  ([kube-atom]
   (intern-resources kube-atom *ns*))
  ([kube-atom ns]
   (doseq [resource-name api/supported-resources :let [lens (resource-lens resource-name kube-atom)]]
     (intern *ns* (symbol resource-name) lens))
   kube-atom))

(defn context
  "Helper function to create a kube context"
  [server namespace & {:keys [username password]}]
  {:server server
   :namespace namespace
   :username username
   :password password})

(defn- state-watch-loop-init!
  "Start updating the state with a k8s watch stream. `body` is the response with the current list of items and the `resourceVersion`."
  [kube-atom resource-name {{v :resourceVersion} :metadata items :items :as body} kill-ch]
  (swap! kube-atom (update-from-snapshot resource-name items))
  (let [ctx (:context @kube-atom)]
    (state-watch-loop! kube-atom
                       resource-name
                       (watch-request ctx v {:method :get :path (api/path-pattern resource-name) :kill-ch kill-ch})
                       kill-ch))
  :connected)

(defn- watch-init!
  "Do an initial GET request to list the existing resources of the given kind, then start tailing the watch stream."
  [kube-atom resource-name kill-ch]
  (let [resource-name                       (keyword resource-name)
        ctx                                 (:context @kube-atom)
        {:keys [body status] :as response}  (<!! (request
                                                  ctx {:method :get :path (api/path-pattern resource-name)}))]
    (case status 200
          (state-watch-loop-init! kube-atom resource-name body kill-ch)
          response)))

(defn- reconnect-or-bust
  "Called when the watch channel closes (network problem?)."
  [kube-atom resource-name kill-ch & {:keys [max-wait] :or {max-wait 8000}}]
  (loop [wait 500]
    (or (get #{:connected} (watch-init! kube-atom resource-name kill-ch))
        (do (<!! (timeout wait))
            (if (> wait max-wait) :gave-up (recur (* 2 wait)))))))

(defn- state-watch-loop! [kube-atom resource-name watch-ch kill-ch]
  (a/thread
    (loop []
      (let [{:keys [type object] :as msg} (<!! watch-ch)]
        (case msg nil (case (reconnect-or-bust kube-atom resource-name kill-ch)
                        :connected (println "Reconnected!")
                        :gave-up   (println "Gave up!"))
              (do
                (swap! kube-atom (update-from-event resource-name (-> object :metadata :name keyword) type object))
                (recur)))))))
