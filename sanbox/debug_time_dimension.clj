(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])

(def test-db (db/create-db {:storage-type :memory}))

(println "=== Debug Time Dimension Subscription ===\n")

(def updates (atom []))

;; Add dimensions
(dfdb/transact! test-db [{:dimension/name :time/shipped :dimension/type :instant :dimension/indexed? true}
                         {:dimension/name :time/delivered :dimension/type :instant :dimension/indexed? true}])

(println "1. Created dimensions")

;; Subscribe watching only :time/shipped
(def sub (dfdb/subscribe test-db
                         {:query '[:find ?order :where [?order :order/id _]]
                          :mode :incremental
                          :watch-dimensions [:time/system :time/shipped]
                          :callback (fn [diff]
                                      (println "   Callback triggered! Additions:" (count (:additions diff)) 
                                               "Retractions:" (count (:retractions diff)))
                                      (swap! updates conj diff))}))

(println "2. Subscribed. Update count:" (count @updates))

;; Add order with shipped time - should trigger
(dfdb/transact! test-db {:tx-data [{:order/id "ORD-1"}]
                         :time-dimensions {:time/shipped #inst "2026-01-20"}})
(Thread/sleep 100)
(println "3. Added order with :time/shipped. Update count:" (count @updates))

;; Update with delivered time (not watched) - should NOT trigger
(dfdb/transact! test-db {:tx-data [[:db/add "ORD-1" :order/delivered true]]
                         :time-dimensions {:time/delivered #inst "2026-01-25"}})
(Thread/sleep 100)
(println "4. Updated with :time/delivered (not watched). Update count:" (count @updates))

;; Update with shipped time - should trigger
(dfdb/transact! test-db {:tx-data [[:db/add "ORD-1" :order/tracking "ABC123"]]
                         :time-dimensions {:time/shipped #inst "2026-01-21"}})
(Thread/sleep 100)
(println "5. Updated with :time/shipped again. Update count:" (count @updates))

(println "\nExpected: 3 updates (initial + 2 with :time/shipped)")
(println "Actual:" (count @updates) "updates")

(dfdb/unsubscribe sub)
