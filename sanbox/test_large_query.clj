(require '[dfdb.core :as dfdb])
(require '[dfdb.performance-test :as perf])

(def db (dfdb/create-db {:storage-backend :memory}))
(dfdb/transact! db (perf/generate-social-network 50 5))  ; 50 users, smaller

(def result (dfdb/query db '[:find ?fof
                             :where [?user :friend ?friend]
                             [?friend :friend ?fof]]))

(println "Result count:" (count result))
(println "First 5 results:" (take 5 result))
(println "Result types:")
(doseq [r (take 3 result)]
  (println "  " r "(type:" (type r) ", first elem:" (first r) ", first elem type:" (type (first r)) ")"))

(shutdown-agents)
