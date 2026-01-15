(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])

(def test-db (db/create-db {:storage-type :memory}))

;; Setup dimension
(dfdb/transact! test-db [{:dimension/name :time/measured
                          :dimension/type :instant
                          :dimension/indexed? true}])

(println "After dimension setup:")
(println "  Storage data count:" (count @(.-data_atom (:storage test-db))))

;; Record sensor readings
(dfdb/transact! test-db {:tx-data [{:sensor/id "TEMP-1" :sensor/value 72.5}]
                         :time-dimensions {:time/measured #inst "2026-01-15T10:00:00Z"}})

(println "\nAfter first transaction:")
(println "  Storage data count:" (count @(.-data_atom (:storage test-db))))
(println "  Sample keys:")
(doseq [k (take 10 (keys @(.-data_atom (:storage test-db))))]
  (println "    " k))

(dfdb/transact! test-db {:tx-data [[:db/add [:sensor/id "TEMP-1"] :sensor/value 73.2]]
                         :time-dimensions {:time/measured #inst "2026-01-15T11:00:00Z"}})

(println "\nAfter second transaction:")
(println "  Storage data count:" (count @(.-data_atom (:storage test-db))))
(println "  Keys with :sensor/value:")
(doseq [[k v] @(.-data_atom (:storage test-db))]
  (when (and (vector? k) (= (nth k 2 nil) :sensor/value))
    (println "    Key:" k)
    (println "      Val:" (select-keys v [:e :a :v :op :time/measured]))))
