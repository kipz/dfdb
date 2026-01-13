(ns dfdb.dd.incremental
  "True incremental differential dataflow execution."
  (:require [clojure.set :as set]
            [dfdb.dd.multiset :as ms]
            [dfdb.dd.difference :as diff]
            [dfdb.dd.operator :as op]
            [dfdb.dd.aggregate :as agg]
            [dfdb.query :as query]))

(defn deltas-to-multiset
  "Convert transaction deltas to multiset of changes.
  For pattern matching, we extract relevant bindings from deltas."
  [deltas _query-form]
  ;; Simplified: convert deltas to binding changes
  ;; Real implementation would be pattern-specific

  (let [additions (atom {})
        retractions (atom {})]

    (doseq [delta deltas]
      (let [{:keys [entity attribute new-value old-value operation]} delta]
        ;; For now: simple extraction
        ;; TODO: Match against query patterns
        (case operation
          :assert (swap! additions update [entity attribute new-value] (fnil inc 0))
          :retract (swap! retractions update [entity attribute old-value] (fnil inc 0)))))

    (diff/difference @additions @retractions)))

(defn build-operator-graph-for-query
  "Build DD operator graph for a Datalog query.
  Returns [input-fn output-operator] where:
  - input-fn: feed deltas to graph
  - output-operator: CollectOperator with results"
  [db query-form]

  (let [{:keys [aggregates]} (query/parse-query query-form)

        ;; Terminal operator
        collect (op/make-collect-operator)

        ;; Build graph based on query structure
        graph (cond
                ;; Has aggregation
                (seq aggregates)
                (let [agg-expr (first aggregates)
                      [agg-fn _var] agg-expr

                      ;; Create aggregate operator
                      aggregator (agg/make-aggregate-operator
                                  identity  ; No grouping for now
                                  (case agg-fn
                                    count count
                                    sum #(reduce + 0 %)
                                    avg #(if (empty? %) 0.0 (/ (reduce + 0 %) (double (count %))))
                                    min #(when (seq %) (reduce min %))
                                    max #(when (seq %) (reduce max %)))
                                  collect)]
                  aggregator)

                ;; No aggregation - just collect
                :else
                collect)

        ;; Input function: execute query and feed to graph
        input-fn (fn [timestamp]
                   (let [results (query/query db query-form)
                         result-ms (ms/multiset (zipmap results (repeat 1)))]
                     (op/input graph result-ms timestamp)))]

    [input-fn collect]))

(defn create-incremental-subscription
  "Create subscription with DD operator graph (for future true differential).
  Currently wraps re-execution but provides infrastructure for true DD."
  [db query-form callback]

  (let [[input-fn output-op] (build-operator-graph-for-query db query-form)
        current-results (atom #{})
        tx-counter (atom 0)]

    {:query query-form
     :callback callback
     :input-fn input-fn
     :output-op output-op
     :current-results current-results
     :tx-counter tx-counter

     :update-fn
     (fn []
       ;; Execute query
       (let [timestamp (swap! tx-counter inc)]
         (input-fn timestamp)

         ;; Get results
         (let [new-results (op/output output-op)
               old-results @current-results

               additions (clojure.set/difference new-results old-results)
               retractions (clojure.set/difference old-results new-results)

               diff {:additions additions :retractions retractions}]

           (reset! current-results new-results)

           (when (or (seq additions) (seq retractions))
             (callback diff)))))}))

(comment
  "This provides the FRAMEWORK for true differential dataflow.

  What's working:
  - Operators can process collections
  - Can be chained together
  - Aggregates work

  What's missing:
  - Feeding DELTAS (not full collections) to operators
  - Maintaining state across updates
  - True incremental join with arrangement probing
  - Only processing CHANGED data

  Current: Re-execute query, feed full results to operators
  Needed: Feed only deltas, operators maintain state, emit only changes")
