(ns dfdb.dd.compiler
  "Compile Datalog queries to differential dataflow operator graphs."
  (:require [dfdb.dd.operator :as op]
            [dfdb.dd.multiset :as ms]
            [dfdb.query :as query]))

(defn compile-pattern-to-scan
  "Compile a single Datalog pattern to a scan operation.
  Returns function that scans database and returns multiset of bindings."
  [db pattern]
  (fn [_timestamp]
    ;; Execute pattern against database
    (let [bindings (query/match-pattern db pattern {} nil)]
      ;; Convert to multiset
      (ms/multiset (zipmap bindings (repeat 1))))))

(defn compile-predicate-to-filter
  "Compile a predicate clause to a filter operator."
  [pred-clause downstream]
  (op/make-filter-operator
   (fn [bindings]
     (query/eval-predicate
      (if (vector? pred-clause) (first pred-clause) pred-clause)
      bindings))
   downstream))

(defn compile-not-clause
  "Compile a NOT clause to operators."
  [_not-pattern _db downstream]
  ;; NOT is anti-join - for now, handled in query re-execution
  ;; True DD NOT requires anti-join operator
  downstream)

(defn compile-query-to-operators
  "Compile Datalog query to DD operator graph.
  Returns [source-fn terminal-operator] where:
  - source-fn: function that feeds initial data
  - terminal-operator: CollectOperator with results"
  [db query-form]
  (let [_parsed (query/parse-query query-form)

        ;; Create terminal collect operator
        terminal (op/make-collect-operator)

        ;; Build operator chain backwards from terminal
        ;; For now: simple re-execution wrapper
        ;; TODO: Build true DD operator graph

        source-fn (fn [timestamp]
                   ;; Execute full query (re-execution model for now)
                    (let [results (query/query db query-form)
                         ;; Convert to multiset
                          result-ms (ms/multiset (zipmap results (repeat 1)))]
                      (op/input terminal result-ms timestamp)))]

    [source-fn terminal]))

(defn create-subscription-operators
  "Create DD operator graph for a subscription.
  Returns operator graph that can be updated incrementally."
  [db query-form]
  (compile-query-to-operators db query-form))

;; =============================================================================
;; Advanced: True Differential Compilation (TODO)
;; =============================================================================

(defn compile-pattern-to-operator
  "Compile a single pattern to a stream operator.
  This would scan indexes and emit changes incrementally."
  [_db _pattern downstream]
  ;; TODO: Build operator that:
  ;; 1. Maintains arrangement of pattern results
  ;; 2. On delta, computes affected bindings
  ;; 3. Emits only changed bindings
  ;; 4. O(changes) not O(data)
  downstream)

(defn compile-join-to-operator
  "Compile a join of two patterns to join operator with arrangements."
  [_db _pattern1 _pattern2 downstream]
  ;; TODO: Build join operator that:
  ;; 1. Maintains arrangements for both sides
  ;; 2. On input to one side, probes other arrangement
  ;; 3. Emits only new joined results
  ;; 4. O(changes) join
  downstream)

(defn compile-aggregate-to-operator
  "Compile aggregation to incremental aggregate operator."
  [_db _find-expr _group-vars downstream]
  ;; TODO: Build aggregate operator that:
  ;; 1. Maintains running aggregates per group
  ;; 2. On change, updates affected groups only
  ;; 3. Emits aggregate deltas (old value out, new value in)
  ;; 4. O(groups changed) not O(all groups)
  downstream)

(comment
  "True differential compilation is complex and would require:
  - Separate operators for each pattern
  - Arrangements for join probing
  - Incremental aggregate state
  - Change propagation through operator graph
  - ~500-800 LOC additional

  Current re-execution model works correctly for all queries.
  True DD compilation is optimization for large datasets.")
