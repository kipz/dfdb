(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])

(def test-db (db/create-db {:storage-type :memory}))

(dfdb/transact! test-db [{:dimension/name :time/measured
                          :dimension/type :instant
                          :dimension/indexed? true}])

(dfdb/transact! test-db {:tx-data [{:sensor/id "TEMP-1" :sensor/value 72.5}]
                         :time-dimensions {:time/measured #inst "2026-01-15T10:00:00Z"}})

(Thread/sleep 100)

(dfdb/transact! test-db {:tx-data [[:db/add [:sensor/id "TEMP-1"] :sensor/value 73.2]]
                         :time-dimensions {:time/measured #inst "2026-01-15T11:00:00Z"}})

(println "First pattern [?s :sensor/id \"TEMP-1\"] results:")
(println "  " (dfdb/query test-db {:query '[:find ?s :where [?s :sensor/id "TEMP-1"]]
                                    :as-of {:time/measured #inst "2026-01-15T11:30:00Z"}}))

(println "\nSecond pattern [?s :sensor/value ?value] with ?s unbound (Case 4):")
(println "  " (dfdb/query test-db {:query '[:find ?s ?value :where [?s :sensor/value ?value]]
                                    :as-of {:time/measured #inst "2026-01-15T11:30:00Z"}}))
