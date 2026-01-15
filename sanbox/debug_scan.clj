(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])
(require '[dfdb.index :as index])

(def test-db (db/create-db {:storage-type :memory}))

;; Setup dimension
(dfdb/transact! test-db [{:dimension/name :time/measured
                          :dimension/type :instant
                          :dimension/indexed? true}])

;; Record sensor readings
(dfdb/transact! test-db {:tx-data [{:sensor/id "TEMP-1" :sensor/value 72.5}]
                         :time-dimensions {:time/measured #inst "2026-01-15T10:00:00Z"}})

(dfdb/transact! test-db {:tx-data [[:db/add [:sensor/id "TEMP-1"] :sensor/value 73.2]]
                         :time-dimensions {:time/measured #inst "2026-01-15T11:00:00Z"}})

(println "All EAVT datoms:")
(let [storage (:storage test-db)
      datoms (index/scan-eavt storage [:eavt] [:eavt nil])]
  (println "  Count:" (count datoms))
  (doseq [[k d] datoms]
    (println "  Key:" k)
    (println "    Datom:" d)))
