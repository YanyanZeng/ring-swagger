(ns ring.swagger.core2
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [ring.util.response :refer :all]
            [ring.swagger.impl :refer :all]
            [schema.core :as s]
            [plumbing.core :refer :all]
            [ring.swagger.common :refer :all]
            [ring.swagger.json-schema :as jsons]
            [org.tobereplaced.lettercase :as lc]))

(alter-var-root #'jsons/*swagger-spec-version* (constantly "2.0"))

(def Anything {s/Keyword s/Any})
(def Nothing {})

;;
;; defaults
;;

(def swagger-defaults {:swagger  "2.0"
                       :info     {:title "Swagger API"
                                  :version "0.0.1"}
                       :produces ["application/json"]
                       :consumes ["application/json"]})

;
;; Schema transformations
;;

(defn- requires-definition? [schema]
  (not (contains? #{nil Nothing Anything}
                  (s/schema-name schema))))

(defn- full-name [path] (->> path (map name) (map lc/capitalized) (apply str) symbol))

(defn- collect-schemas [keys schema]
  (cond
    (plain-map? schema)
    (if (and (seq (pop keys)) (s/schema-name schema))
      schema
      (with-meta
        (into (empty schema)
              (for [[k v] schema
                    :when (jsons/not-predicate? k)
                    :let [keys (conj keys (s/explicit-schema-key k))]]
                [k (collect-schemas keys v)]))
        {:name (full-name keys)}))

    (valid-container? schema)
    (contain schema (collect-schemas keys (first schema)))

    :else schema))

(defn with-named-sub-schemas
  "Traverses a schema tree of Maps, Sets and Sequences and add Schema-name to all
   anonymous maps between the root and any named schemas in thre tree. Names of the
   schemas are generated by the following: Root schema name (or a generated name) +
   all keys in the path CamelCased"
  [schema]
  (collect-schemas [(or (s/schema-name schema) (gensym "schema"))] schema))

(defn extract-models [swagger]
  (let [route-meta      (->> swagger
                             :paths
                             vals
                             flatten)
        body-models     (->> route-meta
                             (map (comp :body :parameters))
                             (filter requires-definition?))
        response-models (->> route-meta
                             (map :responses)
                             (mapcat vals)
                             (map :schema)
                             flatten
                             (filter requires-definition?))
        all-models      (->> (concat body-models response-models)
                             flatten
                             (map with-named-sub-schemas))]
    (->> all-models
         (map (juxt s/schema-name identity))
         (into {})
         vals)))

(defn transform [schema]
  (let [required (required-keys schema)
        required (if-not (empty? required) required)]
    (remove-empty-keys
      {:properties (jsons/properties schema)
       :required required})))

(defn collect-models [x]
  (let [schemas (atom {})]
    (walk/prewalk
      (fn [x]
        (when (requires-definition? x)
          (swap! schemas assoc (s/schema-name x) (if (var? x) @x x)))
        x)
      x)
    @schemas))

(defn transform-models [schemas]
  (->> schemas
       (map collect-models)
       (apply merge)
       (map (juxt (comp keyword key) (comp transform val)))
       (into {})))

;;
;; Paths, parameters, responses
;;

(defmulti ^:private extract-body-paramter
  (fn [e]
    (if (instance? java.lang.Class e)
      e
      (class e))))

(defmethod extract-body-paramter clojure.lang.Sequential [e]
  (let [model (first e)
        schema-json (jsons/->json model)]
    (vector {:in          :body
             :name        (name (s/schema-name model))
             :description (or (:description schema-json) "")
             :required    true
             :schema      {:type  "array"
                           :items (dissoc schema-json :description)}})))

(defmethod extract-body-paramter clojure.lang.IPersistentSet [e]
  (let [model (first e)
        schema-json (jsons/->json model)]
    (vector {:in          :body
             :name        (name (s/schema-name model))
             :description (or (:description schema-json) "")
             :required    true
             :schema      {:type        "array"
                           :uniqueItems true
                           :items       (dissoc schema-json :description)}})))

(defmethod extract-body-paramter :default [model]
  (if-let [schema-name (s/schema-name model)]
    (let [schema-json (jsons/->json model)]
     (vector {:in          :body
              :name        (name schema-name)
              :description (or (:description schema-json) "")
              :required    true
              :schema      (dissoc schema-json :description)}))))


(defmulti ^:private extract-parameter first)

(defmethod extract-parameter :body [[_ model]]
  (extract-body-paramter model))

(defmethod extract-parameter :default [[type model]]
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :when (s/specific-key? k)
          :let [rk (s/explicit-schema-key (eval k))]]
      (jsons/->parameter {:in type
                          :name (name rk)
                          :required (s/required-key? k)}
                         (jsons/->json v)))))

(defn convert-parameters [parameters]
  (into [] (mapcat extract-parameter parameters)))

(defn convert-response-messages [responses]
  (letfn [(response-schema [schema]
            (if-let [name (s/schema-name schema)]
              (str "#/definitions/" name)
              (transform schema)))]
    (zipmap (keys responses)
            (map (fn [r] (update-in r [:schema] response-schema))
                 (vals responses)))))

(defn transform-path-operations
  "Returns a map with methods as keys and the Operation
   maps with parameters and responses transformed to comply
   with Swagger JSON spec as values"
  [operations]
  (into {} (map (juxt :method #(-> %
                                   (dissoc :method)
                                   (update-in [:parameters] convert-parameters)
                                   (update-in [:responses]  convert-response-messages)))
                operations)))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn extract-paths-and-definitions [swagger]
  (let [paths       (->> swagger
                         :paths
                         keys
                         (map swagger-path))
        methods     (->> swagger
                         :paths
                         vals
                         (map transform-path-operations))
        definitions (->> swagger
                         extract-models
                         transform-models)]
    (vector (zipmap paths methods) definitions)))

(defn swagger-json [swagger]
  (let [[paths definitions] (extract-paths-and-definitions swagger)]
    (merge
     swagger-defaults
     (-> swagger
          (assoc :paths paths)
          (assoc :definitions definitions)))))

;; https://github.com/swagger-api/swagger-spec/blob/master/schemas/v2.0/schema.json
;; https://github.com/swagger-api/swagger-spec/blob/master/examples/v2.0/json/petstore.json
;; https://github.com/swagger-api/swagger-spec/blob/master/examples/v2.0/json/petstore-with-external-docs.json
;; https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md
