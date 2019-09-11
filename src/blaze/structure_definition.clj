(ns blaze.structure-definition
  (:require
    [jsonista.core :as json]
    [clojure.java.io :as io]))


(def ^:private object-mapper
  (json/object-mapper {:decode-key-fn keyword :bigdecimals true}))


(defn- read-bundle
  "Reads a bundle from classpath named `resource-name`."
  [resource-name]
  (with-open [rdr (io/reader (io/resource resource-name))]
    (json/read-value rdr object-mapper)))


(defn- extract [kind bundle]
  (into
    []
    (comp
      (map :resource)
      (filter #(= kind (:kind %))))
    (:entry bundle)))


(defn read-structure-definitions []
  (let [package "blaze/fhir/r4/structure-definitions"]
    (into
      (extract "complex-type" (read-bundle (str package "/profiles-types.json")))
      (into
        []
        (remove #(= "Parameters" (:name %)))
        (extract "resource" (read-bundle (str package "/profiles-resources.json")))))))
