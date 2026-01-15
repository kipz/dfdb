(require '[dfdb.core :as dfdb])
(require '[dfdb.query :as query])
(require '[dfdb.performance-test :as perf])

(def db (dfdb/create-db {:storage-backend :memory}))
(def initial-data (perf/generate-social-network 200 8))
(dfdb/transact! db initial-data)

(println "Initial query...")
(def result-before (query/query db '[:find ?fof
                                     :where [?user :friend ?friend]
                                     [?friend :friend ?fof]]))
(println "Count before updates:" (count result-before))
(def bad-before (filter #(and (vector? %) (set? (first %))) result-before))
(println "Bad results before:" (count bad-before))

;; Apply updates
(println "\nApplying 20 updates...")
(def updates (doall (repeatedly 20 #(perf/generate-friendship-update 200))))
(doseq [[idx update] (map-indexed vector updates)]
  (println "  Update" idx ":" update)
  (dfdb/transact! db update))

(println "\nQuery after updates...")
(def result-after (query/query db '[:find ?fof
                                    :where [?user :friend ?friend]
                                    [?friend :friend ?fof]]))
(println "Count after updates:" (count result-after))

(def bad-after (filter #(and (vector? %) (set? (first %))) result-after))
(if (empty? bad-after)
  (println "All results are correct (no sets)")
  (do
    (println "FOUND" (count bad-after) "BAD RESULTS!")
    (println "\nFirst 5 bad results:")
    (doseq [r (take 5 bad-after)]
      (println "   " r)
      (println "     Set contents:" (vec (first r))))))

(shutdown-agents)
