(ns dfdb.dd.multipattern
  "Multi-pattern incremental execution with joins."
  (:require [dfdb.dd.delta-simple :as delta]
            [dfdb.dd.simple-incremental :as simple]
            [clojure.set :as set]))

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

      ;; Exactly two patterns - simple join
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
                      filtered (apply-filters joined)
                      projected (mapcat #(simple/process-delta project-op %) filtered)]
                  (doseq [p projected]
                    (simple/process-delta collect-op p))))

              (doseq [d deltas2]
                (let [pattern-out (simple/process-delta pattern-op2 d)
                      tagged (map #(assoc % :source :right) pattern-out)
                      joined (mapcat #(simple/process-delta join-op %) tagged)
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

      ;; Three+ patterns - chain multiple joins
       (> (count patterns) 2)
       (let [;; Build chain: (P1 ⋈ P2) ⋈ P3 ⋈ P4 ...
             pattern-ops (mapv (fn [p] (simple/->PatternOperator p (atom {}))) patterns)

            ;; Create join operators for each step
            ;; Join-0: P1 ⋈ P2 on common(P1, P2)
            ;; Join-1: Result-0 ⋈ P3 on common(Result-0, P3)
            ;; Join-2: Result-1 ⋈ P4 on common(Result-1, P4)
             join-ops (vec (for [i (range 1 (count patterns))]
                             (if (= i 1)
                               ;; First join: P1 ⋈ P2
                               (let [p1-vars (set (filter #(and (symbol? %) (.startsWith (name %) "?")) (nth patterns 0)))
                                     p2-vars (set (filter #(and (symbol? %) (.startsWith (name %) "?")) (nth patterns 1)))
                                     join-vars (vec (set/intersection p1-vars p2-vars))]
                                 (make-incremental-join join-vars))
                               ;; Later joins: Accumulated ⋈ Pi
                               (let [left-vars (set (mapcat #(filter (fn [x] (and (symbol? x) (.startsWith (name x) "?"))) %)
                                                            (take i patterns)))
                                     right-vars (set (filter #(and (symbol? %) (.startsWith (name %) "?"))
                                                             (nth patterns i)))
                                     join-vars (vec (set/intersection left-vars right-vars))]
                                 (make-incremental-join join-vars)))))

            ;; Helper to apply filters
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
           ;; Process each pattern's deltas through the join chain
            (doseq [[idx pattern] (map-indexed vector patterns)]
              (let [pattern-deltas (delta/transaction-deltas-to-binding-deltas tx-deltas pattern)
                    pattern-op (nth pattern-ops idx)]

                (doseq [d pattern-deltas]
                  (let [pattern-out (simple/process-delta pattern-op d)]

                    (if (zero? idx)
                     ;; First pattern - feed to first join's left side
                      (let [tagged (map #(assoc % :source :left) pattern-out)
                            joined (mapcat #(simple/process-delta (first join-ops) %) tagged)]
                       ;; Chain through remaining joins on LEFT side (accumulated results)
                        (let [final-deltas
                              (reduce (fn [deltas join-idx]
                                        (if (< join-idx (count join-ops))
                                          (let [join-op (nth join-ops join-idx)
                                                ;; Tag as LEFT - this is the accumulated result
                                                tagged (map #(assoc % :source :left) deltas)]
                                            (mapcat #(simple/process-delta join-op %) tagged))
                                          deltas))
                                      joined
                                      (range 1 (count join-ops)))
                              filtered (apply-filters final-deltas)
                              projected (mapcat #(simple/process-delta project-op %) filtered)]
                          (doseq [p projected]
                            (simple/process-delta collect-op p))))

                     ;; Later patterns - feed to corresponding join's right side
                      (let [join-idx (dec idx)  ; Pattern 2 goes to join 0's right, Pattern 3 goes to join 1's right, etc.
                            tagged (map #(assoc % :source :right) pattern-out)
                            joined (mapcat #(simple/process-delta (nth join-ops join-idx) %) tagged)]
                       ;; Chain output through NEXT joins on LEFT side (this is now accumulated)
                        (let [final-deltas
                              (reduce (fn [deltas ji]
                                        (if (and deltas (< ji (count join-ops)))
                                          (let [join-op (nth join-ops ji)
                                                ;; Tag as LEFT for next join - accumulated result
                                                tagged (map #(assoc % :source :left) deltas)]
                                            (mapcat #(simple/process-delta join-op %) tagged))
                                          deltas))
                                      joined
                                      (range (inc join-idx) (count join-ops)))
                              filtered (when final-deltas (apply-filters final-deltas))
                              projected (when filtered (mapcat #(simple/process-delta project-op %) filtered))]
                          (doseq [p projected]
                            (simple/process-delta collect-op p))))))))))

          :get-results
          (fn [] (simple/get-results collect-op))

          :operators
          {:patterns pattern-ops
           :joins join-ops
           :project project-op
           :collect collect-op}})

       :else
       nil))))
