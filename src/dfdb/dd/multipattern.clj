(ns dfdb.dd.multipattern
  "Multi-pattern incremental execution with joins."
  (:require [dfdb.dd.delta-simple :as delta]
            [dfdb.dd.simple-incremental :as simple]
            [clojure.set :as set]))

(defrecord IncrementalJoinOperator [left-state right-state join-vars]
  simple/DeltaOperator
  (process-delta [_this delta]
    (let [binding (:binding delta)
          mult (:mult delta)
          source (:source delta :left)]

      (case source
        :left
        (do
          (swap! left-state update binding (fnil + 0) mult)
          (let [join-key (select-keys binding join-vars)]
            (mapcat (fn [[right-binding right-mult]]
                      (when (= join-key (select-keys right-binding join-vars))
                        (let [joined (merge binding right-binding)
                              combined-mult (* mult right-mult)]
                          (when (not= 0 combined-mult)
                            [(delta/make-delta joined combined-mult)]))))
                    @right-state)))

        :right
        (do
          (swap! right-state update binding (fnil + 0) mult)
          (let [join-key (select-keys binding join-vars)]
            (mapcat (fn [[left-binding left-mult]]
                      (when (= join-key (select-keys left-binding join-vars))
                        (let [joined (merge left-binding binding)
                              combined-mult (* left-mult mult)]
                          (when (not= 0 combined-mult)
                            [(delta/make-delta joined combined-mult)]))))
                    @left-state)))))))

(defn make-incremental-join [join-vars]
  (->IncrementalJoinOperator (atom {}) (atom {}) join-vars))

