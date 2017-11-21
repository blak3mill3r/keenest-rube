(ns rube.state
  "Pure functions for updating the kube state")

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
