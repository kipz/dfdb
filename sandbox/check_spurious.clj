(ns check-spurious
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]))

(defn test-single-spurious []
  (let [db (dfdb/create-db {:storage-backend :memory})

        ;; Reproduce the exact scenario: Update #3 adds 55→89
        ;; First create some edges that lead to 55
        _ (dfdb/transact! db [[:db/add 1 :connected 20]
                              [:db/add 20 :connected 55]])

        ;; Now add 55→89
        _ (dfdb/transact! db [[:db/add 55 :connected 89]])

        ;; Check if 89 is reachable via 3 hops
        results (query/query db '[:find ?fofof
                                  :where [?u :connected ?f1]
                                  [?f1 :connected ?f2]
                                  [?f2 :connected ?fofof]])]

    (println "After creating path 1→20→55→89:")
    (println "  Results:" (vec results))
    (println "  Contains [89]?" (contains? (set results) [89]))

    ;; Now check what happens if we overwrite an edge in the path
    ;; In the test, edges might get overwritten due to the multi-valued attribute logic
    (let [;; Check if 1 has other connections
          edges-from-1 (query/query db '[:find ?to :where [1 :connected ?to]])]
      (println "\n Edges from 1:" edges-from-1))

    ;; Add another edge from 1 (might create a set)
    (dfdb/transact! db [[:db/add 1 :connected 30]])

    (let [edges-from-1 (query/query db '[:find ?to :where [1 :connected ?to]])
          results-after (query/query db '[:find ?fofof
                                          :where [?u :connected ?f1]
                                          [?f1 :connected ?f2]
                                          [?f2 :connected ?fofof]])]
      (println "\nAfter adding 1→30:")
      (println "  Edges from 1:" (vec (sort edges-from-1)))
      (println "  Results:" (count results-after))
      (println "  Still contains [89]?" (contains? (set results-after) [89])))))

(comment
  (test-single-spurious))
