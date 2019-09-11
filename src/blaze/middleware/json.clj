(ns blaze.middleware.json
  (:require
    [blaze.handler.util :as handler-util]
    [jsonista.core :as json]
    [cognitect.anomalies :as anom]
    [manifold.deferred :as md]
    [prometheus.alpha :as prom]
    [ring.util.response :as ring]
    [taoensso.timbre :as log]
    [clojure.string :as str]))


(prom/defhistogram parse-duration-seconds
  "FHIR parsing latencies in seconds."
  {:namespace "fhir"}
  (take 17 (iterate #(* 2 %) 0.00001))
  "format")


(prom/defhistogram generate-duration-seconds
  "FHIR generating latencies in seconds."
  {:namespace "fhir"}
  (take 17 (iterate #(* 2 %) 0.00001))
  "format")


(def ^:private object-mapper
  (json/object-mapper {:bigdecimals true}))


(defn- parse-json
  "Takes a request `body` and returns a deferred with the parsed JSON content
  with string keys and BigDecimal numbers.

  Executes the parsing on `parse-executor`. Returns an error deferred with an
  busy anomaly if parse-executor rejects the task.

  Returns an error deferred with an incorrect anomaly on parse errors."
  [body]
  (with-open [_ (prom/timer parse-duration-seconds "json")]
    (try
      (json/read-value body object-mapper)
      (catch Exception e
        (md/error-deferred
          #::anom{:category ::anom/incorrect
                  :message (ex-message e)})))))


(defn- handle-request
  [{:keys [request-method body] {:strs [content-type]} :headers :as request}]
  (if (and (#{:put :post} request-method)
           content-type
           (or (str/starts-with? content-type "application/fhir+json")
               (str/starts-with? content-type "application/json")))
    (-> (parse-json body)
        (md/chain' #(assoc request :body %)))
    request))


(defn- generate-json [body]
  (try
    (with-open [_ (prom/timer generate-duration-seconds "json")]
      (json/write-value-as-bytes body object-mapper))
    (catch Exception e
      (log/error (log/stacktrace e))
      (json/write-value-as-bytes
        {"resourceType" "OperationOutcome"
         "issue"
         [{"severity" "error"
           "code" "exception"
           "diagnostics" (ex-message e)}]}
        object-mapper))))


(defn handle-response [{:keys [body] :as response}]
  (if (some? body)
    (-> (assoc response :body (generate-json body))
        (ring/content-type "application/fhir+json;charset=utf-8"))
    response))


(defn wrap-json
  "Parses the request body as JSON, calls `handler` and generates JSON from the
  response."
  [handler]
  (fn [request]
    (-> (handle-request request)
        (md/chain' handler)
        (md/chain' handle-response)
        (md/catch' #(handle-response (handler-util/error-response %))))))
