(ns blaze.interaction.update
  "FHIR update interaction.

  https://www.hl7.org/fhir/http.html#update"
  (:require
    [blaze.anomaly :refer [ex-anom]]
    [blaze.async-comp :as ac]
    [blaze.db.api :as d]
    [blaze.fhir.spec :as fhir-spec]
    [blaze.handler.fhir.util :as fhir-util]
    [blaze.handler.util :as handler-util]
    [blaze.interaction.update.spec]
    [blaze.middleware.fhir.metrics :refer [wrap-observe-request-duration]]
    [clojure.alpha.spec :as s2]
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [integrant.core :as ig]
    [reitit.core :as reitit]
    [ring.util.response :as ring]
    [taoensso.timbre :as log])
  (:import
    [java.time ZonedDateTime ZoneId]
    [java.time.format DateTimeFormatter]))


(set! *warn-on-reflection* true)


(def ^:private gmt (ZoneId/of "GMT"))


(defn- last-modified [{:blaze.db.tx/keys [instant]}]
  (->> (ZonedDateTime/ofInstant instant gmt)
       (.format DateTimeFormatter/RFC_1123_DATE_TIME)))


(defn- validate-resource [type id body]
  (cond
    (not (map? body))
    (throw
      (ex-anom
        {::anom/category ::anom/incorrect
         :fhir/issue "structure"
         :fhir/operation-outcome "MSG_JSON_OBJECT"}))

    (not= type (:resourceType body))
    (throw
      (ex-anom
        {::anom/category ::anom/incorrect
         :fhir/issue "invariant"
         :fhir/operation-outcome "MSG_RESOURCE_TYPE_MISMATCH"}))

    (not (contains? body :id))
    (throw
      (ex-anom
        {::anom/category ::anom/incorrect
         :fhir/issue "required"
         :fhir/operation-outcome "MSG_RESOURCE_ID_MISSING"}))

    (not (s2/valid? :fhir/id (:id body)))
    (throw
      (ex-anom
        {::anom/category ::anom/incorrect
         :fhir/issue "value"
         :fhir/operation-outcome "MSG_ID_INVALID"}))

    (not= id (:id body))
    (throw
      (ex-anom
        {::anom/category ::anom/incorrect
         :fhir/issue "invariant"
         :fhir/operation-outcome "MSG_RESOURCE_ID_MISMATCH"}))

    (not (fhir-spec/valid? body))
    (throw
      (ex-anom
        {::anom/category ::anom/incorrect
         ::anom/message "Resource invalid."
         :fhir/issue "invariant"}))

    :else
    body))


(defn- build-response
  [router headers type id old-resource db]
  (let [new-resource (d/resource db type id)
        return-preference (handler-util/preference headers "return")
        vid (-> new-resource :meta :versionId)
        {:blaze.db/keys [tx]} (meta new-resource)]
    (log/trace (format "build-response of %s/%s with vid = %s" type id vid))
    (cond->
      (-> (cond
            (= "minimal" return-preference)
            nil
            (= "OperationOutcome" return-preference)
            {:resourceType "OperationOutcome"}
            :else
            new-resource)
          (ring/response)
          (ring/status (if old-resource 200 201))
          (ring/header "Last-Modified" (last-modified tx))
          (ring/header "ETag" (str "W/\"" vid "\"")))
      (nil? old-resource)
      (ring/header
        "Location" (fhir-util/versioned-instance-url router type id vid)))))


(defn- tx-op [resource if-match-t]
  (cond-> [:put resource]
    if-match-t
    (conj if-match-t)))


(defn- handler-intern [node executor]
  (fn [{{{:fhir.resource/keys [type]} :data} ::reitit/match
        {:keys [id]} :path-params
        :keys [body]
        {:strs [if-match] :as headers} :headers
        ::reitit/keys [router]}]
    (let [db (d/db node)]
      (-> (ac/supply (validate-resource type id body))
          (ac/then-compose
            #(d/transact node [(tx-op % (fhir-util/etag->t if-match))]))
          ;; it's important to switch to the transaction executor here, because
          ;; otherwise the central indexing thread would execute response
          ;; building.
          (ac/then-apply-async
            #(build-response
               router headers type id (d/resource db type id) %)
            executor)
          (ac/exceptionally handler-util/error-response)))))


(defn handler [node executor]
  (-> (handler-intern node executor)
      (wrap-observe-request-duration "update")))


(defmethod ig/pre-init-spec :blaze.interaction/update [_]
  (s/keys :req-un [:blaze.db/node ::executor]))


(defmethod ig/init-key :blaze.interaction/update
  [_ {:keys [node executor]}]
  (log/info "Init FHIR update interaction handler")
  (handler node executor))
