(ns dfdb.pull
  "Datomic-style pull API for hierarchical data retrieval."
  (:require [dfdb.index :as index]))

(set! *warn-on-reflection* true)

(defn pull-attribute-values
  "Get all values for an attribute on an entity."
  [db entity-id attr]
  (let [start-key [:eavt entity-id attr]
        end-key [:eavt entity-id (index/successor-value attr)]
        datoms-map (index/scan-eavt (:storage db) start-key end-key)
        datoms (map second datoms-map)]
    (when (seq datoms)
      (let [values (mapv :v datoms)]
        (if (= 1 (count values))
          (first values)
          values)))))

(defn pull-all-attributes
  "Pull all attributes of entity with [*] pattern."
  [db entity-id]
  (let [start-key [:eavt entity-id]
        end-key [:eavt (inc entity-id)]
        datoms-map (index/scan-eavt (:storage db) start-key end-key)
        datoms (map second datoms-map)
        attrs (group-by :a datoms)]
    (into {:db/id entity-id}
          (map (fn [[attr attr-datoms]]
                 (let [values (mapv :v attr-datoms)]
                   [attr (if (= 1 (count values))
                           (first values)
                           values)]))
               attrs))))

(declare pull)

(defn pull-attributes
  "Pull specific attributes from pattern."
  [db entity-id pattern]
  (reduce (fn [result attr-spec]
            (cond
              ;; Nested pull: {:ref-attr [:nested-attr]} or {:attr/_ref [:nested]}
              (map? attr-spec)
              (let [[ref-attr nested-pattern] (first attr-spec)
                    options (dissoc attr-spec ref-attr)
                    limit (:limit options)
                    is-reverse? (and (keyword? ref-attr)
                                     (.startsWith (name ref-attr) "_"))]

                (if is-reverse?
                  ;; Reverse lookup with nested pull
                  (let [forward-attr (keyword (namespace ref-attr)
                                              (subs (name ref-attr) 1))
                        start-key [:vaet entity-id forward-attr]
                        end-key [:vaet entity-id (index/successor-value forward-attr)]
                        datoms-map (index/scan-vaet (:storage db) start-key end-key)
                        datoms (map second datoms-map)
                        ref-entities (mapv :e datoms)]
                    (if (seq ref-entities)
                      (let [pulled-refs (mapv #(pull db % nested-pattern) ref-entities)
                            limited (if limit
                                      (vec (take limit pulled-refs))
                                      pulled-refs)]
                        (assoc result ref-attr limited))
                      result))

                  ;; Forward reference with nested pull
                  (let [ref-values (pull-attribute-values db entity-id ref-attr)]
                    (cond
                      ;; No reference value
                      (nil? ref-values)
                      result

                      ;; Single reference
                      (not (coll? ref-values))
                      (assoc result ref-attr (pull db ref-values nested-pattern))

                      ;; Multiple references
                      :else
                      (let [pulled-refs (mapv #(pull db % nested-pattern) ref-values)
                            limited (if limit
                                      (vec (take limit pulled-refs))
                                      pulled-refs)]
                        (assoc result ref-attr limited))))))

              ;; Reverse lookup: :attr/_ref
              (and (keyword? attr-spec)
                   (.startsWith (name attr-spec) "_"))
              (let [forward-attr (keyword (namespace attr-spec)
                                          (subs (name attr-spec) 1))
                    ;; Find all entities that reference this entity via forward-attr
                    start-key [:vaet entity-id forward-attr]
                    end-key [:vaet entity-id (index/successor-value forward-attr)]
                    datoms-map (index/scan-vaet (:storage db) start-key end-key)
                    datoms (map second datoms-map)
                    ref-entities (mapv :e datoms)]
                (if (seq ref-entities)
                  (assoc result attr-spec ref-entities)
                  result))

              ;; Simple attribute
              (keyword? attr-spec)
              (if-let [value (pull-attribute-values db entity-id attr-spec)]
                (assoc result attr-spec value)
                result)

              ;; Unknown spec type
              :else
              result))
          {:db/id entity-id}
          pattern))

(defn pull
  "Pull entity attributes matching pattern.

  Patterns:
  - [*] - all attributes
  - [:attr1 :attr2] - specific attributes
  - [{:ref-attr [:nested-attr]}] - nested pull
  - [{:ref-attr [*]}] - nested wildcard
  - [:attr/_ref] - reverse lookup
  - [{:attr [:nested] :limit 10}] - with limit"
  [db entity-id pattern]
  (cond
    ;; Wildcard - all attributes
    (and (vector? pattern) (= 1 (count pattern)) (= '* (first pattern)))
    (pull-all-attributes db entity-id)

    ;; Attribute list
    (vector? pattern)
    (pull-attributes db entity-id pattern)

    ;; Invalid pattern
    :else
    (throw (ex-info "Invalid pull pattern" {:pattern pattern}))))
