(ns dfdb.dimensions
  "Multi-dimensional time support."
  (:require [dfdb.db :as db]
            [dfdb.index :as index]))

(declare get-entity-dimensions)

(def system-time-dimension
  "Built-in system-time dimension (always present, immutable)."
  {:dimension/name :time/system
   :dimension/type :instant
   :dimension/indexed? true
   :dimension/retroactive? false
   :dimension/description "When fact entered the database (immutable audit trail)"})

(defn dimension-entity?
  "Check if entity is a time dimension definition."
  [entity-map]
  (contains? entity-map :dimension/name))

(defn get-dimension
  "Get dimension by name from database."
  [db dim-name]
  (cond
    (= dim-name :time/system)
    system-time-dimension

    :else
    (db/entity-by db :dimension/name dim-name)))

(defn validate-dimension-exists
  "Validate that dimension exists, throw if not."
  [db dim-name]
  (when-not (get-dimension db dim-name)
    (throw (ex-info "Undefined time dimension"
                    {:dimension dim-name}))))

(defn validate-system-time-not-retroactive
  "Validate that system-time is not being set retroactively."
  [time-dimensions]
  (when (contains? time-dimensions :time/system)
    (throw (ex-info "system-time cannot be set retroactively - it is managed by the system"
                    {:attempted-value (get time-dimensions :time/system)}))))

(defn validate-ordering-constraint
  "Validate ordering constraint between dimensions.
  Constraint: {:type :ordering :after :time/ordered}"
  [dim-name constraint time-dimensions]
  (let [after-dim (:after constraint)
        this-dim-val (get time-dimensions dim-name)
        after-dim-val (get time-dimensions after-dim)]
    (when (and this-dim-val after-dim-val)
      (when (< (.getTime this-dim-val) (.getTime after-dim-val))
        (throw (ex-info (str "Constraint violation - " dim-name " must be after " after-dim)
                        {:dimension dim-name
                         :constraint constraint
                         :value this-dim-val
                         :after-value after-dim-val}))))))

(defn validate-derived-dimension
  "Validate and compute derived dimension value.
  Derivation: {:op :minus :operands [:time/delivered :time/shipped]}"
  [_db derivation time-dimensions]
  (let [{:keys [op operands]} derivation]
    (case op
      :minus
      (let [[dim1 dim2] operands
            v1 (get time-dimensions dim1)
            v2 (get time-dimensions dim2)]
        (when (and v1 v2)
          (- (.getTime v1) (.getTime v2)))))))

(defn validate-constraints
  "Validate all constraints for dimension definitions in time-dimensions map."
  [db time-dimensions]
  ;; Check each dimension has constraints
  (doseq [[dim-name _dim-value] time-dimensions]
    (when-not (= dim-name :time/system)
      (when-let [dim (get-dimension db dim-name)]
        (when-let [constraints (:dimension/constraints dim)]
          (doseq [constraint constraints]
            (case (:type constraint)
              :ordering
              (validate-ordering-constraint
               dim-name
               constraint
               time-dimensions)

              :derived
              ;; Derived dimensions are computed, not validated
              nil

              ;; User-defined constraints
              (when-let [constraint-fn (:fn constraint)]
                (when-not (constraint-fn time-dimensions)
                  (throw (ex-info "Constraint violation - user-defined constraint failed"
                                  {:constraint constraint
                                   :dimensions time-dimensions})))))))))))

(defn get-all-dimensions
  "Get all defined dimensions from database."
  [db]
  (let [storage (:storage db)
        start-key [:avet :dimension/name]
        end-key [:avet (index/successor-value :dimension/name)]]
    ;; Scan for all entities with :dimension/name attribute
    (->> (index/scan-avet storage start-key end-key)
         (map (fn [[_k datom]] (:e datom)))
         (distinct)
         (map #(db/entity db %))
         (filter some?)
         (cons system-time-dimension))))

(defn compute-derived-dimensions
  "Compute values for derived dimensions based on time-dimensions map.
  Looks for ALL defined dimensions with derivations and computes them."
  [db time-dimensions]
  (let [all-dims (atom time-dimensions)]
    ;; Check each dimension that was provided
    (doseq [[dim-name _dim-value] time-dimensions]
      (when-let [dim (get-dimension db dim-name)]
        (when-let [derivation (:dimension/derived-from dim)]
          (when-let [computed (validate-derived-dimension db derivation @all-dims)]
            (swap! all-dims assoc (:dimension/name dim) computed)))))

    ;; Also check for derived dimensions that might exist but weren't provided
    ;; Query for all dimensions with derivations
    (doseq [dim (get-all-dimensions db)]
      (when-let [derivation (:dimension/derived-from dim)]
        (let [dim-name (:dimension/name dim)]
          (when-not (contains? @all-dims dim-name)
            (when-let [computed (validate-derived-dimension db derivation @all-dims)]
              (swap! all-dims assoc dim-name computed))))))

    @all-dims))

(defn enrich-time-dimensions
  "Enrich time-dimensions map with system-time and validate all dimensions.
  For updates, merges with existing entity dimensions before validation."
  [db time-dimensions tx-time entity-id]
  (let [;; Add system-time (always present)
        with-system (assoc time-dimensions :time/system (java.util.Date. tx-time))

        ;; Get existing dimensions from entity if updating
        existing-dims (when entity-id (get-entity-dimensions db entity-id))

        ;; Merge existing with new (new takes precedence)
        merged-dims (merge existing-dims with-system)]

    ;; Validate system-time not retroactive
    (validate-system-time-not-retroactive time-dimensions)

    ;; Validate all dimensions exist
    (doseq [dim-name (keys time-dimensions)]
      (validate-dimension-exists db dim-name))

    ;; Validate constraints against MERGED dimensions (includes existing)
    (validate-constraints db merged-dims)

    ;; Compute derived dimensions
    (compute-derived-dimensions db with-system)))

(defn get-entity-dimensions
  "Get all time dimensions from an entity by scanning its datoms."
  [db entity-id]
  (when entity-id
    (let [storage (:storage db)
          start-key [:eavt entity-id]
          end-key (if (string? entity-id)
                    [:eavt (str entity-id "\uFFFF")]
                    [:eavt (inc entity-id)])
          datoms (index/scan-eavt storage start-key end-key)]
      (reduce (fn [acc [_k datom]]
                (merge acc
                       (select-keys datom
                                    (filter #(and (keyword? %)
                                                  (namespace %)
                                                  (.startsWith (namespace %) "time"))
                                            (keys datom)))))
              {}
              datoms))))
