(ns dfdb.index
  "EAV index management."
  (:require [dfdb.storage :as storage]))

(set! *warn-on-reflection* true)

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
  Returns map of {attribute -> value}.

  OPTIMIZED: Single-pass state machine instead of multiple map/filter/group-by passes."
  ([storage e t]
   (entity-at storage e t ##Inf))
  ([storage e t tx-id]
   (let [start-key [:eavt e]
         ;; For string IDs, append unicode; for numbers use inc
         end-key (if (string? e)
                   [:eavt (str e "\uFFFF")]
                   [:eavt (inc e)])
         datoms (storage/scan storage start-key end-key)]
     ;; Single-pass state machine: group by attribute and track latest values
     (loop [items datoms
            by-attr (transient {})]
       (if-let [[_k datom] (first items)]
         (if (and (<= (:t datom) t)
                  (<= (:tx-id datom) tx-id))
           (let [a (:a datom)
                 current-attr (get by-attr a)]
             ;; Track all datoms for this attribute
             (recur (rest items)
                    (assoc! by-attr a (conj (or current-attr []) datom))))
           (recur (rest items) by-attr))
         ;; Process accumulated datoms per attribute
         (loop [attrs (seq (persistent! by-attr))
                result (transient {})]
           (if-let [[a ds] (first attrs)]
             (let [sorted-all (sort datom-comparator ds)
                   absolute-latest (first sorted-all)]
               (if (and (= :retract (:op absolute-latest))
                        (nil? (:v absolute-latest)))
                 ;; Attribute fully retracted
                 (recur (rest attrs) result)
                 ;; Find latest asserted values
                 (let [;; Group by value and find latest per value
                       by-value (reduce (fn [acc d]
                                          (let [v (:v d)]
                                            (if-let [current (get acc v)]
                                              (if (> (:tx-id d) (:tx-id current))
                                                (assoc acc v d)
                                                acc)
                                              (assoc acc v d))))
                                        {}
                                        ds)
                       ;; Keep only asserted values
                       asserted (keep (fn [[v d]]
                                        (when (= :assert (:op d))
                                          {:value v :tx-id (:tx-id d)}))
                                      by-value)]
                   (if (seq asserted)
                     (let [latest-tx (reduce max (map :tx-id asserted))
                           latest-values (map :value (filter #(= latest-tx (:tx-id %)) asserted))]
                       (recur (rest attrs)
                              (assoc! result a
                                      (if (> (count latest-values) 1)
                                        (set latest-values)
                                        (first latest-values)))))
                     (recur (rest attrs) result)))))
             (persistent! result))))))))

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
  "Get all values for attribute a as-of time t.

  OPTIMIZED: Single-pass with transient collections instead of multi-pass pipeline."
  [storage a t]
  (let [start-key [:aevt a]
        ;; Scan to next attribute in AEVT index
        end-key [:aevt (keyword (str (name a) "\uFFFF"))]
        datoms (storage/scan storage start-key end-key)]
    ;; Single pass: group by entity+attr and track latest
    (loop [items datoms
           by-ea (transient {})]
      (if-let [[_k datom] (first items)]
        (if (and (= a (:a datom)) (<= (:t datom) t))
          (let [ea [(:e datom) (:a datom)]
                current (get by-ea ea)]
            ;; Keep datom with highest tx-id
            (recur (rest items)
                   (if (or (nil? current)
                           (> (:tx-id datom) (:tx-id current)))
                     (assoc! by-ea ea datom)
                     by-ea)))
          (recur (rest items) by-ea))
        ;; Extract values from asserted datoms
        (loop [entries (seq (persistent! by-ea))
               result (transient [])]
          (if-let [[_ea datom] (first entries)]
            (if (= :assert (:op datom))
              (recur (rest entries) (conj! result (:v datom)))
              (recur (rest entries) result))
            (persistent! result)))))))

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
