(require '[dfdb.core :as dfdb])
(require '[dfdb.dd.delta-core :as delta])

(def db (dfdb/create-db {:storage-backend :memory}))

;; Build initial state
(dfdb/transact! db [[:db/add 1 :friend 2]
                    [:db/add 1 :friend 3]])

(println "Current state: Entity 1 has friends #{2, 3}")

;; Now add friend 4
(println "\nAdding friend 4 to entity 1...")
(def tx-result (dfdb/transact! db [[:db/add 1 :friend 4]]))

(println "\nTransaction deltas:")
(doseq [d (:deltas tx-result)]
  (println "  Entity:" (:entity d) "Attr:" (:attribute d))
  (println "  Old:" (:old-value d))
  (println "  New:" (:new-value d))
  (println "  Op:" (:operation d)))

(println "\nConverting to binding deltas for pattern [?user :friend ?friend]...")
(def binding-deltas (delta/transaction-deltas-to-binding-deltas (:deltas tx-result) ['?user :friend '?friend]))

(println "Binding deltas generated:")
(doseq [bd binding-deltas]
  (println "  Binding:" (:binding bd) "Mult:" (:mult bd)))

(println "\nExpected: Only 1 delta for adding friend 4")
(println "  {?user 1, ?friend 4} mult: +1")
(println "\nActual count:" (count binding-deltas))

(shutdown-agents)
