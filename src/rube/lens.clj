(ns rube.lens
  "Make stateful k8s API calls based on changes to local state."
  (:require [lentes.core :as l]
            [clojure.data :as data]
            [clojure.core.match :refer [match]]
            [clojure.core.async :refer [<!!]]
            [rube.request :refer [request]]
            [rube.api.swagger :as api]))

(declare resource-setter)

(defn resource-lens
  "Returns a lens representing a k8s resource.
  The read function is just a keyword.
  The write function does stateful API calls and either throws or returns a next-state."
  [resource-name kube-state]
  (-> (l/lens (keyword resource-name) #(apply resource-setter (keyword resource-name) %&))
      (l/derive kube-state)))

(defn- resource-update!
  "Try to manipulate k8s state via an API call, and either throw or return (f resource-state body)."
  [resource-state context request-options f]
  (let [{:keys [status body] :as response}
        (<!! (request context request-options))]
    (if (api/success? status) (f resource-state body)
        (throw (ex-info (:message body) response)))))

(defn- replace-one!
  "Do a PUT on an individual resource of kind `k` with name `n`, using its value from next-resource-state."
  [next-resource-state context k n]
  (-> next-resource-state
      (resource-update! context
                        {:method :put :path (api/path-pattern-one k)
                         :params {:namespace (:namespace context) :name (name n)}
                         :body (get next-resource-state n)}
                        #(assoc-in % [k n] %2))))

(defn- create-one!
  "Do a POST to create a resource of kind `k` with name `n`, using its value from next-resource-state."
  [next-resource-state context k n]
  (-> next-resource-state
      (resource-update! context
                        {:method :post :path (api/path-pattern k)
                         :params {:namespace (:namespace context)}
                         :body (get next-resource-state n)}
                        #(assoc-in % [k n] %2))))

(defn- delete-one!
  "Do a DELETE request on a resource of kind `k` with name `n`."
  [resource-state context k n]
  (-> resource-state
      (resource-update! context
                        {:method :delete
                         :path (api/path-pattern-one k)
                         :params {:namespace (:namespace context)
                                  :name (name n)}}
                        (fn [state _] (dissoc state n)))))

(defn- resource-setter
  "Takes a resource, a kube-state, and a resource-state transition function `f`,
  diffs the resource-state before & after the application of `f`, and conditionally makes stateful API calls.
  Returns a next-kube-state (or throws on API errors).
  By design, it only allows updating, creating, or deleting one resource at a time."
  [k {:as kube-state :keys [context]} f]
  (let [resource-state      (k kube-state)
        next-resource-state (k (update kube-state k f))

        [was shall-be still-is] (data/diff resource-state next-resource-state)

        [[resource-name _]] (seq shall-be)
        [[delete-name   _]] (seq was)
        pre-existing?       (if (get still-is resource-name) 1 0)]

    (->>
     (match [(count was) (count shall-be) pre-existing?]

            [ 0 0 _ ]   resource-state
            [ 0 1 1 ]   (-> next-resource-state (replace-one! context k resource-name))
            [ 0 1 0 ]   (-> next-resource-state (create-one!  context k resource-name))
            [ 1 0 _ ]   (-> resource-state      (delete-one!  context k delete-name))

            :else
            (if (= 1 (count (merge was shall-be)))
              (-> next-resource-state (replace-one! context k resource-name))
              (throw (ex-info "Modifying multiple resources in one mutation is not supported." {:names (keys (merge was shall-be))}))))

     (assoc kube-state k))))
