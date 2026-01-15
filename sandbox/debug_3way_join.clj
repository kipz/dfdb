(ns debug-3way-join
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]
            [dfdb.subscription :as sub]))

(defn test-3way-join-minimal []
  (let [db (dfdb/create-db {:storage-backend :memory})

        ;; Create simple graph: 1→2→3→4
        ;; This creates one 3-hop path: 1→2→3→4
        ;; The query should find ?fofof=4 when ?u=1
        initial-data [[:db/add 1 :connected 2]
                      [:db/add 2 :connected 3]
                      [:db/add 3 :connected 4]]

        _ (dfdb/transact! db initial-data)

        query-map '[:find ?fofof
                    :where [?u :connected ?f1]
                    [?f1 :connected ?f2]
                    [?f2 :connected ?fofof]]

        ;; Test naive query
        naive-results (set (query/query db query-map))

        ;; Test subscription
        sub-results (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff] (swap! sub-results conj diff))
                                        :mode :incremental})

        ;; Add one more edge: 4→5
        ;; This should add result [5] (path 1→2→3→4→5 gives fofof=5)
        _ (dfdb/transact! db [[:db/add 4 :connected 5]])

        ;; Compute subscription final
        sub-final (reduce
                   (fn [state diff]
                     (-> state
                         (clojure.set/union (:additions diff))
                         (clojure.set/difference (:retractions diff))))
                   #{}
                   @sub-results)

        naive-final (set (query/query db query-map))]

    (println "\nInitial:")
    (println "  Naive:" (sort naive-results))
    (println "\nAfter adding 4→5:")
    (println "  Subscription final:" (sort sub-final))
    (println "  Naive final:" (sort naive-final))
    (println "  Match?" (= sub-final naive-final))
    (println "  Sub count:" (count sub-final) "Naive count:" (count naive-final))

    (sub/unsubscribe subscription)
    {:sub sub-final :naive naive-final :match? (= sub-final naive-final)}))

(comment
  (test-3way-join-minimal))
