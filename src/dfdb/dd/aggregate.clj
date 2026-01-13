(ns dfdb.dd.aggregate
  "Aggregate operators for differential dataflow."
  (:require [dfdb.dd.multiset :as ms]
            [dfdb.dd.operator :as op]))

(defrecord AggregateOperator [group-fn agg-fn state downstream]
  ;; group-fn: extract grouping key from value
  ;; agg-fn: aggregate function (count, sum, avg, etc.)
  ;; state: maintains current aggregates per group

  op/Operator
  (input [_this coll timestamp]
    ;; Group values and compute aggregates
    (let [grouped (group-by group-fn (mapcat (fn [[value count]]
                                               (repeat count value))
                                             (seq coll)))
          aggregates (into {}
                           (map (fn [[group-key values]]
                                  [group-key (agg-fn values)])
                                grouped))]

      ;; Store in state
      (swap! (:aggregates state) assoc timestamp aggregates)

      ;; Forward to downstream as collection
      (when downstream
        (let [output-coll (ms/multiset
                           (into {}
                                 (map (fn [[k v]] [[k v] 1])
                                      aggregates)))]
          (op/input downstream output-coll timestamp)))))

  (step [_this]
    false)

  (output [_this]
    (when downstream
      (op/output downstream)))

  (frontier [_this]
    (when downstream
      (op/frontier downstream)))

  op/Stateful
  (state [_this]
    {:aggregates @(:aggregates state)}))

(defn make-aggregate-operator
  "Create an aggregate operator.
  group-fn: function to extract grouping key
  agg-fn: aggregation function (count, sum, avg, min, max)"
  [group-fn agg-fn downstream]
  (->AggregateOperator
   group-fn
   agg-fn
   (assoc (op/create-operator-state)
          :aggregates (atom {}))
   downstream))

;; Standard aggregation functions

(defn agg-count [values]
  (count values))

(defn agg-sum [values]
  (reduce + 0 values))

(defn agg-avg [values]
  (if (empty? values)
    0.0
    (/ (reduce + 0 values) (double (count values)))))

(defn agg-min [values]
  (when (seq values)
    (reduce min values)))

(defn agg-max [values]
  (when (seq values)
    (reduce max values)))

;; =============================================================================
;; GroupOperator
;; =============================================================================

(defrecord GroupOperator [key-fn state downstream]
  ;; key-fn: extract grouping key from value

  op/Operator
  (input [_this coll timestamp]
    ;; Group by key
    (let [grouped (group-by key-fn
                            (mapcat (fn [[value count]]
                                      (repeat count value))
                                    (seq coll)))]

      ;; Store groups
      (swap! (:groups state) assoc timestamp grouped)

      ;; Forward groups to downstream
      ;; Each group becomes a separate collection
      (when downstream
        (doseq [[_group-key group-values] grouped]
          (let [group-coll (ms/multiset (frequencies group-values))]
            (op/input downstream group-coll timestamp))))))

  (step [_this]
    false)

  (output [_this]
    (when downstream
      (op/output downstream)))

  (frontier [_this]
    (when downstream
      (op/frontier downstream)))

  op/Stateful
  (state [_this]
    {:groups @(:groups state)}))

(defn make-group-operator
  "Create a group operator."
  [key-fn downstream]
  (->GroupOperator
   key-fn
   (assoc (op/create-operator-state)
          :groups (atom {}))
   downstream))
