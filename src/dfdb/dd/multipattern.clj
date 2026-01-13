(ns dfdb.dd.multipattern
  "Multi-pattern incremental execution with joins."
  (:require [dfdb.dd.delta-simple :as delta]
            [dfdb.dd.simple-incremental :as simple]))

(defrecord IncrementalJoinOperator [left-state right-state join-vars]
  simple/DeltaOperator
  (process-delta [this delta]
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

      ;; Two or more patterns - incremental joins
       (>= (count patterns) 2)
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

      ;; More than 2 patterns - not yet supported
       :else
       nil))))
