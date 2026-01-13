(ns dfdb.dd.compile
  "Compile Datalog queries to differential dataflow operator graphs."
  (:require [clojure.set :as set]
            [dfdb.dd.operator :as op]
            [dfdb.dd.multiset :as ms]
            [dfdb.query :as query]))

(defn pattern-to-scan-fn
  "Convert pattern to function that extracts bindings from deltas."
  [pattern]
  (fn [_db delta]
    (let [[e-sym a-sym v-sym] pattern
          {:keys [entity attribute new-value old-value operation]} delta]

      (if (= attribute a-sym)
        (let [changes (atom {:additions {} :retractions {}})]
          ;; Retract old binding
          (when old-value
            (swap! changes assoc-in [:retractions {e-sym entity, v-sym old-value}] 1))

          ;; Add new binding
          (when (and new-value (= operation :assert))
            (swap! changes assoc-in [:additions {e-sym entity, v-sym new-value}] 1))

          @changes)
        {:additions {} :retractions {}}))))

(defn compile-simple-query
  "Compile simple query (single pattern) to DD graph."
  [db query-form]

  (let [{:keys [find where]} (query/parse-query query-form)
        pattern (first where)
        collect (op/make-collect-operator)
        scan-fn (pattern-to-scan-fn pattern)
        current-bindings (atom #{})  ; Track bindings, not projected results
        tx-counter (atom 0)]

    {:feed-deltas
     (fn [deltas]
       (let [timestamp (swap! tx-counter inc)
             all-changes (reduce (fn [acc delta]
                                   (let [changes (scan-fn db delta)]
                                     {:additions (merge (:additions acc) (:additions changes))
                                      :retractions (merge (:retractions acc) (:retractions changes))}))
                                 {:additions {} :retractions {}}
                                 deltas)]
         (when (or (seq (:additions all-changes)) (seq (:retractions all-changes)))
           (let [combined (merge-with +
                                      (:additions all-changes)
                                      (into {} (map (fn [[k v]] [k (- v)]) (:retractions all-changes))))
                 diff-ms (ms/multiset combined)]
             (op/input collect diff-ms timestamp)))))

     :get-results
     (fn []
       (let [bindings (op/output collect)]
         (when bindings
           (set (map (fn [binding]
                       (vec (map #(get binding %) find)))
                     bindings)))))

     :get-diff
     (fn []
       (let [new-bindings (op/output collect)
             old-bindings @current-bindings

             new-results (when new-bindings
                           (set (map (fn [binding]
                                       (vec (map #(get binding %) find)))
                                     new-bindings)))
             old-results (when old-bindings
                           (set (map (fn [binding]
                                       (vec (map #(get binding %) find)))
                                     old-bindings)))

             additions (set/difference (or new-results #{}) (or old-results #{}))
             retractions (set/difference (or old-results #{}) (or new-results #{}))]

         (reset! current-bindings new-bindings)
         {:additions additions :retractions retractions}))}))

(defn compile-query-to-graph
  "Compile Datalog query to DD operator graph."
  [db query-form]
  (let [{:keys [where]} (query/parse-query query-form)]
    (when (= 1 (count where))
      (compile-simple-query db query-form))))
