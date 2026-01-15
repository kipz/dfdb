(ns dfdb.dd.incremental-aggregate
  "Incremental aggregate operators using DeltaOperator protocol.

  These operators maintain running aggregates and update them incrementally
  as deltas arrive, providing O(1) updates instead of O(n) recomputation."
  (:require [dfdb.dd.delta-core :as delta]
            [dfdb.dd.incremental-core :as core]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Incremental Aggregate Functions
;; =============================================================================
;; These functions maintain running aggregate state and update incrementally

(defn inc-count
  "Incrementally update count aggregate.
  current: current count (or nil)
  value: the value (ignored for count)
  mult: delta multiplicity (+1 or -1)"
  [current _value mult]
  (+ (or current 0) mult))

(defn inc-sum
  "Incrementally update sum aggregate.
  current: current sum (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)"
  [current value mult]
  (+ (or current 0) (* value mult)))

(defn inc-avg
  "Incrementally update average aggregate.
  current: map {:sum X :count Y} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)"
  [current value mult]
  (let [sum (+ (get current :sum 0) (* value mult))
        count (+ (get current :count 0) mult)]
    {:sum sum :count count :avg (if (zero? count) 0.0 (/ sum (double count)))}))

(defn inc-min
  "Incrementally update min aggregate.
  current: map {:min X :values {value → count}} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)

  Note: Min/max require tracking all values to recompute when min/max is removed"
  [current value mult]
  (let [values (or (:values current) {})
        new-count (+ (get values value 0) mult)
        new-values (if (zero? new-count)
                     (dissoc values value)
                     (assoc values value new-count))
        new-min (when (seq new-values)
                  (apply min (keys new-values)))]
    {:min new-min :values new-values}))

(defn inc-max
  "Incrementally update max aggregate.
  current: map {:max X :values {value → count}} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)"
  [current value mult]
  (let [values (or (:values current) {})
        new-count (+ (get values value 0) mult)
        new-values (if (zero? new-count)
                     (dissoc values value)
                     (assoc values value new-count))
        new-max (when (seq new-values)
                  (apply max (keys new-values)))]
    {:max new-max :values new-values}))

;; =============================================================================
;; Advanced Incremental Aggregates
;; =============================================================================

