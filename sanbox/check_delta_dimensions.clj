(require '[dfdb.core :as dfdb])

(def db (dfdb/create-db {:storage-backend :memory}))

(println "Checking delta dimensions for set update...")

(dfdb/transact! db [[:db/add 1 :friend 2]
                    [:db/add 1 :friend 3]])

(println "\nAdding friend 4 to create a set update...")
(def tx-result (dfdb/transact! db [[:db/add 1 :friend 4]]))

(println "\nTransaction deltas:")
(doseq [d (:deltas tx-result)]
  (println "\nDelta keys:" (keys d))
  (println "Has :time/system?" (contains? d :time/system))
  (println "Full delta:" d))

(shutdown-agents)
