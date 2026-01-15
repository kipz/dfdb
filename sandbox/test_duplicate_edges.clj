(ns test-duplicate-edges
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]
            [dfdb.subscription :as sub]))

(defn test-duplicate []
  (let [db (dfdb/create-db {:storage-backend :memory})

        ;; Create simple 3-hop path: 1→2→3→4
        _ (dfdb/transact! db [[:db/add 1 :connected 2]
                              [:db/add 2 :connected 3]
                              [:db/add 3 :connected 4]])

        query-map '[:find ?fofof
                    :where [?u :connected ?f1]
                    [?f1 :connected ?f2]
                    [?f2 :connected ?fofof]]

        results1 (query/query db query-map)
        _ (println "After initial: results =" results1)

        sub-results (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff]
                                                    (println "DIFF: +" (:additions diff) "-" (:retractions diff))
                                                    (swap! sub-results conj diff))
                                        :mode :incremental})

        ;; Add the SAME edge again: 1→2 (duplicate)
        _ (println "\nAdding duplicate edge 1→2")
        _ (dfdb/transact! db [[:db/add 1 :connected 2]])

        ;; Check results
        results2 (query/query db query-map)
        _ (println "After duplicate: results =" results2)

        sub-final (reduce
                   (fn [state diff]
                     (-> state
                         (clojure.set/union (:additions diff))
                         (clojure.set/difference (:retractions diff))))
                   #{}
                   @sub-results)]

    (println "\nSub final:" sub-final)
    (println "Naive final:" (set results2))
    (println "Match?" (= sub-final (set results2)))

    (sub/unsubscribe subscription)))

(comment
  (test-duplicate))
