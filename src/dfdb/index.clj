(ns dfdb.index
  "EAV index management."
  (:require [dfdb.storage :as storage]))

(defn eavt-key
  "Create EAVT index key: [:eavt entity attribute value tx-id]
  Prefix with index type to avoid collisions between indexes."
  [e a v tx-id]
  [:eavt e a v tx-id])

(defn aevt-key
  "Create AEVT index key: [:aevt attribute entity value tx-id]"
  [a e v tx-id]
  [:aevt a e v tx-id])

(defn avet-key
  "Create AVET index key: [:avet attribute value entity tx-id]"
  [a v e tx-id]
  [:avet a v e tx-id])

(defn vaet-key
  "Create VAET index key: [:vaet value attribute entity tx-id]
  Used for reference lookups."
  [v a e tx-id]
  [:vaet v a e tx-id])

(defn datom
  "Create a datom (fact) with entity, attribute, value, tx-time, tx-id, and operation."
  [e a v t tx-id op]
  {:e e :a a :v v :t t :tx-id tx-id :op op})

(defn index-datom
  "Index a datom in all indexes. Returns seq of storage ops."
  [datom]
  (let [{:keys [e a v tx-id op]} datom]
    (case op
      :assert
      [[:put (eavt-key e a v tx-id) datom]
       [:put (aevt-key a e v tx-id) datom]
       [:put (avet-key a v e tx-id) datom]
       ;; VAET only for reference values (integers assumed to be entity IDs)
       (when (integer? v)
         [:put (vaet-key v a e tx-id) datom])]

      :retract
      ;; For retract, we still store the datom to maintain history
      [[:put (eavt-key e a v tx-id) datom]
       [:put (aevt-key a e v tx-id) datom]
       [:put (avet-key a v e tx-id) datom]
       (when (integer? v)
         [:put (vaet-key v a e tx-id) datom])])))

(defn datom-comparator
  "Compare datoms by tx-id (descending) for deterministic ordering.
  Tx-id is a logical clock that provides total ordering of transactions.
  Returns -1, 0, or 1 for sort-by."
  [d1 d2]
  (compare (:tx-id d2) (:tx-id d1)))  ; Descending tx-id

(defn entity-at
  "Get all attributes of entity e as-of time t and/or tx-id.
  Filters by both time and tx-id (whichever is more restrictive).
  Returns map of {attribute -> value}."
  ([storage e t]
   (entity-at storage e t ##Inf))
  ([storage e t tx-id]
   (let [start-key [:eavt e]
         ;; For string IDs, append unicode; for numbers use inc
         end-key (if (string? e)
                   [:eavt (str e "\uFFFF")]
                   [:eavt (inc e)])
         datoms (storage/scan storage start-key end-key)]
     (->> datoms
          (map second)  ; get datom from [key datom] pair
          (filter (fn [d] (and (<= (:t d) t)  ; wall-clock time constraint
                               (<= (:tx-id d) tx-id))))  ; tx-id constraint
          (group-by :a)  ; group by attribute
          (map (fn [[a ds]]
                 ;; Get the most recent value for this attribute
                 (let [sorted (sort datom-comparator ds)  ; descending by tx-id
                       latest (first sorted)]
                   (when (= :assert (:op latest))
                     [a (:v latest)]))))
          (filter some?)
          (into {})))))

(defn scan-eavt
  "Scan EAVT index from start to end pattern."
  [storage start end]
  (storage/scan storage start end))

(defn scan-aevt
  "Scan AEVT index from start to end pattern."
  [storage start end]
  (storage/scan storage start end))

(defn scan-avet
  "Scan AVET index from start to end pattern."
  [storage start end]
  (storage/scan storage start end))

(defn scan-vaet
  "Scan VAET index from start to end pattern."
  [storage start end]
  (storage/scan storage start end))

(defn attribute-values
  "Get all values for attribute a as-of time t."
  [storage a t]
  (let [start-key [:aevt a]
        ;; Scan to next attribute in AEVT index
        end-key [:aevt (keyword (str (name a) "\uFFFF"))]
        datoms (storage/scan storage start-key end-key)]
    (->> datoms
         (map second)
         (filter (fn [d] (and (= a (:a d)) (<= (:t d) t))))  ; filter by attribute and time
         (group-by (fn [d] [(:e d) (:a d)]))  ; group by entity+attribute
         (map (fn [[_ ds]]
                (let [sorted (sort datom-comparator ds)
                      latest (first sorted)]
                  (when (= :assert (:op latest))
                    latest))))
         (filter some?)
         (map :v))))

(defn successor-value
  "Get a value that is lexicographically after v for range scans."
  [v]
  (cond
    (string? v) (str v "\uFFFF")
    (number? v) ##Inf  ; Use infinity for number upper bound
    (keyword? v) (keyword (namespace v) (str (name v) "\uFFFF"))
    (inst? v) (java.util.Date. Long/MAX_VALUE)
    :else v))  ; For other types, return as-is and rely on filtering

(defn lookup-ref
  "Resolve lookup ref [attribute value] to entity ID as-of time t.
  Returns entity ID or nil if not found."
  [storage lookup-ref t]
  (let [[a v] lookup-ref
        start-key [:avet a v]
        ;; Scan to next value using proper successor based on type
        end-key [:avet a (successor-value v)]
        datoms (storage/scan storage start-key end-key)]
    (->> datoms
         (map second)
         (filter (fn [d] (and (= a (:a d)) (= v (:v d)) (<= (:t d) t))))
         (group-by :e)  ; group by entity
         (keep (fn [[e ds]]
                 (let [sorted (sort datom-comparator ds)
                       latest (first sorted)]
                   (when (= :assert (:op latest))
                     e))))
         first)))
