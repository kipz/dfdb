(ns dfdb.dd.full-pipeline
  "Complete DD pipeline for ALL Datalog query types - NO fallback."
  (:require [clojure.set]
            [dfdb.dd.delta-core :as delta]
            [dfdb.dd.incremental-core :as core]
            [dfdb.dd.multipattern :as mp]
            [dfdb.dd.incremental-aggregate :as inc-agg]
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
  (map #(core/->PredicateFilter (predicate-to-fn %)) predicates))

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
       ;; Recursive patterns WITH aggregates - two-phase execution
       (and (seq recursive-patterns) (seq aggregates))
       (when db
         ;; Phase 1: Compute recursive closure (returns all matching paths)
         ;; Phase 2: Apply aggregates to closure results
         ;; This is NOT fully incremental but it works correctly
         (let [;; For recursive+aggregate, we need to determine what variables are in the results
               ;; The recursive pipeline returns ALL variables from ALL patterns
               all-pattern-vars (distinct (mapcat (fn [pattern]
                                                    (filter #(and (symbol? %) (.startsWith ^String (name %) "?")) pattern))
                                                  where))

               recursive-pipeline (rec/make-recursive-pipeline db where all-pattern-vars)

               ;; Variables used in aggregates
               agg-vars (distinct (map (fn [agg-expr] (last agg-expr)) aggregates))

               ;; all-vars for recursive results = all pattern variables
               all-vars all-pattern-vars
               result-vars (vec (concat group-vars agg-vars))

               group-fn-delta (if (seq group-vars)
                                (fn [binding]
                                  (vec (take (count group-vars) binding)))
                                (constantly :all))

               ;; Build aggregate specs
               agg-specs (vec
                          (for [[agg-fn agg-var] aggregates]
                            (let [var-idx (.indexOf ^java.util.List all-vars agg-var)
                                  value-fn-delta (fn [binding]
                                                   (nth binding var-idx))
                                  agg-fn-name (if (list? agg-fn) (first agg-fn) agg-fn)
                                  agg-fn-args (when (list? agg-fn) (rest agg-fn))]
                              (case agg-fn-name
                                count {:value-fn (constantly nil) :agg-fn inc-agg/inc-count :extract-fn identity}
                                sum {:value-fn value-fn-delta :agg-fn inc-agg/inc-sum :extract-fn identity}
                                avg {:value-fn value-fn-delta :agg-fn inc-agg/inc-avg :extract-fn :avg}
                                min {:value-fn value-fn-delta :agg-fn inc-agg/inc-min :extract-fn :min}
                                max {:value-fn value-fn-delta :agg-fn inc-agg/inc-max :extract-fn :max}
                                count-distinct {:value-fn value-fn-delta :agg-fn inc-agg/inc-count-distinct :extract-fn :result}
                                variance {:value-fn value-fn-delta :agg-fn inc-agg/inc-variance :extract-fn :result}
                                stddev {:value-fn value-fn-delta :agg-fn inc-agg/inc-stddev :extract-fn :result}
                                median {:value-fn value-fn-delta :agg-fn inc-agg/inc-median :extract-fn :result}
                                collect {:value-fn value-fn-delta :agg-fn inc-agg/inc-collect :extract-fn :result}
                                sample (let [k (first agg-fn-args)]
                                         {:value-fn value-fn-delta
                                          :agg-fn (fn [state value mult]
                                                    (inc-agg/inc-sample state value mult k))
                                          :extract-fn :result})
                                rand {:value-fn value-fn-delta :agg-fn inc-agg/inc-rand :extract-fn :result}))))

               agg-op (inc-agg/make-multi-aggregate group-fn-delta agg-specs)
               collect-agg (core/->CollectResults {:accumulated (atom {})})]

           ;; Return combined pipeline
           ;; NOTE: This recomputes aggregates from scratch each time
           ;; Not fully incremental but ensures correctness for recursive+aggregate
           {:process-deltas
            (fn [tx-deltas]
              ;; Phase 1: Process through recursive pipeline
              ((:process-deltas recursive-pipeline) tx-deltas)

              ;; Phase 2: Recompute ALL aggregates from scratch
              ;; Query WITHOUT aggregates to get ALL current raw results
              (let [raw-query (vec (concat [:find] all-pattern-vars [:where] where))
                    raw-results (query/query db raw-query)]

                ;; Create fresh aggregate operators
                (let [fresh-agg-op (inc-agg/make-multi-aggregate group-fn-delta agg-specs)
                      fresh-collect (core/->CollectResults {:accumulated (atom {})})]

                  ;; Feed ALL raw results through fresh aggregates
                  (doseq [result raw-results]
                    (let [result-delta (delta/make-delta result 1)
                          agg-deltas (core/process-delta fresh-agg-op result-delta)]
                      (doseq [agg-delta agg-deltas]
                        (core/process-delta fresh-collect agg-delta))))

                  ;; Replace collect-agg state with fresh results
                  (reset! (:accumulated (:state collect-agg))
                          @(:accumulated (:state fresh-collect))))))

            :get-results
            (fn [] (core/get-results collect-agg))

            :recursive-pipeline recursive-pipeline
            :agg-op agg-op
            :all-pattern-vars all-pattern-vars
            :where where
            :db db
            :group-fn-delta group-fn-delta
            :agg-specs agg-specs
            :operators {:collect collect-agg}}))

       ;; Recursive patterns WITHOUT aggregates
       (seq recursive-patterns)
       (when db
         (rec/make-recursive-pipeline db where find))

       ;; Aggregates WITHOUT recursive patterns
       (seq aggregates)
       (let [;; Get all variables used in ALL aggregate expressions
             agg-vars (distinct (map second aggregates))
             ;; Get ALL variables from patterns (for preventing deduplication)
             all-pattern-vars (distinct (mapcat (fn [pattern]
                                                  (filter #(and (symbol? %) (.startsWith (name %) "?")) pattern))
                                                pure-patterns))
             ;; Variables for final results (group + agg)
             result-vars (vec (concat group-vars agg-vars))
             ;; For pipeline, use result-vars
             all-vars result-vars

            ;; Create predicate filters to apply during incremental updates
             pred-filters (make-predicate-filters predicates)

            ;; Build base WITH predicate filters (keep full bindings)
            ;; We pass all-vars so the base knows what to keep
             base (mp/make-multi-pattern-pipeline pure-patterns all-vars pred-filters)

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

            ;; Create ONE MULTI-AGGREGATE operator that combines all aggregates
            ;; Extract grouping key from binding (tuple)
             group-fn-delta (if (seq group-vars)
                             ;; First N elements are group vars
                              (fn [binding]
                                (vec (take (count group-vars) binding)))
                              (constantly :all))

            ;; Build agg-specs for multi-aggregate operator
             agg-specs (vec
                        (for [[agg-fn agg-var] aggregates]
                          (let [;; Find position of this var in all-vars
                                var-idx (.indexOf ^java.util.List all-vars agg-var)
                               ;; Extract value for this specific variable from binding
                                value-fn-delta (fn [binding] (nth binding var-idx))]

                           ;; Create aggregate spec based on type
                            ;; Handle aggregate functions with parameters (like sample)
                            (let [agg-fn-name (if (list? agg-fn) (first agg-fn) agg-fn)
                                  agg-fn-args (when (list? agg-fn) (rest agg-fn))]
                              (case agg-fn-name
                                count {:value-fn (constantly nil)
                                       :agg-fn inc-agg/inc-count
                                       :extract-fn identity}
                                sum {:value-fn value-fn-delta
                                     :agg-fn inc-agg/inc-sum
                                     :extract-fn identity}
                                avg {:value-fn value-fn-delta
                                     :agg-fn inc-agg/inc-avg
                                     :extract-fn :avg}
                                min {:value-fn value-fn-delta
                                     :agg-fn inc-agg/inc-min
                                     :extract-fn :min}
                                max {:value-fn value-fn-delta
                                     :agg-fn inc-agg/inc-max
                                     :extract-fn :max}
                                ;; Advanced aggregates
                                count-distinct {:value-fn value-fn-delta
                                                :agg-fn inc-agg/inc-count-distinct
                                                :extract-fn :result}
                                variance {:value-fn value-fn-delta
                                          :agg-fn inc-agg/inc-variance
                                          :extract-fn :result}
                                stddev {:value-fn value-fn-delta
                                        :agg-fn inc-agg/inc-stddev
                                        :extract-fn :result}
                                median {:value-fn value-fn-delta
                                        :agg-fn inc-agg/inc-median
                                        :extract-fn :result}
                                collect {:value-fn value-fn-delta
                                         :agg-fn inc-agg/inc-collect
                                         :extract-fn :result}
                                sample (let [k (first agg-fn-args)]
                                         {:value-fn value-fn-delta
                                          :agg-fn (fn [state value mult]
                                                    (inc-agg/inc-sample state value mult k))
                                          :extract-fn :result})
                                rand {:value-fn value-fn-delta
                                      :agg-fn inc-agg/inc-rand
                                      :extract-fn :result})))))

            ;; Create the multi-aggregate operator
             agg-op (inc-agg/make-multi-aggregate group-fn-delta agg-specs)

             collect-agg (core/->CollectResults {:accumulated (atom {})})

         ;; Helper to extract current aggregate results from collect operator
         ;; With incremental aggregates, results are stored in collect-agg
             extract-agg-results (fn []
                                   (core/get-results collect-agg))]

         {:process-deltas
          (fn [tx-deltas]
            ;; NEW DELTA-BASED APPROACH:
            ;; Work with MULTISETS (with multiplicities) to handle duplicate tuples correctly
            ;; Critical for aggregates where [1 100000] can appear multiple times

            ;; Get old accumulated multiset BEFORE processing
            (let [base-ops (:operators base)
                  base-collect (:collect base-ops)
                  old-accumulated (or (when base-collect @(:accumulated (:state base-collect))) {})]

              ;; Process through base pipeline
              ((:process-deltas base) tx-deltas)

              ;; Get new accumulated multiset AFTER processing
              (let [new-accumulated (or (when base-collect @(:accumulated (:state base-collect))) {})

                    ;; Compute differential at multiset level
                    ;; For each binding, check if multiplicity changed
                    all-bindings (set (concat (keys old-accumulated) (keys new-accumulated)))

                    deltas (for [binding all-bindings
                                 :let [old-mult (get old-accumulated binding 0)
                                       new-mult (get new-accumulated binding 0)
                                       delta-mult (- new-mult old-mult)]
                                 :when (not= delta-mult 0)]
                             {:binding binding :mult delta-mult})]

                ;; Debug
                (when (and (System/getProperty "dfdb.debug.agg") (seq deltas))
                  (println "DELTAS:" (count deltas) "first few:" (take 3 deltas)))

                ;; Process each delta through aggregate operator
                (doseq [delta deltas]
                  (let [agg-deltas (core/process-delta agg-op delta)]
                    ;; Feed aggregate deltas to collect
                    (doseq [agg-delta agg-deltas]
                      (core/process-delta collect-agg agg-delta)))))))

          :get-results
          (fn [] (core/get-results collect-agg))

          :base base
          :all-vars all-vars
          :result-vars result-vars
          :all-pattern-vars all-pattern-vars
          :agg-op agg-op
          :group-vars group-vars
          :extract-agg-results extract-agg-results
          :operators {:collect collect-agg}})

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

        filtered-collect (core/->CollectResults {:accumulated (atom {})})]

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
               (core/process-delta filtered-collect (delta/make-delta result 1))))))

       :get-results
       (fn [] (core/get-results filtered-collect))}

      ;; Can't compile NOT pattern
      (throw (ex-info "Could not compile NOT pattern to DD"
                      {:not-pattern not-pattern})))))

