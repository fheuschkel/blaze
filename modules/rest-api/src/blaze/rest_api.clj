(ns blaze.rest-api
  (:require
    [blaze.async-comp :as ac]
    [blaze.db.search-param-registry :as sr]
    [blaze.db.search-param-registry.spec]
    [blaze.executors :as ex]
    [blaze.middleware.fhir.metrics :as metrics]
    [blaze.module :refer [reg-collector]]
    [blaze.rest-api.middleware.auth-guard :refer [wrap-auth-guard]]
    [blaze.rest-api.middleware.cors :refer [wrap-cors]]
    [blaze.rest-api.middleware.json :as json :refer [wrap-json]]
    [blaze.rest-api.middleware.log :refer [wrap-log]]
    [blaze.rest-api.spec]
    [blaze.spec]
    [buddy.auth.middleware :refer [wrap-authentication]]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [integrant.core :as ig]
    [reitit.core :as reitit]
    [reitit.impl :as impl]
    [reitit.ring]
    [reitit.ring.spec]
    [ring.util.response :as ring]
    [taoensso.timbre :as log]))


(defn resolve-pattern
  "Tries to find a resource pattern in `resource-patterns` according to the
  name of the `structure-definition`.

  Falls back to the :default resource pattern if there is any."
  {:arglists '([resource-patterns structure-definition])}
  [resource-patterns {:keys [name]}]
  (or
    (some
      #(when (= name (:blaze.rest-api.resource-pattern/type %)) %)
      resource-patterns)
    (some
      #(when (= :default (:blaze.rest-api.resource-pattern/type %)) %)
      resource-patterns)))


(defn resource-route
  "Builds routes for one resource according to `structure-definition`.

  Returns nil if the resource has no match in `resource-patterns`.

  Route data contains resource type."
  {:arglists '([resource-patterns structure-definition])}
  [auth-backends resource-patterns {:keys [name] :as structure-definition}]
  (when-let
    [{:blaze.rest-api.resource-pattern/keys [interactions]}
     (resolve-pattern resource-patterns structure-definition)]
    [(str "/" name)
     {:middleware
      (cond-> []
        (seq auth-backends)
        (conj wrap-auth-guard))
      :fhir.resource/type name}
     [""
      (cond-> {:name (keyword name "type")}
        (contains? interactions :search-type)
        (assoc :get (-> interactions :search-type
                        :blaze.rest-api.interaction/handler))
        (contains? interactions :create)
        (assoc :post (-> interactions :create
                         :blaze.rest-api.interaction/handler)))]
     ["/_history"
      (cond-> {}
        (contains? interactions :history-type)
        (assoc :get (-> interactions :history-type
                        :blaze.rest-api.interaction/handler)))]
     ["/_search"
      (cond-> {}
        (contains? interactions :search-type)
        (assoc :post (-> interactions :search-type
                         :blaze.rest-api.interaction/handler)))]
     ["/{id}"
      [""
       (cond-> {:name (keyword name "instance")}
         (contains? interactions :read)
         (assoc :get (-> interactions :read
                         :blaze.rest-api.interaction/handler))
         (contains? interactions :update)
         (assoc :put (-> interactions :update
                         :blaze.rest-api.interaction/handler))
         (contains? interactions :delete)
         (assoc :delete (-> interactions :delete
                            :blaze.rest-api.interaction/handler)))]
      ["/_history"
       [""
        (cond-> {:name (keyword name "history-instance")}
          (contains? interactions :history-instance)
          (assoc :get (-> interactions :history-instance
                          :blaze.rest-api.interaction/handler)))]
       ["/{vid}"
        (cond-> {:name (keyword name "versioned-instance")}
          (contains? interactions :vread)
          (assoc :get (-> interactions :vread
                          :blaze.rest-api.interaction/handler)))]]]]))


(defn- compartment-route
  {:arglists '([auth-backends compartment])}
  [auth-backends {:blaze.rest-api.compartment/keys [code search-handler]}]
  [(format "/%s/{id}/{type}" code)
   {:name (keyword code "compartment")
    :fhir.compartment/code code
    :middleware
    (cond-> []
      (seq auth-backends)
      (conj wrap-auth-guard))
    :get search-handler}])


(def structure-definition-filter
  (comp
    (filter (comp #{"resource"} :kind))
    (remove :experimental)
    (remove :abstract)))


(s/def ::structure-definitions
  (s/coll-of :fhir.un/StructureDefinition))


(defn- non-conflict-detecting-router
  "Like reitit.core/router but without conflict detection."
  ([raw-routes opts]
   (let [{:keys [router] :as opts} (merge (reitit/default-router-options) opts)
         routes (impl/resolve-routes raw-routes opts)
         compiled-routes (impl/compile-routes routes opts)]

     (when-let [validate (:validate opts)]
       (validate compiled-routes opts))

     (router compiled-routes opts))))

(defn router
  {:arglists '([config capabilities-handler])}
  [{:keys
    [base-url
     context-path
     structure-definitions
     auth-backends
     transaction-handler
     history-system-handler
     resource-patterns
     compartments
     operations]
    :or {context-path ""}}
   capabilities-handler]
  (non-conflict-detecting-router
    (-> [""
         {:blaze/base-url base-url
          :blaze/context-path context-path
          :middleware
          (cond-> [wrap-cors wrap-log]
            (seq auth-backends)
            (conj #(apply wrap-authentication % auth-backends)))}
         [""
          (cond->
            {:middleware
             (cond-> []
               (seq auth-backends)
               (conj wrap-auth-guard))}
            (some? transaction-handler)
            (assoc :post transaction-handler))]
         ["/metadata"
          {:get capabilities-handler}]
         ["/_history"
          (cond->
            {:middleware
             (cond-> []
               (seq auth-backends)
               (conj wrap-auth-guard))}
            (some? history-system-handler)
            (assoc :get history-system-handler))]]
        (into
          (map #(compartment-route auth-backends %))
          compartments)
        (into
          (comp
            structure-definition-filter
            (map #(resource-route auth-backends resource-patterns %))
            (remove nil?))
          structure-definitions)
        (into
          (mapcat
            (fn [{:blaze.rest-api.operation/keys
                  [code system-handler]}]
              (when system-handler
                [(str "/$" code)
                 {:middleware
                  (cond-> []
                    (seq auth-backends)
                    (conj wrap-auth-guard))
                  :get system-handler
                  :post system-handler}])))
          operations)
        (into
          (mapcat
            (fn [{:blaze.rest-api.operation/keys
                  [code resource-types type-handler]}]
              (when type-handler
                (map
                  (fn [resource-type]
                    [(str "/" resource-type "/$" code)
                     {:middleware
                      (cond-> []
                        (seq auth-backends)
                        (conj wrap-auth-guard))
                      :get type-handler
                      :post type-handler}])
                  resource-types))))
          operations)
        (into
          (mapcat
            (fn [{:blaze.rest-api.operation/keys
                  [code resource-types instance-handler]}]
              (when instance-handler
                (map
                  (fn [resource-type]
                    [(str "/" resource-type "/{id}/$" code)
                     {:middleware
                      (cond-> []
                        (seq auth-backends)
                        (conj wrap-auth-guard))
                      :get instance-handler
                      :post instance-handler}])
                  resource-types))))
          operations))
    {:path context-path
     :syntax :bracket
     :router reitit/mixed-router
     :validate reitit.ring.spec/validate
     :coerce reitit.ring/coerce-handler
     :compile reitit.ring/compile-result
     :reitit.ring/default-options-handler
     (fn [{::reitit/keys [match]}]
       (let [methods (->> match :result (keep (fn [[k v]] (when v k))))
             allowed-methods
             (->> methods (map (comp str/upper-case name)) (str/join ","))]
         (-> (ring/response {})
             (ring/header "Access-Control-Allow-Methods" allowed-methods)
             (ring/header "Access-Control-Allow-Headers" "content-type"))))}))


(defn- capability-resource
  {:arglists '([resource-patterns operations search-param-registry structure-definition])}
  [resource-patterns operations search-param-registry
   {:keys [name] :as structure-definition}]
  (when-let
    [{:blaze.rest-api.resource-pattern/keys [interactions]}
     (resolve-pattern resource-patterns structure-definition)]
    (let [operations
          (filter
            #(some #{name} (:blaze.rest-api.operation/resource-types %))
            operations)]
      (cond->
        {:type name
         :interaction
         (reduce
           (fn [res code]
             (if-let
               [{:blaze.rest-api.interaction/keys [doc]} (get interactions code)]
               (conj
                 res
                 (cond->
                   {:code (clojure.core/name code)}
                   doc
                   (assoc :documentation doc)))
               res))
           []
           [:read
            :vread
            :update
            :delete
            :history-instance
            :history-type
            :create
            :search-type])
         :versioning "versioned"
         :readHistory true
         :updateCreate true
         :conditionalCreate false
         :conditionalRead "not-supported"
         :conditionalUpdate false
         :conditionalDelete "not-supported"
         :referencePolicy
         ["literal"
          "enforced"
          "local"]
         :searchParam
         (into
           []
           (map
             (fn [{:keys [name url type]}]
               (cond-> {:name name :type type}
                 url
                 (assoc :definition url))))
           (sr/list-by-type search-param-registry name))}

        (seq operations)
        (assoc
          :operation
          (into
            []
            (mapcat
              (fn [{:blaze.rest-api.operation/keys
                    [code def-uri type-handler instance-handler]}]
                (when (or type-handler instance-handler)
                  [{:name code
                    :definition def-uri}])))
            operations))))))


(defn capabilities-handler
  [{:keys
    [base-url
     version
     context-path
     structure-definitions
     search-param-registry
     transaction-handler
     history-system-handler
     resource-patterns
     operations]
    :or {context-path ""}}]
  (let [capability-statement
        {:resourceType "CapabilityStatement"
         :status "active"
         :kind "instance"
         :date "2020-02-25"
         :software
         {:name "Blaze"
          :version version}
         :implementation
         {:description (str "Blaze running at " base-url context-path)
          :url (str base-url context-path)}
         :fhirVersion "4.0.1"
         :format ["application/fhir+json"]
         :rest
         [{:mode "server"
           :resource
           (into
             []
             (comp
               structure-definition-filter
               (map #(capability-resource resource-patterns operations search-param-registry %))
               (remove nil?))
             structure-definitions)
           :interaction
           (cond-> []
             (some? transaction-handler)
             (conj {:code "transaction"} {:code "batch"})
             (some? history-system-handler)
             (conj {:code "history-system"}))}]}]
    (fn [_]
      (ac/completed-future (ring/response capability-statement)))))


(def default-handler
  (reitit.ring/create-default-handler
    {:not-found
     (fn [request]
       (ac/completed-future
         (ring/not-found
           {:resourceType "OperationOutcome"
            :issue
            [{:severity "error"
              :code "not-found"
              :request (str request)}]})))
     :method-not-allowed
     (fn [{:keys [uri request-method]}]
       (-> (ring/response
             {:resourceType "OperationOutcome"
              :issue
              [{:severity "error"
                :code "processing"
                :diagnostics (format "Method %s not allowed on `%s` endpoint."
                                     (str/upper-case (name request-method)) uri)}]})
           (ring/status 405)
           (ac/completed-future)))
     :not-acceptable
     (fn [_]
       (-> (ring/response
             {:resourceType "OperationOutcome"
              :issue
              [{:severity "error"
                :code "structure"}]})
           (ring/status 406)
           (ac/completed-future)))}))


(defn handler
  "Whole app Ring handler."
  [{:blaze.rest-api.json-parse/keys [executor] :as config}]
  (-> (reitit.ring/ring-handler
        (router config (capabilities-handler config))
        default-handler)
      (wrap-json executor)))


(defn- json-parse-executor-init-msg []
  (format "Init JSON parse executor with %d threads"
          (.availableProcessors (Runtime/getRuntime))))


(defmethod ig/init-key :blaze.rest-api.json-parse/executor
  [_ _]
  (log/info (json-parse-executor-init-msg))
  (ex/cpu-bound-pool "blaze-json-parse-%d"))


(derive :blaze.rest-api.json-parse/executor :blaze.metrics/thread-pool-executor)


(defmethod ig/pre-init-spec :blaze/rest-api [_]
  (s/keys
    :req
    [:blaze.rest-api.json-parse/executor]
    :req-un
    [:blaze/base-url
     ::version
     ::structure-definitions
     :blaze.db/search-param-registry]
    :opt-un
    [::context-path
     ::auth-backends
     ::transaction-handler
     ::history-system-handler
     ::resource-patterns
     ::operations]))


(defmethod ig/init-key :blaze/rest-api
  [_ {:keys [base-url context-path] :as config}]
  (log/info
    "Init FHIR RESTful API with base URL:" (str base-url context-path))
  (handler config))


(reg-collector ::requests-total
  metrics/requests-total)


(reg-collector ::request-duration-seconds
  metrics/request-duration-seconds)


(reg-collector ::parse-duration-seconds
  json/parse-duration-seconds)


(reg-collector ::generate-duration-seconds
  json/generate-duration-seconds)
