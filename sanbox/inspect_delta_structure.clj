(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])
(require '[dfdb.transaction :as tx])

;; Monkey-patch notify-all-subscriptions to inspect deltas
(def original-notify (resolve 'dfdb.subscription/notify-all-subscriptions))
(def original-fn @original-notify)

(alter-var-root original-notify
                (fn [_] (fn [db deltas]
                          (println "\n=== DELTAS ===")
                          (doseq [delta deltas]
                            (println "Delta keys:" (keys delta))
                            (println "Delta:" delta))
                          (original-fn db deltas))))

(def test-db (db/create-db {:storage-type :memory}))

(dfdb/transact! test-db [{:dimension/name :time/shipped :dimension/type :instant :dimension/indexed? true}])

(def sub (dfdb/subscribe test-db
                         {:query '[:find ?order :where [?order :order/id _]]
                          :mode :incremental
                          :watch-dimensions [:time/system :time/shipped]
                          :callback (fn [diff] nil)}))

(println "\n>>> Transaction with :time/shipped")
(dfdb/transact! test-db {:tx-data [{:order/id "ORD-1"}]
                         :time-dimensions {:time/shipped #inst "2026-01-20"}})

(println "\n>>> Transaction with :time/delivered")
(dfdb/transact! test-db {:tx-data [[:db/add "ORD-1" :order/delivered true]]
                         :time-dimensions {:time/delivered #inst "2026-01-25"}})

(dfdb/unsubscribe sub)
