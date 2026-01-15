(require '[dfdb.core :as dfdb])
(require '[dfdb.performance-test :as perf])

(println "Creating database...")
(def db (dfdb/create-db {:storage-backend :memory}))

(println "Generating 500-user social network...")
(def data (perf/generate-social-network 500 8))
(println "Generated" (count data) "transactions")

(println "Loading data...")
(dfdb/transact! db data)

(println "\nTesting friend-of-friend query with OPTIMIZED hash join:")
(time (def result (dfdb/query db '[:find ?fof
                                   :where [?user :friend ?friend]
                                   [?friend :friend ?fof]])))
(println "Result count:" (count result))

(println "\nDone!")
(shutdown-agents)
