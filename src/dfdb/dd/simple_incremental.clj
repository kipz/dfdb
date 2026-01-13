(ns dfdb.dd.simple-incremental
  "Simplified incremental execution based on xtflow delta model."
  (:require [dfdb.dd.delta-simple :as delta]))

(defprotocol DeltaOperator
  "Operator that processes deltas incrementally (xtflow-style)."
  (process-delta [_this delta]
    "Process a delta and return output deltas."))

(defrecord PatternOperator [pattern state]
  ;; Matches pattern against binding deltas
  ;; Since we already have bindings from transaction-deltas-to-binding-deltas,
  ;; this is mostly pass-through with filtering

  DeltaOperator
  (process-delta [_ delta]
    ;; Delta is {:binding {...} :mult +1/-1}
    ;; Check if binding matches all constant constraints in pattern
    (let [binding (:binding delta)
          [e _a v] pattern
          variable? (fn [x] (and (symbol? x) (.startsWith (name x) "?")))]

      ;; Pattern constraints:
      ;; - If e is constant (not variable), binding must match
      ;; - If v is constant (not variable/wildcard), binding must match
      ;; - Attribute is already filtered by transaction-deltas-to-binding-deltas
      (if (and (or (variable? e) (= (get binding e) e))
               (or (variable? v) (= '_ v) (= (get binding v) v)))
        [delta]
        []))))

(defrecord ProjectOperator [find-vars state]
  ;; Projects bindings to find variables

  DeltaOperator
  (process-delta [_ delta]
    (let [binding (:binding delta)
          mult (:mult delta)
          ;; Project to find vars
          values (map #(get binding %) find-vars)

          ;; Handle multi-valued attributes: if any value is a set, expand into multiple deltas
          has-set? (some set? values)]

      (if has-set?
        ;; Expand sets into multiple deltas (Cartesian product if multiple sets)
        (let [expanded-values (map (fn [v] (if (set? v) (seq v) [v])) values)
              ;; Cartesian product of all value lists
              combinations (reduce (fn [acc vals]
                                     (for [existing acc
                                           v vals]
                                       (conj existing v)))
                                   [[]]
                                   expanded-values)]
          (map (fn [combo] (delta/make-delta (vec combo) mult)) combinations))
        ;; No sets - simple projection
        [(delta/make-delta (vec values) mult)]))))

(defrecord PredicateFilter [pred-fn]
  ;; Filters deltas based on predicate function

  DeltaOperator
  (process-delta [_this delta]
    (let [binding (:binding delta)]
      (if (try
            (pred-fn binding)
            (catch NullPointerException _e
              ;; Null pointer is expected when binding values are nil
              false)
            (catch ClassCastException _e
              ;; Type mismatch in comparison - treat as false
              false))
        [delta]
        []))))

(defrecord CollectResults [state]
  ;; Accumulates deltas into final result set

  DeltaOperator
  (process-delta [_this delta]
    (let [value (:binding delta)
          mult (:mult delta)]
      ;; Update accumulated state
      (swap! (:accumulated state) update value (fnil + 0) mult)
      ;; Return empty - this is terminal
      []))

  Object
  (toString [_]
    (str "CollectResults: " (count @(:accumulated state)) " items")))

(defn get-results
  "Get current results from CollectResults operator."
  [collect-op]
  (let [accumulated @(:accumulated (:state collect-op))]
    (set (mapcat (fn [[value count]]
                   (when (pos? count)
                     (repeat count value)))
                 accumulated))))

(defn make-simple-pipeline
  "Create simple operator pipeline for single-pattern query.
  Returns {:process-deltas-fn get-results-fn}."
  [pattern find-vars]

  (let [;; Create operators
        pattern-op (->PatternOperator pattern (atom {}))
        project-op (->ProjectOperator find-vars (atom {}))
        collect-op (->CollectResults {:accumulated (atom {})})

        ;; Chain: pattern -> project -> collect
        process-chain (fn [delta]
                        (->> [delta]
                             (mapcat #(process-delta pattern-op %))
                             (mapcat #(process-delta project-op %))
                             (mapcat #(process-delta collect-op %))))]

    {:process-deltas
     (fn [tx-deltas]
       ;; Convert transaction deltas to binding deltas
       (let [binding-deltas (delta/transaction-deltas-to-binding-deltas tx-deltas pattern)]
         ;; Process through pipeline
         (doseq [d binding-deltas]
           (process-chain d))))

     :get-results
     (fn []
       (get-results collect-op))

     :operators
     {:pattern pattern-op
      :project project-op
      :collect collect-op}}))
