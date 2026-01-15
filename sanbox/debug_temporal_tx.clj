(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])
(require '[dfdb.index :as index])

(def test-db (db/create-db {:storage-type :memory}))

(dfdb/transact! test-db [{:dimension/name :time/measured
                          :dimension/type :instant
                          :dimension/indexed? true}])

(println "Transaction 1:")
(let [r1 (dfdb/transact! test-db {:tx-data [{:sensor/id "TEMP-1" :sensor/value 72.5}]
                                   :time-dimensions {:time/measured #inst "2026-01-15T10:00:00Z"}})]
  (println "  tx-id:" (:tx-id r1)))

(Thread/sleep 100)

(println "\nTransaction 2:")
(let [r2 (dfdb/transact! test-db {:tx-data [[:db/add [:sensor/id "TEMP-1"] :sensor/value 73.2]]
                                   :time-dimensions {:time/measured #inst "2026-01-15T11:00:00Z"}})]
  (println "  tx-id:" (:tx-id r2)))

(println "\nDatams for :sensor/value:")
(doseq [[k v] @(.-data_atom (:storage test-db))]
  (when (and (vector? k) (= (nth k 2 nil) :sensor/value))
    (println "  " (select-keys v [:e :a :v :op :tx-id :time/measured]))))

(println "\nQuery result:")
(println "  " (dfdb/query test-db {:query '[:find ?value :where [?s :sensor/id "TEMP-1"] [?s :sensor/value ?value]]
                                    :as-of {:time/measured #inst "2026-01-15T11:30:00Z"}}))