;; build-pipeline is the main entry point defined earlier in the file

;; =============================================================================
;; DD Pipeline State Initialization
;; =============================================================================

(defn- compute-join-result
  "Compute the actual join result of two sets of bindings.
  Uses hash join logic matching the query engine's join-bindings function."
  [left-bindings right-bindings]
  (if (or (empty? left-bindings) (empty? right-bindings))
    #{}
    (let [common-vars (clojure.set/intersection
                       (set (keys (first left-bindings)))
                       (set (keys (first right-bindings))))]
      (if (empty? common-vars)
        ;; No common variables - Cartesian product
        (set (for [b1 left-bindings b2 right-bindings]
               (merge b1 b2)))
        ;; Hash join: build hash table from smaller side
        (let [[build-side probe-side] (if (<= (count left-bindings) (count right-bindings))
                                        [left-bindings right-bindings]
                                        [right-bindings left-bindings])
              ;; Build phase: create hash table join-key -> [bindings]
              hash-table (reduce (fn [ht binding]
                                   (let [join-key (select-keys binding common-vars)
                                         existing (get ht join-key [])]
                                     (assoc ht join-key (conj existing binding))))
                                 {}
                                 build-side)]
          ;; Probe phase: look up each probe-side binding and merge matches
          (set (mapcat (fn [probe-binding]
                         (let [join-key (select-keys probe-binding common-vars)]
                           (when-let [matching-bindings (get hash-table join-key)]
                             (map #(merge % probe-binding) matching-bindings))))
                       probe-side)))))))

(defn initialize-pipeline-state
  "Initialize DD pipeline state with existing database contents.
  Uses naive query execution to compute initial results, then directly
  populates the pipeline's final operator state. This avoids the expensive
  process of scanning the entire database and feeding it through the delta pipeline."
  [dd-graph db query-form]
  (when dd-graph
    ;; Run the query naively to get current results
    (let [initial-results (query/query db query-form)
          operators (:operators dd-graph)
          parsed (query/parse-query query-form)
          where-clauses (:where parsed)
          patterns (filter pattern-clause? where-clauses)
          recursive-patterns (filter recursive-pattern? where-clauses)
          predicates (filter predicate-clause? where-clauses)
          has-aggregates? (seq (:aggregates parsed))]

      ;; Special case: Recursive + Aggregate
      ;; Query WITHOUT aggregates to get raw results, then feed through aggregates
      (when (and (seq recursive-patterns) has-aggregates?)
        (when-let [all-pattern-vars (:all-pattern-vars dd-graph)]
          (when-let [agg-op (:agg-op dd-graph)]
            (when-let [collect-agg (get-in dd-graph [:operators :collect])]
              ;; Build query without aggregates - just the where clause with all variables
              (let [raw-query (vec (concat [:find] all-pattern-vars [:where] where-clauses))
                    raw-results (query/query db raw-query)]
                ;; Feed raw results through aggregate operator
                (doseq [result raw-results]
                  (let [result-delta (delta/make-delta result 1)
                        agg-deltas (core/process-delta agg-op result-delta)]
                    (doseq [agg-delta agg-deltas]
                      (core/process-delta collect-agg agg-delta)))))))))

      ;; Populate join operator states for multi-pattern queries
      ;; This ensures incremental updates can join against existing state
      (when (and (> (count patterns) 1) operators)
        (try
          (cond
            ;; 2-pattern join: populate pattern operators and join operator
            (= (count patterns) 2)
            (let [pattern1 (first patterns)
                  pattern2 (second patterns)
                  pattern1-vars (vec (filter #(and (symbol? %) (.startsWith ^String (name %) "?")) pattern1))
                  pattern2-vars (vec (filter #(and (symbol? %) (.startsWith ^String (name %) "?")) pattern2))]

              ;; Only populate if we have variables and a join operator
              (when (and (seq pattern1-vars) (seq pattern2-vars) (:join operators))
                ;; Query each pattern separately to get all current matches
                (let [pattern1-query (vec (concat [:find] pattern1-vars [:where pattern1]))
                      pattern2-query (vec (concat [:find] pattern2-vars [:where pattern2]))
                      pattern1-bindings (try (set (query/query db pattern1-query)) (catch Exception _e #{}))
                      pattern2-bindings (try (set (query/query db pattern2-query)) (catch Exception _e #{}))]

                  ;; Convert result tuples back to binding maps
                  (when (and (seq pattern1-bindings) (seq pattern2-bindings))
                    (let [pattern1-maps (set (map (fn [tuple]
                                                    (if (vector? tuple)
                                                      (zipmap pattern1-vars tuple)
                                                      {(first pattern1-vars) tuple}))
                                                  pattern1-bindings))
                          pattern2-maps (set (map (fn [tuple]
                                                    (if (vector? tuple)
                                                      (zipmap pattern2-vars tuple)
                                                      {(first pattern2-vars) tuple}))
                                                  pattern2-bindings))
                          join-op (:join operators)
                          left-state (:left-state join-op)
                          right-state (:right-state join-op)]
                      (reset! left-state (into {} (map (fn [binding] [binding 1]) pattern1-maps)))
                      (reset! right-state (into {} (map (fn [binding] [binding 1]) pattern2-maps))))))))

            ;; 3+ pattern join: initialize pattern operators and join operators
            (> (count patterns) 2)
            (let [pattern-ops (:patterns operators)
                  join-ops (:joins operators)]
              (when (and pattern-ops join-ops)
                ;; Query each pattern to get current bindings
                (let [pattern-bindings
                      (vec
                       (for [pattern patterns]
                         (let [pattern-vars (vec (filter #(and (symbol? %)
                                                               (.startsWith ^String (name %) "?"))
                                                         pattern))
                               pattern-query (vec (concat [:find] pattern-vars [:where pattern]))
                               bindings (try (set (query/query db pattern-query))
                                             (catch Exception _e #{}))]
                           (set (map (fn [tuple]
                                       (if (vector? tuple)
                                         (zipmap pattern-vars tuple)
                                         {(first pattern-vars) tuple}))
                                     bindings)))))]

                  ;; Initialize pattern operator states
                  (doseq [[pattern-op bindings] (map vector pattern-ops pattern-bindings)]
                    (when-let [state (:state pattern-op)]
                      (reset! state (into {} (map (fn [binding] [binding 1]) bindings)))))

                  ;; Initialize join operator states progressively
                  ;; First join: pattern1 ⋈ pattern2
                  (when-let [join0 (first join-ops)]
                    (reset! (:left-state join0)
                            (into {} (map (fn [b] [b 1]) (nth pattern-bindings 0))))
                    (reset! (:right-state join0)
                            (into {} (map (fn [b] [b 1]) (nth pattern-bindings 1)))))

                  ;; For subsequent joins, left side is previous join result, right is next pattern
                  ;; We approximate by using pattern results (full join computation would be expensive)
                  (loop [i 1
                         prev-join-result (compute-join-result (nth pattern-bindings 0)
                                                               (nth pattern-bindings 1))]
                    (when (< i (count join-ops))
                      (when-let [join-op (nth join-ops i)]
                        ;; Left state: previous join's result
                        (reset! (:left-state join-op)
                                (into {} (map (fn [b] [b 1]) prev-join-result)))
                        ;; Right state: next pattern
                        (reset! (:right-state join-op)
                                (into {} (map (fn [b] [b 1]) (nth pattern-bindings (inc i)))))
                        ;; Compute this join's result for next iteration
                        (let [next-join-result (compute-join-result prev-join-result
                                                                    (nth pattern-bindings (inc i)))]
                          (recur (inc i) next-join-result))))))))

            :else nil)
          (catch Exception e
            ;; If join initialization fails, log and continue with just CollectResults
            (println "Warning: Failed to initialize join operator state:" (.getMessage ^Exception e)))))

      ;; For aggregate queries, initialize the base pipeline's CollectResults
      ;; with raw tuples (before aggregation) AND feed them through aggregate operators
      (when has-aggregates?
        (when-let [base (:base dd-graph)]
          (when-let [all-vars (:all-vars dd-graph)]
            (when-let [base-ops (:operators base)]
              (when-let [base-collect (:collect base-ops)]
                (try
                  ;; Initialize join operators in the base pipeline if this is a multi-pattern aggregate
                  (when (> (count patterns) 1)
                    (let [pattern-ops (:patterns base-ops)
                          join-ops (:joins base-ops)]
                      (when (and pattern-ops join-ops)
                        (let [pattern-bindings
                              (vec
                               (for [pattern patterns]
                                 (let [pattern-vars (vec (filter #(and (symbol? %)
                                                                       (.startsWith ^String (name %) "?"))
                                                                 pattern))
                                       pattern-query (vec (concat [:find] pattern-vars [:where pattern]))
                                       bindings (try (set (query/query db pattern-query))
                                                     (catch Exception _e #{}))]
                                   (set (map (fn [tuple]
                                               (if (vector? tuple)
                                                 (zipmap pattern-vars tuple)
                                                 {(first pattern-vars) tuple}))
                                             bindings)))))]
                          (doseq [[pattern-op bindings] (map vector pattern-ops pattern-bindings)]
                            (when-let [state (:state pattern-op)]
                              (reset! state (into {} (map (fn [binding] [binding 1]) bindings)))))
                          (when-let [join1 (first join-ops)]
                            (reset! (:left-state join1)
                                    (into {} (map (fn [b] [b 1]) (nth pattern-bindings 0))))
                            (reset! (:right-state join1)
                                    (into {} (map (fn [b] [b 1]) (nth pattern-bindings 1)))))
                          (loop [i 1
                                 prev-join-result (compute-join-result (nth pattern-bindings 0)
                                                                       (nth pattern-bindings 1))]
                            (when (< i (count join-ops))
                              (when-let [join-op (nth join-ops i)]
                                ;; Left state: previous join's result
                                (reset! (:left-state join-op)
                                        (into {} (map (fn [b] [b 1]) prev-join-result)))
                                ;; Right state: next pattern
                                (reset! (:right-state join-op)
                                        (into {} (map (fn [b] [b 1]) (nth pattern-bindings (inc i)))))
                                ;; Compute this join's result for next iteration
                                (let [next-join-result (compute-join-result prev-join-result
                                                                            (nth pattern-bindings (inc i)))]
                                  (recur (inc i) next-join-result)))))))))

                  ;; Query the patterns without aggregation to get raw tuples
                  ;; CRITICAL: Query with ALL pattern vars to prevent deduplication!
                  (let [all-pattern-vars (get dd-graph :all-pattern-vars)
                        result-vars (get dd-graph :result-vars)
                        base-where (vec (concat patterns predicates))
                        base-query-all (vec (concat [:find] all-pattern-vars [:where] base-where))
                        raw-tuples-all-vars (query/query db base-query-all)
                        ;; Project to result-vars and count duplicates
                        var-to-idx (into {} (map-indexed (fn [idx v] [v idx]) all-pattern-vars))
                        result-indices (mapv var-to-idx result-vars)
                        raw-tuples (map (fn [tuple]
                                          (mapv #(nth tuple %) result-indices))
                                        raw-tuples-all-vars)
                        base-accumulated-atom (:accumulated (:state base-collect))
                        raw-tuples-map (frequencies raw-tuples)]
                    (reset! base-accumulated-atom raw-tuples-map)

                    ;; Feed raw tuples through aggregate operator as DELTAS to initialize it
                    (when-let [agg-op (get-in dd-graph [:agg-op])]
                      ;; Convert raw tuples to deltas and feed through multi-aggregate operator
                      (doseq [[binding mult] raw-tuples-map]
                        (when (pos? mult)  ; Only positive multiplicities for initialization
                          (let [base-delta (delta/make-delta binding mult)
                                agg-deltas (core/process-delta agg-op base-delta)]
                            ;; Feed aggregate deltas to collect operator
                            (when-let [collect-agg (get-in dd-graph [:operators :collect])]
                              (doseq [agg-delta agg-deltas]
                                (core/process-delta collect-agg agg-delta))))))))

                    ;; Aggregate operators now initialized
                  (catch Exception e
                    (println "Warning: Failed to initialize base pipeline for aggregates:" (.getMessage ^Exception e)))))))))

      ;; Populate the final CollectResults operator with initial results
      ;; Each result gets multiplicity 1 (present in current state)
      ;; EXCEPT for aggregate queries which handle this specially above
      (when-not has-aggregates?
        (when-let [collect-op (:collect operators)]
          (let [accumulated (:accumulated (:state collect-op))]
            (reset! accumulated
                    (into {} (map (fn [result] [result 1]) initial-results)))))))))
