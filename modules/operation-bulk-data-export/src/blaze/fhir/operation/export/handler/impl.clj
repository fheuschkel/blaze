(ns blaze.fhir.operation.export.handler.impl
  (:require
    [blaze.db.api :as d]
    [blaze.fhir.response.create :as response]
    [blaze.handler.util :as handler-util]
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [reitit.core :as reitit]
    [ring.util.response :as ring]))


(defn handler [node]
  (fn [request]
    (ring/response {})))