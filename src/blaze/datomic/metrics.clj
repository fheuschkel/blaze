(ns blaze.datomic.metrics
  (:require
    [prometheus.alpha :as prom]
    [taoensso.timbre :as log]))


(prom/defgauge object-cache-hits-ratio
  "Datomic object cache hit ratio."
  {:namespace "datomic"})


(prom/defgauge object-cache-size
  "Number of segments in the Datomic object cache."
  {:namespace "datomic"})


(prom/defcounter storage-get-bytes-total
  "Number of segments in the Datomic object cache."
  {:namespace "datomic"})


(prom/defcounter storage-gets-total
  "Number of segments in the Datomic object cache."
  {:namespace "datomic"})


(defn handler
  [{object-cache :ObjectCache
    object-cache-count :ObjectCacheCount
    storage-get-bytes :StorageGetBytes
    :as metrics}]
  (log/info "metrics" metrics)

  (when-let [{:keys [sum count]} object-cache]
    (prom/set! object-cache-hits-ratio (/ (double sum) count)))

  (when-let [{:keys [sum count]} storage-get-bytes]
    (prom/inc! storage-get-bytes-total sum)
    (prom/inc! storage-gets-total count))

  (when object-cache-count
    (prom/set! object-cache-size object-cache-count)))
