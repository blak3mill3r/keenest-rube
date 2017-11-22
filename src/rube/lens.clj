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
  [kube-state resource-map resource-name]
  (-> (l/lens resource-name #(apply resource-setter resource-map resource-name %&))
      (l/derive kube-state)))

(def success? #{200 201 202})

(defn- resource-update!
  "Try to manipulate k8s state via an API call, and either throw or return (f resource-state body)."
  [resource-state context request-options f]
  (let [{:keys [status body] :as response}
        (<!! (request context request-options))]
    (if (success? status) (f resource-state body)
        (throw (ex-info (:message body) response)))))

(defn- replace-one!
  "Do a PUT on an individual resource of kind `k` with name `n`, using its value from next-resource-state."
  [next-resource-state context resource-map k n]
  (-> next-resource-state
      (resource-update! context
                        {:method :put :path (api/path-pattern-one resource-map (name k))
                         :params {"namespace" (:namespace context) "name" (str n)}
                         :body (get next-resource-state n)}
                        #(assoc-in % [n] %2))))

(defn deep-merge* [& maps]
  (let [f (fn [old new]
            (if (and (map? old) (map? new))
              (merge-with deep-merge* old new)
              new))]
    (if (every? map? maps)
      (apply merge-with f maps)
      (last maps))))

;; https://github.com/circleci/frontend/blob/cba5cb228c4739d08baa070eecc01ec0b1ed2ab2/src-cljs/frontend/utils.cljs
(defn deep-merge
  [& maps]
  (let [maps (filter identity maps)]
    (assert (every? map? maps))
    (apply merge-with deep-merge* maps)))

(defn- patch-one!
  "Do a PUT on an individual resource of kind `k` with name `n`, using its value from next-resource-state."
  [next-resource-state context resource-map k n]
  (-> next-resource-state
      (resource-update! context
                        {:method :patch :path (api/path-pattern-one resource-map (name k))
                         :params {"namespace" (:namespace context) "name" (str n)}
                         :body (get next-resource-state n)}
                        #(update-in % [n] deep-merge %2))))

(defn- create-one!
  "Do a POST to create a resource of kind `k` with name `n`, using its value from next-resource-state."
  [next-resource-state context resource-map k n]
  (-> next-resource-state
      (resource-update! context
                        {:method :post :path (api/path-pattern resource-map (name k))
                         :params {"namespace" (:namespace context)}
                         :body (get next-resource-state n)}
                        #(assoc-in % [n] %2))))

(defn- delete-one!
  "Do a DELETE request on a resource of kind `k` with name `n`."
  [resource-state context resource-map k n]
  (-> resource-state
      (resource-update! context
                        {:method :delete
                         :path (api/path-pattern-one resource-map (name k))
                         :params {"namespace" (:namespace context)
                                  "name" (str n)}}
                        (fn [state _] (dissoc state n)))))

(defn- resource-setter
  "Takes a resource, a kube-state, and a resource-state transition function `f`,
  diffs the resource-state before & after the application of `f`, and conditionally makes stateful API calls.
  Returns a next-kube-state (or throws on API errors).
  By design, it only allows updating, creating, or deleting one resource at a time."
  [resource-map k {:as kube-state :keys [context]} f]
  (let [resource-state      (get kube-state k)

        next-resource-state (get (update kube-state k f) k)
        [was shall-be still-is] (data/diff resource-state next-resource-state)
        [[resource-name _]] (seq shall-be)
        [[delete-name   _]] (seq was)
        pre-existing?       (if (get still-is resource-name) 1 0)]
    (->>
     (match [(count was) (count shall-be) pre-existing?]

            [ 0 0 _ ]   resource-state
            [ 0 1 1 ]   (-> next-resource-state (replace-one! context resource-map k resource-name))
            [ 1 1 1 ]   (-> next-resource-state (patch-one! context resource-map k resource-name))
            [ 0 1 0 ]   (-> next-resource-state (create-one!  context resource-map k resource-name))
            [ 1 0 _ ]   (-> resource-state      (delete-one!  context resource-map k delete-name))

            :else
            (if (= 1 (count (merge was shall-be)))
              (-> next-resource-state (replace-one! context resource-map k resource-name))
              (throw (ex-info "Modifying multiple resources in one mutation is not supported." {:names (keys (merge was shall-be))}))))

     (assoc kube-state k))))
