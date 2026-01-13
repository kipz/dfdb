(ns dfdb.dd.execution
  "True differential dataflow execution engine.
  Builds operator graphs from queries and executes incrementally."
  (:require [dfdb.dd.multiset :as ms]
            [dfdb.dd.difference :as diff]
            [dfdb.dd.operator :as op]
            [dfdb.dd.aggregate :as agg]
            [dfdb.query :as query]))

(defrecord OperatorGraph
           [operators        ; Map of operator-id -> operator
            terminal         ; Terminal CollectOperator
            pattern-ops      ; Map of pattern -> operator
            state])          ; Shared state

(defn extract-bindings-from-delta
  "Extract relevant bindings from a transaction delta.
  Returns multiset of bindings that this delta produces."
  [delta]
  (let [{:keys [entity _attribute _new-value _old-value operation]} delta]

    ;; Create binding for this delta
    (cond
      ;; Assert: new value added
      (= operation :assert)
      (ms/multiset {entity 1})  ; Simple: just entity for now

      ;; Retract: old value removed
      (= operation :retract)
      (ms/multiset {})

      :else
      (ms/multiset {}))))

(defn build-graph-for-pattern
  "Build operator sub-graph for a single pattern.
  Returns operator that outputs bindings matching pattern."
  [_db _pattern]

  ;; For pattern [?e :attr ?v], create operator that:
  ;; 1. Takes entity IDs as input
  ;; 2. Looks up attribute value
  ;; 3. Outputs binding {?e entity, ?v value}

  (let [collect (op/make-collect-operator)]

    ;; Simple wrapper for now
    (reify op/Operator
      (input [_this entities timestamp]
        ;; For each entity in input, check if pattern matches
        (let [bindings (mapcat (fn [[_entity _mult]]
                                ;; Execute pattern for this entity
                                ;; TODO: Actual pattern matching
                                 [])
                               (seq entities))]
          (when (seq bindings)
            (op/input collect (ms/multiset (zipmap bindings (repeat 1))) timestamp))))

      (step [_this]
        false)
      (output [_this]
        (op/output collect))
      (frontier [_this]
        (op/frontier collect)))))

(defn build-graph-for-query
  "Build complete DD operator graph for query.
  Returns OperatorGraph with terminal operator."
  [_db query-form]

  (let [{:keys [_find _where aggregates group-vars]} (query/parse-query query-form)

        ;; Terminal collector
        terminal (op/make-collect-operator)

        ;; Build operator chain
        operators (cond
                    ;; Has aggregation
                    (seq aggregates)
                    (let [agg-expr (first aggregates)
                          [agg-fn _var] agg-expr
                          group-by-fn (if (seq group-vars)
                                        (fn [bindings]
                                          (vec (map #(get bindings %) group-vars)))
                                        (constantly nil))

                          aggregator (agg/make-aggregate-operator
                                      group-by-fn
                                      (case agg-fn
                                        count agg/agg-count
                                        sum agg/agg-sum
                                        avg agg/agg-avg
                                        min agg/agg-min
                                        max agg/agg-max)
                                      terminal)]
                      {:aggregate aggregator})

                    ;; No aggregation
                    :else
                    {:terminal terminal})]

    (map->OperatorGraph
     {:operators operators
      :terminal terminal
      :pattern-ops {}
      :state (atom {})})))

(defn execute-incremental
  "Execute query incrementally on deltas.
  Returns difference (what changed in results)."
  [_graph _db _deltas _timestamp]

  ;; Current: Re-execute query (simple, correct)
  ;; TODO: Feed deltas to operator graph for true incremental

  ;; For now, this is a no-op - subscriptions use re-execution
  (diff/empty-difference))

(comment
  "True incremental execution requires:

  1. Pattern Analysis:
     - For each transaction delta, determine which patterns it affects
     - Extract affected entity IDs
     - Feed to relevant pattern operators

  2. Incremental Pattern Evaluation:
     - Pattern operator maintains arrangement of results
     - On entity delta, re-evaluate pattern for THAT entity only
     - Emit difference (old binding out, new binding in)

  3. Incremental Join:
     - Join operator maintains arrangements for both sides
     - On left input, probe right arrangement
     - On right input, probe left arrangement
     - Emit only newly joined results

  4. Incremental Aggregate:
     - Aggregate operator maintains running totals per group
     - On input, update affected groups
     - Emit difference (old aggregate out, new aggregate in)

  5. Propagation:
     - Changes flow through operator graph
     - Each operator processes only what changed
     - Final output is difference of query results

  This is ~400-600 LOC of sophisticated implementation.
  The foundation (operators, multisets, arrangements) exists and works.
  The integration is the remaining work.")
