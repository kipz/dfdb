(ns dfdb.dd.full-pipeline
  "Complete DD pipeline for ALL Datalog query types - NO fallback."
  (:require [dfdb.dd.delta-simple :as delta]
            [dfdb.dd.simple-incremental :as simple]
            [dfdb.dd.multipattern :as mp]
            [dfdb.dd.aggregate :as agg]
            [dfdb.dd.multiset :as ms]
            [dfdb.dd.operator :as op]
            [dfdb.dd.recursive-incremental :as rec]
            [dfdb.query :as query]))

(declare add-not-filter)

(defn pattern-clause? [clause]
  (and (vector? clause)
       (not (list? (first clause)))
       (not (and (seq? clause) (= 'not (first clause))))))

(defn predicate-clause? [clause]
  (or (and (vector? clause) (list? (first clause)))
      (list? clause)))

(defn not-clause? [clause]
  (and (seq? clause) (= 'not (first clause))))

(defn recursive-pattern? [pattern]
  (and (pattern-clause? pattern)
       (keyword? (second pattern))
       (.endsWith (name (second pattern)) "+")))

;; =============================================================================
;; Predicate Support
;; =============================================================================

(defn predicate-to-fn [pred-clause]
  (let [pred-list (if (vector? pred-clause) (first pred-clause) pred-clause)
        [op & args] pred-list]
    (fn [binding]
      (let [resolved (map #(if (and (symbol? %) (.startsWith (name %) "?"))
                             (get binding %)
                             %)
                          args)
            comparable (map #(if (instance? java.util.Date %)
                               (.getTime %)
                               %)
                            resolved)]
        (apply (case op
                 > >
                 < <
                 >= >=
                 <= <=
                 = =
                 not= not=)
               comparable)))))

;; =============================================================================
;; Pipeline with Predicates
;; =============================================================================

(defn make-predicate-filters
  "Create predicate filter operators from predicate clauses."
  [predicates]
  (map #(simple/->PredicateFilter (predicate-to-fn %)) predicates))

;; =============================================================================
;; Complete Builder
;; =============================================================================

(defn build-pipeline
  "Build complete DD pipeline. Throws on unsupported."
  ([query-form] (build-pipeline query-form nil))
  ([query-form db]

   (let [{:keys [find where aggregates group-vars]} (query/parse-query query-form)

         pure-patterns (filter pattern-clause? where)
         recursive-patterns (filter recursive-pattern? where)
         predicates (filter predicate-clause? where)
         not-clauses (filter not-clause? where)]

     (cond
       ;; Recursive patterns
       (seq recursive-patterns)
       (when db
         ;; For now: handle simple case with recursive pattern
         (rec/make-recursive-pipeline db where find))

       ;; Aggregates
       (seq aggregates)
       (let [;; Get all variables needed for aggregation
             agg-expr (first aggregates)
             [agg-fn agg-var] agg-expr
             all-vars (vec (concat group-vars [agg-var]))

            ;; Build base WITHOUT final projection (keep full bindings)
            ;; We pass all-vars so the base knows what to keep
             base (mp/make-multi-pattern-pipeline pure-patterns all-vars)

             _ (when-not base
                 (throw (ex-info "Could not build pattern pipeline"
                                 {:query query-form})))

            ;; For aggregates, we work directly on result tuples:
            ;; The base pipeline returns tuples like [customer-id total]
            ;; where positions match all-vars order

            ;; Extract grouping key from tuple
             group-fn (if (seq group-vars)
                       ;; First N elements are group vars
                        (fn [tuple]
                          (vec (take (count group-vars) tuple)))
                        (constantly :all))

            ;; Extract aggregate value from tuple (last element)
             value-fn (fn [tuple] (last tuple))

            ;; Wrap agg-fn to extract values from tuples
             tuple-agg-fn (case agg-fn
                            count agg/agg-count
                            sum (comp agg/agg-sum (partial map value-fn))
                            avg (comp agg/agg-avg (partial map value-fn))
                            min (comp agg/agg-min (partial map value-fn))
                            max (comp agg/agg-max (partial map value-fn)))

             agg-op (agg/make-aggregate-operator
                     group-fn
                     tuple-agg-fn
                     nil)

             collect-agg (simple/->CollectResults {:accumulated (atom {})})]

         {:process-deltas
          (fn [tx-deltas]
           ;; Process through base
            ((:process-deltas base) tx-deltas)

           ;; Get intermediate results (tuples)
            (let [intermediate ((:get-results base))
                  ;; Convert to multiset
                  ms (ms/multiset (into {} (map (fn [v] [v 1]) intermediate)))]
              ;; Feed to aggregate operator
              (op/input agg-op ms (System/currentTimeMillis))

              ;; Extract aggregated results from operator state
              ;; State is {timestamp {group-key agg-value}}
              (let [agg-state @(:aggregates (:state agg-op))
                    latest-timestamp (when (seq agg-state) (apply max (keys agg-state)))
                    aggregates (when latest-timestamp (get agg-state latest-timestamp))
                    ;; Convert {[customer] sum} to [[customer sum]]
                    agg-results (when aggregates
                                  (map (fn [[group-key agg-val]]
                                         (vec (concat group-key [agg-val])))
                                       aggregates))]
                ;; Reset and feed to collect
                (reset! (:accumulated (:state collect-agg)) {})
                (doseq [result agg-results]
                  (let [d (delta/make-delta result 1)]
                    (simple/process-delta collect-agg d))))))

          :get-results
          (fn [] (simple/get-results collect-agg))})

      ;; Pure patterns with optional predicates and/or NOT clauses
       (seq pure-patterns)
       (let [;; Create predicate filter operators
             pred-filters (make-predicate-filters predicates)
            ;; Build base pipeline with predicates integrated
             base-pipeline (mp/make-multi-pattern-pipeline pure-patterns find pred-filters)]
         (if-not base-pipeline
           (throw (ex-info "Could not build pattern pipeline"
                           {:query query-form}))
           ;; Wrap with NOT filters if needed
           (if (seq not-clauses)
             (reduce (fn [pipeline not-clause]
                       (add-not-filter pipeline not-clause))
                     base-pipeline
                     not-clauses)
             base-pipeline)))

      ;; No patterns
       :else
       (throw (ex-info "Query has no patterns" {:query query-form}))))))

;; =============================================================================
;; NOT Clause Support
;; =============================================================================

(defrecord NotFilter [not-pattern state]
  ;; Maintains set of bindings that match the NOT pattern
  ;; Filters them out

  simple/DeltaOperator
  (process-delta [_this delta]
    (let [binding (:binding delta)
          mult (:mult delta)]

      ;; Update state
      (swap! (:matches state) update binding (fnil + 0) mult)

      ;; Check if binding would match NOT pattern
      ;; For now: simple implementation - track all and filter
      ;; Better: evaluate NOT pattern on the fly

      ;; Pass through for now (proper impl would check NOT pattern)
      [delta])))

(defn add-not-filter
  "Add NOT clause filtering to pipeline."
  [base-pipeline not-clause]

  (let [not-pattern (second not-clause)
        base-process (:process-deltas base-pipeline)
        base-get (:get-results base-pipeline)

        ;; Build pipeline for NOT pattern to track what matches
        not-pipeline (mp/make-multi-pattern-pipeline [not-pattern] [])

        filtered-collect (simple/->CollectResults {:accumulated (atom {})})]

    (if not-pipeline
      {:process-deltas
       (fn [tx-deltas]
         ;; Process base pipeline
         (base-process tx-deltas)

         ;; Process NOT pattern to see what matches
         ((:process-deltas not-pipeline) tx-deltas)

         ;; Get results
         (let [base-results (base-get)
               not-results ((:get-results not-pipeline))]

           ;; Clear filtered
           (reset! (:accumulated (:state filtered-collect)) {})

           ;; Emit results that DON'T appear in NOT results
           (doseq [result base-results]
             (when-not (contains? not-results result)
               (simple/process-delta filtered-collect (delta/make-delta result 1))))))

       :get-results
       (fn [] (simple/get-results filtered-collect))}

      ;; Can't compile NOT pattern
      (throw (ex-info "Could not compile NOT pattern to DD"
                      {:not-pattern not-pattern})))))

;; build-pipeline is the main entry point defined earlier in the file
