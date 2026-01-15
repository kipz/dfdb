(ns debug-3way-detailed
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]
            [dfdb.subscription :as sub]))

(defn generate-edges [n]
  ;; Create simple graph with some paths
  ;; 1→2→3→4, 5→6→7→8, etc.
  (for [i (range 1 n)]
    [:db/add i :connected (inc i)]))

(defn test-3way-detailed []
  (let [db (dfdb/create-db {:storage-backend :memory})

        ;; Create chain: 1→2→3→4→5→6→7→8→9→10
        initial-data (generate-edges 9)

        _ (dfdb/transact! db initial-data)

        query-map '[:find ?fofof
                    :where [?u :connected ?f1]
                    [?f1 :connected ?f2]
                    [?f2 :connected ?fofof]]

        ;; Test naive query
        naive-initial (set (query/query db query-map))

        ;; Test subscription
        sub-results (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff]
                                                    (println "DIFF: +" (:additions diff) "-" (:retractions diff))
                                                    (swap! sub-results conj diff))
                                        :mode :incremental})

        _ (println "\nInitial results: Naive =" (count naive-initial) "Sub callback had" (count (:additions (first @sub-results))))

        ;; Add edge that creates new 3-hop path
        ;; Add 10→11: creates path 8→9→10→11, so 11 should appear
        _ (println "\nAdding 10→11")
        _ (dfdb/transact! db [[:db/add 10 :connected 11]])

        ;; Add edge from beginning
        _ (println "Adding 0→1")
        _ (dfdb/transact! db [[:db/add 0 :connected 1]])

        ;; Compute subscription final
        sub-final (reduce
                   (fn [state diff]
                     (-> state
                         (clojure.set/union (:additions diff))
                         (clojure.set/difference (:retractions diff))))
                   #{}
                   @sub-results)

        naive-final (set (query/query db query-map))]

    (println "\nFinal:")
    (println "  Subscription count:" (count sub-final))
    (println "  Naive count:" (count naive-final))
    (println "  Match?" (= sub-final naive-final))
    (when (not= sub-final naive-final)
      (println "  Subscription only:" (clojure.set/difference sub-final naive-final))
      (println "  Naive only:" (clojure.set/difference naive-final sub-final)))

    (sub/unsubscribe subscription)
    {:sub sub-final :naive naive-final :match? (= sub-final naive-final)}))

(comment
  (test-3way-detailed))
