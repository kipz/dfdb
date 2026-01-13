(ns dfdb.dd.full-pipeline
  "Complete DD pipeline for ALL Datalog query types - NO fallback."
  (:require [dfdb.dd.delta-simple :as delta]
            [dfdb.dd.simple-incremental :as simple]
            [dfdb.dd.multipattern :as mp]
            [dfdb.dd.aggregate :as agg]
            [dfdb.dd.multiset :as ms]
            [dfdb.dd.operator :as op]
            [dfdb.dd.recursive-incremental :as rec]
            [dfdb.query :as query]
            [dfdb.index :as index]))

(set! *warn-on-reflection* true)

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
      (let [resolved (map #(if (and (symbol? %) (.startsWith ^String (name %) "?"))
                             (get binding %)
                             %)
                          args)
            comparable (map #(if (instance? java.util.Date %)
                               (.getTime ^java.util.Date %)
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
       (let [;; Get all variables used in ALL aggregate expressions
             agg-vars (distinct (map second aggregates))
             all-vars (vec (concat group-vars agg-vars))

            ;; Build base WITHOUT final projection (keep full bindings)
            ;; We pass all-vars so the base knows what to keep
             base (mp/make-multi-pattern-pipeline pure-patterns all-vars)

             _ (when-not base
                 (throw (ex-info "Could not build pattern pipeline"
                                 {:query query-form})))

            ;; For aggregates, we work directly on result tuples:
            ;; The base pipeline returns tuples like [group-key... var1 var2 ...]
            ;; where positions match all-vars order

            ;; Extract grouping key from tuple
             group-fn (if (seq group-vars)
                       ;; First N elements are group vars
                        (fn [tuple]
                          (vec (take (count group-vars) tuple)))
                        (constantly :all))

            ;; Create aggregate operators for each aggregate expression
            ;; Each operates on the same grouping but different variables/aggregations
             agg-ops (vec
                      (for [[agg-fn agg-var] aggregates]
                        (let [;; Find position of this var in all-vars
                              var-idx (.indexOf ^java.util.List all-vars agg-var)
                              ;; Extract value for this specific variable
                              value-fn (fn [tuple] (nth tuple var-idx))
                              ;; Wrap agg-fn to extract values from tuples
                              tuple-agg-fn (case agg-fn
                                             count agg/agg-count
                                             sum (comp agg/agg-sum (partial map value-fn))
                                             avg (comp agg/agg-avg (partial map value-fn))
                                             min (comp agg/agg-min (partial map value-fn))
                                             max (comp agg/agg-max (partial map value-fn)))]
                          (agg/make-aggregate-operator group-fn tuple-agg-fn nil))))

             collect-agg (simple/->CollectResults {:accumulated (atom {})})

         ;; Helper to extract current aggregate results from operators
             extract-agg-results (fn []
                                   (let [first-agg-state @(:aggregates (:state (first agg-ops)))
                                         latest-timestamp (when (seq first-agg-state) (apply max (keys first-agg-state)))
                                         first-aggregates (when latest-timestamp (get first-agg-state latest-timestamp))]
                                     (when first-aggregates
                                       (set
                                        (for [group-key (keys first-aggregates)]
                                          (let [agg-values (vec
                                                            (for [agg-op agg-ops]
                                                              (let [state @(:aggregates (:state agg-op))
                                                                    aggs (get state latest-timestamp)]
                                                                (get aggs group-key))))]
                                            (if (= :all group-key)
                                              agg-values
                                              (vec (concat group-key agg-values)))))))))]

         {:process-deltas
          (fn [tx-deltas]
           ;; Get current aggregate results BEFORE processing
            (let [old-results (or (extract-agg-results) #{})]

             ;; Process through base
              ((:process-deltas base) tx-deltas)

             ;; Get intermediate results (tuples)
              (let [intermediate ((:get-results base))
                    ;; Convert to multiset
                    ms (ms/multiset (into {} (map (fn [v] [v 1]) intermediate)))]

               ;; Feed to ALL aggregate operators
                (doseq [agg-op agg-ops]
                  (op/input agg-op ms (System/currentTimeMillis)))

               ;; Get new aggregate results AFTER processing
                (let [new-results (or (extract-agg-results) #{})

                      ;; Compute differential
                      additions (clojure.set/difference new-results old-results)
                      retractions (clojure.set/difference old-results new-results)]

                 ;; Feed differential to collect operator
                  (doseq [result retractions]
                    (let [d (delta/make-delta result -1)]
                      (simple/process-delta collect-agg d)))

                  (doseq [result additions]
                    (let [d (delta/make-delta result 1)]
                      (simple/process-delta collect-agg d)))))))

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

;; =============================================================================
;; DD Pipeline State Initialization
;; =============================================================================

(defn initialize-pipeline-state
  "Initialize DD pipeline state with existing database contents.
  The DD pipeline starts with empty state and only learns from deltas.
  This function bootstraps the state by scanning the database and generating
  synthetic 'add' deltas for all existing data matching the query patterns."
  [dd-graph db query-form]
  (when dd-graph
    (let [parsed (query/parse-query query-form)
          patterns (filter pattern-clause? (:where parsed))
          storage (:storage db)]

      ;; For each pattern, scan database and generate initial deltas
      (doseq [pattern patterns]
        (let [[_e a _v] pattern]
          (when (keyword? a)  ; Only for concrete attributes
            ;; Scan all datoms for this attribute
            (let [datoms (index/scan-aevt storage [:aevt a] [:aevt (index/successor-value a)])
                  ;; Convert to synthetic transaction deltas
                  init-deltas (map (fn [[_k datom]]
                                     {:entity (:e datom)
                                      :attribute (:a datom)
                                      :old-value nil
                                      :new-value (:v datom)
                                      :operation :assert
                                      :time/system (:t datom)})
                                   (filter #(= :assert (:op (second %))) datoms))]

              ;; Feed through DD pipeline
              (when (seq init-deltas)
                ((:process-deltas dd-graph) init-deltas)))))))))
