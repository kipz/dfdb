(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])

(def test-db (db/create-db {:storage-type :memory}))

(println "=== Testing temporal query issue ===\n")

;; Setup dimension
(dfdb/transact! test-db [{:dimension/name :time/measured
                          :dimension/type :instant
                          :dimension/indexed? true}])

;; Record sensor readings
(dfdb/transact! test-db {:tx-data [{:sensor/id "TEMP-1" :sensor/value 72.5}]
                         :time-dimensions {:time/measured #inst "2026-01-15T10:00:00Z"}})

(println "After first reading (72.5 at 10:00):")
(println "  Query all:" (dfdb/query test-db '[:find ?value :where [?s :sensor/id "TEMP-1"] [?s :sensor/value ?value]]))

(dfdb/transact! test-db {:tx-data [[:db/add [:sensor/id "TEMP-1"] :sensor/value 73.2]]
                         :time-dimensions {:time/measured #inst "2026-01-15T11:00:00Z"}})

(println "\nAfter second reading (73.2 at 11:00):")
(println "  Query all:" (dfdb/query test-db '[:find ?value :where [?s :sensor/id "TEMP-1"] [?s :sensor/value ?value]]))
(println "  As-of 11:30:" (dfdb/query test-db {:query '[:find ?value :where [?s :sensor/id "TEMP-1"] [?s :sensor/value ?value]]
                                                :as-of {:time/measured #inst "2026-01-15T11:30:00Z"}}))

(println "\nExpected as-of 11:30: #{[73.2]}")
