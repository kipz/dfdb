(require '[dfdb.core :as dfdb])
(require '[dfdb.query :as query])
(require '[dfdb.performance-test :as perf])

(def db (dfdb/create-db {:storage-backend :memory}))
(def initial-data (perf/generate-social-network 200 8))
(dfdb/transact! db initial-data)

(println "Testing query/query directly on 200 users...")

(def result (query/query db '[:find ?fof
                              :where [?user :friend ?friend]
                              [?friend :friend ?fof]]))

(println "\nResult count:" (count result))
(println "\nChecking for sets in results...")
(def bad-results (filter #(and (vector? %) (set? (first %))) result))
(if (empty? bad-results)
  (println "All results are correct (no sets)")
  (do
    (println "FOUND" (count bad-results) "BAD RESULTS!")
    (println "\nFirst 5 bad results:")
    (doseq [r (take 5 bad-results)]
      (println "   " r)
      (println "     First elem (the set):" (first r))
      (println "     Set count:" (count (first r))))))

(println "\nFirst 10 results overall:")
(doseq [r (take 10 result)]
  (println "   " r))

(shutdown-agents)