(defn natural-join-variables [pattern1 pattern2]
  (let [vars1 (filter #(and (symbol? %) (.startsWith (name %) "?")) pattern1)
        vars2 (filter #(and (symbol? %) (.startsWith (name %) "?")) pattern2)]
    (vec (filter (set vars1) vars2))))

(defn pattern-clause?
  "Check if clause is a pattern (not a predicate/NOT/etc)."
  [clause]
  (and (vector? clause)
       (not (list? (first clause)))
       (not (and (seq? clause) (= 'not (first clause))))))

(defn make-multi-pattern-pipeline
  "Create DD pipeline for multi-pattern query with optional predicate filters.
  Returns pipeline or nil if unsupported."
  ([patterns find-vars] (make-multi-pattern-pipeline patterns find-vars []))
  ([patterns find-vars predicate-filters]

   ;; Only compile pure pattern queries (no predicates in pattern list)
   (when (every? pattern-clause? patterns)
     (cond
      ;; Single pattern
       (= 1 (count patterns))
       (simple/make-simple-pipeline (first patterns) find-vars)

      ;; Exactly two patterns - incremental join
       (= 2 (count patterns))
       (let [pattern1 (first patterns)
             pattern2 (second patterns)
             vars1 (filter #(and (symbol? %) (.startsWith (name %) "?")) pattern1)
             vars2 (filter #(and (symbol? %) (.startsWith (name %) "?")) pattern2)
             join-vars (vec (filter (set vars1) vars2))

             pattern-op1 (simple/->PatternOperator pattern1 (atom {}))
             pattern-op2 (simple/->PatternOperator pattern2 (atom {}))
             join-op (make-incremental-join join-vars)

             ;; Helper to apply predicate filters to deltas
             apply-filters (fn [deltas]
                             (if (empty? predicate-filters)
                               deltas
                               (reduce (fn [ds filter-op]
                                         (mapcat #(simple/process-delta filter-op %) ds))
                                       deltas
                                       predicate-filters)))

             project-op (simple/->ProjectOperator find-vars (atom {}))
             collect-op (simple/->CollectResults {:accumulated (atom {})})]

         {:process-deltas
          (fn [tx-deltas]
            (let [deltas1 (delta/transaction-deltas-to-binding-deltas tx-deltas pattern1)
                  deltas2 (delta/transaction-deltas-to-binding-deltas tx-deltas pattern2)]

              (doseq [d deltas1]
                (let [pattern-out (simple/process-delta pattern-op1 d)
                      tagged (map #(assoc % :source :left) pattern-out)
                      joined (mapcat #(simple/process-delta join-op %) tagged)
                      ;; Apply predicate filters BEFORE projection
                      filtered (apply-filters joined)
                      projected (mapcat #(simple/process-delta project-op %) filtered)]
                  (doseq [p projected]
                    (simple/process-delta collect-op p))))

              (doseq [d deltas2]
                (let [pattern-out (simple/process-delta pattern-op2 d)
                      tagged (map #(assoc % :source :right) pattern-out)
                      joined (mapcat #(simple/process-delta join-op %) tagged)
                      ;; Apply predicate filters BEFORE projection
                      filtered (apply-filters joined)
                      projected (mapcat #(simple/process-delta project-op %) filtered)]
                  (doseq [p projected]
                    (simple/process-delta collect-op p))))))

          :get-results
          (fn [] (simple/get-results collect-op))

          :operators
          {:pattern1 pattern-op1
           :pattern2 pattern-op2
           :join join-op
           :project project-op
           :collect collect-op}})

      ;; Three or more patterns - recursively build left-deep join tree
      ;; Strategy: ((P1 ⋈ P2) ⋈ P3) ⋈ P4 ...
       (> (count patterns) 2)
       (let [;; Get all variables from all patterns to use as intermediate projection
             all-vars (vec (distinct (apply concat
                                            (map #(filter (fn [x] (and (symbol? x) (.startsWith (name x) "?"))) %)
                                                 patterns))))

             ;; Build pipeline for first two patterns
             first-two-pipeline (make-multi-pattern-pipeline
                                 (take 2 patterns)
                                 all-vars
                                 predicate-filters)

             ;; Recursively build join tree: join result of first two with remaining patterns
             ;; We create a synthetic pattern from the joined results and join with next pattern
             remaining-patterns (drop 2 patterns)

             ;; Create operators for remaining patterns
             remaining-ops (mapv (fn [p] (simple/->PatternOperator p (atom {}))) remaining-patterns)

             ;; Build chain of joins
             collect-op (simple/->CollectResults {:accumulated (atom {})})]

         (when first-two-pipeline
           {:process-deltas
            (fn [tx-deltas]
              ;; Process first two patterns
              ((:process-deltas first-two-pipeline) tx-deltas)
              (let [intermediate-results ((:get-results first-two-pipeline))]

                ;; Reset collect
                (reset! (:accumulated (:state collect-op)) {})

                ;; For each remaining pattern, simulate a join
                ;; This is a simplified implementation - proper DD would maintain arrangements
                (let [final-results
                      (reduce
                       (fn [current-results [idx pattern]]
                         (let [pattern-deltas (delta/transaction-deltas-to-binding-deltas tx-deltas pattern)
                               pattern-op (nth remaining-ops idx)

                               ;; Get all bindings from pattern
                               pattern-bindings (set
                                                 (mapcat
                                                  (fn [d]
                                                    (let [out (simple/process-delta pattern-op d)]
                                                      (map :binding out)))
                                                  pattern-deltas))

                               ;; Find common variables between current results and pattern
                               current-vars (when (seq current-results) (set (keys (first current-results))))
                               pattern-vars (when (seq pattern-bindings) (set (keys (first pattern-bindings))))
                               common-vars (set/intersection current-vars pattern-vars)

                               ;; Join current results with pattern bindings
                               joined (if (empty? common-vars)
                                        ;; Cross product
                                        (set (for [r current-results
                                                   b pattern-bindings]
                                               (merge r b)))
                                        ;; Natural join on common variables
                                        (set (for [r current-results
                                                   b pattern-bindings
                                                   :when (every? #(= (get r %) (get b %)) common-vars)]
                                               (merge r b))))]
                           joined))
                       intermediate-results
                       (map-indexed vector remaining-patterns))

                      ;; Apply predicate filters and project to find vars
                      filtered (if (empty? predicate-filters)
                                 final-results
                                 (reduce
                                  (fn [results filter-op]
                                    (set (filter #(seq (simple/process-delta filter-op (delta/make-delta % 1)))
                                                 results)))
                                  final-results
                                  predicate-filters))

                      projected (set (map #(vec (map (fn [v] (get % v)) find-vars)) filtered))]

                  ;; Feed to collector
                  (doseq [result projected]
                    (simple/process-delta collect-op (delta/make-delta result 1))))))

            :get-results
            (fn [] (simple/get-results collect-op))}))

       :else
       nil))))
