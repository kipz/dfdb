(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])
(require '[dfdb.index :as index])

(def test-db (db/create-db {:storage-type :memory}))

(println "=== Detailed temporal query debugging ===\n")

;; Setup dimension
(dfdb/transact! test-db [{:dimension/name :time/measured
                          :dimension/type :instant
                          :dimension/indexed? true}])

;; Record sensor readings
(println "Transaction 1: value 72.5 at 10:00")
(let [result (dfdb/transact! test-db {:tx-data [{:sensor/id "TEMP-1" :sensor/value 72.5}]
                                      :time-dimensions {:time/measured #inst "2026-01-15T10:00:00Z"}})]
  (println "  Deltas:" (count (:deltas result)))
  (doseq [d (:deltas result)]
    (println "    " (select-keys d [:entity :attribute :new-value :time/measured]))))

(println "\nTransaction 2: value 73.2 at 11:00")
(let [result (dfdb/transact! test-db {:tx-data [[:db/add [:sensor/id "TEMP-1"] :sensor/value 73.2]]
                                      :time-dimensions {:time/measured #inst "2026-01-15T11:00:00Z"}})]
  (println "  Deltas:" (count (:deltas result)))
  (doseq [d (:deltas result)]
    (println "    " (select-keys d [:entity :attribute :old-value :new-value :time/measured]))))

(println "\nScanning EAVT for all sensor/value datoms:")
(let [storage (:storage test-db)
      datoms (index/scan-eavt storage [:eavt] [:eavt nil])]
  (doseq [[k d] datoms]
    (when (= (:a d) :sensor/value)
      (println "  " (select-keys d [:e :a :v :op :time/measured]) "tx:" (:tx-id (:tx d))))))

(println "\nQuery without as-of:")
(println "  Result:" (dfdb/query test-db '[:find ?value :where [?s :sensor/id "TEMP-1"] [?s :sensor/value ?value]]))

(println "\nQuery with as-of 11:30:")
(println "  Result:" (dfdb/query test-db {:query '[:find ?value :where [?s :sensor/id "TEMP-1"] [?s :sensor/value ?value]]
                                          :as-of {:time/measured #inst "2026-01-15T11:30:00Z"}}))
