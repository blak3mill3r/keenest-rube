(ns rube.state
  "Pure functions for updating the kube state")

(defn update-from-snapshot
  "Incorporate `items`, a snapshot of the existing set of resources of a given kind."
  [resource-name items]
  #(assoc % (keyword resource-name)
          (into {} (for [{{name :name} :metadata :as object} items]
                     [(keyword name) object]))))

(defn update-from-event
  "Map from k8s watch-stream messages -> state transition functions for the kube atom."
  [resource-name name type object]
  (condp get type
    #{ "DELETED" }          #(update % resource-name dissoc name)
    #{ "ADDED" "MODIFIED" } #(update % resource-name assoc name object)
    identity))
