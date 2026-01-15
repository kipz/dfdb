(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])

(def test-db (db/create-db {:storage-type :memory}))

(dfdb/transact! test-db [{:account/id "ACC-001" :account/balance 1000}])

(println "Transaction 1:")
(let [r1 (dfdb/transact! test-db [{:tx/id "TX-1" :tx/account "ACC-001" :tx/amount -50 :tx/type :withdrawal}])]
  (println "  tx-id:" (:tx-id r1)))

(println "\nTransaction 2:")
(let [r2 (dfdb/transact! test-db [{:tx/id "TX-2" :tx/account "ACC-001" :tx/amount 200 :tx/type :deposit}])]
  (println "  tx-id:" (:tx-id r2)))

(println "\nQuery for all transactions:")
(let [txs (dfdb/query test-db '[:find ?tx-id ?amount ?type
                                 :where
                                 [?tx :tx/account "ACC-001"]
                                 [?tx :tx/id ?tx-id]
                                 [?tx :tx/amount ?amount]
                                 [?tx :tx/type ?type]])]
  (println "  Result:" txs)
  (println "  Count:" (count txs)))
