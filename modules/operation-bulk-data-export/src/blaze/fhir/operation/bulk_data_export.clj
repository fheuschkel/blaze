(ns blaze.fhir.operation.bulk-data-export
  "Main entry point into the $export operation."
  (:require
    [blaze.executors :as ex :refer [executor?]]
    [blaze.fhir.operation.export.handler.impl :as export]
    [blaze.fhir.operation.export-status.handler.impl :as exp-status]
    [blaze.module :refer [reg-collector]]
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [taoensso.timbre :as log]))


(defn export-handler [node]
  (export/handler node))


(defn export-status-handler [node]
  (exp-status/handler node))


(defmethod ig/init-key ::export-handler
  [_ {:keys [node]}]
  (log/info "Init FHIR $export operation handler")
  (export-handler node))


(defmethod ig/init-key ::export-status-handler
  [_ {:keys [node]}]
  (log/info "Init FHIR $__export-status operation handler")
  (export-status-handler node))