(defn inc-count-distinct
  "Incrementally update count-distinct aggregate.
  current: map {:values #{...}} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)

  Maintains set of distinct values seen."
  [current value mult]
  (let [values (or (:values current) #{})
        new-values (if (pos? mult)
                     (conj values value)
                     (disj values value))]
    {:values new-values
     :result (count new-values)}))

(defn inc-variance
  "Incrementally update variance using Welford's online algorithm.
  current: map {:count n :mean m :m2 (sum of squared differences)} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)

  Uses Welford's algorithm for numerical stability.
  Handles both additions (mult > 0) and retractions (mult < 0)."
  [current value mult]
  (if (pos? mult)
    ;; Add value (mult times)
    (loop [n (:count current 0)
           mean (:mean current 0.0)
           m2 (:m2 current 0.0)
           remaining mult]
      (if (zero? remaining)
        {:count n :mean mean :m2 m2
         :result (if (> n 0) (/ m2 (double n)) 0.0)}
        (let [n' (inc n)
              delta (- value mean)
              mean' (+ mean (/ delta n'))
              delta2 (- value mean')
              m2' (+ m2 (* delta delta2))]
          (recur n' mean' m2' (dec remaining)))))
    ;; Remove value (mult times) - more complex
    (loop [n (:count current 0)
           mean (:mean current 0.0)
           m2 (:m2 current 0.0)
           remaining (- mult)]
      (if (zero? remaining)
        {:count n :mean mean :m2 m2
         :result (if (> n 0) (/ m2 (double n)) 0.0)}
        (let [n' (dec n)
              delta (- value mean)
              mean' (if (zero? n') 0.0 (- mean (/ delta n')))
              delta2 (- value mean')
              m2' (- m2 (* delta delta2))]
          (recur n' mean' m2' (dec remaining)))))))

(defn inc-stddev
  "Incrementally update standard deviation (sqrt of variance).
  current: variance state
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)"
  [current value mult]
  (let [var-state (inc-variance current value mult)]
    (assoc var-state :result (Math/sqrt (:result var-state)))))

(defn inc-median
  "Incrementally update median aggregate.
  current: map {:values (sorted-map value → count)} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)

  Maintains sorted map of values for O(log n) updates.
  Median calculation is O(n) but only done when needed."
  [current value mult]
  (let [values (or (:values current) (sorted-map))
        current-count (get values value 0)
        new-count (+ current-count mult)
        new-values (if (zero? new-count)
                     (dissoc values value)
                     (assoc values value new-count))
        ;; Flatten to vector for median calculation
        flat-values (vec (mapcat (fn [[v c]] (repeat c v)) new-values))
        total-count (count flat-values)
        median-val (if (zero? total-count)
                     nil
                     (if (even? total-count)
                       (/ (+ (nth flat-values (/ total-count 2))
                             (nth flat-values (dec (/ total-count 2))))
                          2.0)
                       (double (nth flat-values (quot total-count 2)))))]
    {:values new-values
     :count total-count
     :result median-val}))

(defn inc-collect
  "Incrementally update collect aggregate (accumulate all values).
  current: map {:items {value → count}} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)

  Returns flattened vector of all values with multiplicities."
  [current value mult]
  (let [items (or (:items current) {})
        current-count (get items value 0)
        new-count (+ current-count mult)
        new-items (if (zero? new-count)
                    (dissoc items value)
                    (assoc items value new-count))
        ;; Flatten to vector
        result-vec (vec (mapcat (fn [[v c]] (repeat c v)) new-items))]
    {:items new-items
     :result result-vec}))

(defn inc-sample
  "Incrementally update sample aggregate using reservoir sampling.
  current: map {:reservoir [...] :count n} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)
  k: sample size

  Uses reservoir sampling for approximate random sample.
  Good for large datasets where full collection is impractical."
  [current value mult k]
  (let [reservoir (or (:reservoir current) [])
        n (:count current 0)]
    (if (pos? mult)
      ;; Add element (mult times - simplified: add each occurrence)
      (loop [res reservoir
             count n
             remaining mult]
        (if (zero? remaining)
          {:reservoir res :count count :result res}
          (let [new-count (inc count)]
            (recur (if (< (clojure.core/count res) k)
                     (conj res value)
                     ;; Reservoir full - random replacement
                     (let [j (rand-int new-count)]
                       (if (< j k)
                         (assoc res j value)
                         res)))
                   new-count
                   (dec remaining)))))
      ;; Remove element - filter out
      {:reservoir (vec (remove #{value} reservoir))
       :count (+ n mult)
       :result (vec (remove #{value} reservoir))})))

(defn inc-rand
  "Incrementally update rand aggregate (select one random element).
  current: map {:values {value → count}} (or nil)
  value: the value to add/subtract
  mult: delta multiplicity (+1 or -1)

  Returns one random value weighted by multiplicity."
  [current value mult]
  (let [values (or (:values current) {})
        current-count (get values value 0)
        new-count (+ current-count mult)
        new-values (if (zero? new-count)
                     (dissoc values value)
                     (assoc values value new-count))
        ;; Flatten and pick random
        flat (mapcat (fn [[v c]] (repeat c v)) new-values)
        random-val (when (seq flat)
                     (rand-nth (vec flat)))]
    {:values new-values
     :result random-val}))

;; =============================================================================
;; IncrementalAggregateOperator
;; =============================================================================

(deftype IncrementalAggregateOperator [group-fn value-fn agg-fn extract-fn
                                       ^:volatile-mutable aggregates]
  ;; group-fn: extract grouping key from binding (e.g., first element)
  ;; value-fn: extract value to aggregate from binding (e.g., second element)
  ;; agg-fn: incremental aggregate function (inc-count, inc-sum, etc.)
  ;; extract-fn: extract final result from aggregate state (e.g., identity, :avg, :min, :result)
  ;; aggregates: mutable map {group-key → aggregate-state}

  core/DeltaOperator
  (process-delta [_this delta]
    (let [binding (:binding delta)
          mult (:mult delta)
          group-key (group-fn binding)
          value (value-fn binding)]

      ;; Get old aggregate value (before update)
      (let [old-agg-state (get aggregates group-key)
            old-agg-value (when old-agg-state (extract-fn old-agg-state))]

        ;; Update aggregate state incrementally (direct mutation)
        (set! aggregates (update aggregates group-key
                                 (fn [current]
                                   (agg-fn current value mult))))

        ;; Get new aggregate value (after update)
        (let [new-agg-state (get aggregates group-key)
              new-agg-value (when new-agg-state (extract-fn new-agg-state))]

          ;; Emit deltas for the change
          (cond
            ;; New group appeared
            (and (nil? old-agg-value) (some? new-agg-value))
            [(delta/make-delta (if (= :all group-key)
                                 [new-agg-value]
                                 (vec (concat group-key [new-agg-value])))
                               1)]

            ;; Group disappeared
            (and (some? old-agg-value) (nil? new-agg-value))
            (do
              ;; Clean up empty group
              (set! aggregates (dissoc aggregates group-key))
              [(delta/make-delta (if (= :all group-key)
                                   [old-agg-value]
                                   (vec (concat group-key [old-agg-value])))
                                 -1)])

            ;; Group value changed
            (and (some? old-agg-value) (some? new-agg-value)
                 (not= old-agg-value new-agg-value))
            [(delta/make-delta (if (= :all group-key)
                                 [old-agg-value]
                                 (vec (concat group-key [old-agg-value])))
                               -1)
             (delta/make-delta (if (= :all group-key)
                                 [new-agg-value]
                                 (vec (concat group-key [new-agg-value])))
                               1)]

            ;; No change in aggregate value (rare but possible)
            :else
            []))))))

(defn make-incremental-aggregate
  "Create an incremental aggregate operator.

  group-fn: extract grouping key from binding vector
  value-fn: extract value to aggregate from binding vector
  agg-fn: incremental aggregate function (inc-count, inc-sum, etc.)
  extract-fn: extract final result from aggregate state

  Example:
    (make-incremental-aggregate
      first              ; group by first element
      second             ; aggregate second element
      inc-sum            ; sum aggregation
      identity)          ; extract sum directly"
  [group-fn value-fn agg-fn extract-fn]
  (IncrementalAggregateOperator.
   group-fn
   value-fn
   agg-fn
   extract-fn
   {}))

;; =============================================================================
;; Convenience constructors for common aggregates
;; =============================================================================

(defn make-count-aggregate
  "Create incremental count aggregate.
  group-fn: extract grouping key from binding"
  [group-fn]
  (make-incremental-aggregate
   group-fn
   (constantly nil)  ; value not needed for count
   inc-count
   identity))

(defn make-sum-aggregate
  "Create incremental sum aggregate.
  group-fn: extract grouping key from binding
  value-fn: extract value to sum from binding"
  [group-fn value-fn]
  (make-incremental-aggregate
   group-fn
   value-fn
   inc-sum
   identity))

(defn make-avg-aggregate
  "Create incremental average aggregate.
  group-fn: extract grouping key from binding
  value-fn: extract value to average from binding"
  [group-fn value-fn]
  (make-incremental-aggregate
   group-fn
   value-fn
   inc-avg
   :avg))

(defn make-min-aggregate
  "Create incremental min aggregate.
  group-fn: extract grouping key from binding
  value-fn: extract value to find min of from binding"
  [group-fn value-fn]
  (make-incremental-aggregate
   group-fn
   value-fn
   inc-min
   :min))

(defn make-max-aggregate
  "Create incremental max aggregate.
  group-fn: extract grouping key from binding
  value-fn: extract value to find max of from binding"
  [group-fn value-fn]
  (make-incremental-aggregate
   group-fn
   value-fn
   inc-max
   :max))

;; =============================================================================
;; Multi-Aggregate Operator (for combining multiple aggregates)
;; =============================================================================

(deftype MultiAggregateOperator [group-fn agg-specs
                                 ^:volatile-mutable aggregates]
  ;; group-fn: extract grouping key from binding
  ;; agg-specs: vector of {:value-fn ... :agg-fn ... :extract-fn ...}
  ;; aggregates: map of {group-key → [state1 state2 ...]}

  core/DeltaOperator
  (process-delta [this delta]
    (let [binding ^clojure.lang.IPersistentVector (:binding delta)
          mult ^long (:mult delta)
          group-key (group-fn binding)]

      ;; Get old aggregate values (before update)
      (let [old-agg-states (get aggregates group-key)
            old-agg-values (when old-agg-states
                             (mapv (fn [agg-state agg-spec]
                                     ((:extract-fn agg-spec) agg-state))
                                   old-agg-states
                                   agg-specs))]

        ;; Update all aggregates for this group (direct mutation)
        (set! aggregates (update aggregates group-key
                                 (fn [current-states]
                                   (vec
                                    (map-indexed
                                     (fn [idx agg-spec]
                                       (let [current-state (when current-states (nth current-states idx nil))
                                             value ((:value-fn agg-spec) binding)
                                             agg-fn (:agg-fn agg-spec)]
                                         (agg-fn current-state value mult)))
                                     agg-specs)))))

        ;; Get new aggregate values (after update)
        (let [new-agg-states (get aggregates group-key)
              new-agg-values (when new-agg-states
                               (mapv (fn [agg-state agg-spec]
                                       ((:extract-fn agg-spec) agg-state))
                                     new-agg-states
                                     agg-specs))]

          ;; Emit deltas for the change
          (cond
            ;; New group appeared
            (and (nil? old-agg-values) (some? new-agg-values))
            [(delta/make-delta (if (= :all group-key)
                                 new-agg-values
                                 (vec (concat group-key new-agg-values)))
                               1)]

            ;; Group disappeared
            (and (some? old-agg-values) (nil? new-agg-values))
            (do
              ;; Clean up empty group
              (set! aggregates (dissoc aggregates group-key))
              [(delta/make-delta (if (= :all group-key)
                                   old-agg-values
                                   (vec (concat group-key old-agg-values)))
                                 -1)])

            ;; Group values changed
            (and (some? old-agg-values) (some? new-agg-values)
                 (not= old-agg-values new-agg-values))
            [(delta/make-delta (if (= :all group-key)
                                 old-agg-values
                                 (vec (concat group-key old-agg-values)))
                               -1)
             (delta/make-delta (if (= :all group-key)
                                 new-agg-values
                                 (vec (concat group-key new-agg-values)))
                               1)]

            ;; No change in aggregate values
            :else
            []))))))

(defn make-multi-aggregate
  "Create a multi-aggregate operator that combines multiple aggregates.

  group-fn: extract grouping key from binding
  agg-specs: vector of aggregate specifications, each with:
             {:value-fn ... :agg-fn ... :extract-fn ...}

  Example:
    (make-multi-aggregate
      first                           ; group by first element
      [{:value-fn (constantly nil)   ; count doesn't need value
        :agg-fn inc-count
        :extract-fn identity}
       {:value-fn second              ; sum second element
        :agg-fn inc-sum
        :extract-fn identity}])"
  [group-fn agg-specs]
  (MultiAggregateOperator.
   group-fn
   agg-specs
   {}))
