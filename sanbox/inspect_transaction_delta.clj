(require '[dfdb.core :as dfdb])

(def db (dfdb/create-db {:storage-backend :memory}))

;; Start with entity 1 having friends 2 and 3
(println "Initial: Entity 1 friends 2, 3")
(def tx1-result (dfdb/transact! db [[:db/add 1 :friend 2]
                                    [:db/add 1 :friend 3]]))
(println "Tx1 deltas:")
(doseq [d (:deltas tx1-result)]
  (println "  " (select-keys d [:entity :attribute :old-value :new-value :operation])))

;; Now add friend 4 to entity 1
(println "\nAdding: Entity 1 friend 4")
(def tx2-result (dfdb/transact! db [[:db/add 1 :friend 4]]))
(println "Tx2 deltas:")
(doseq [d (:deltas tx2-result)]
  (println "  Entity:" (:entity d))
  (println "  Attribute:" (:attribute d))
  (println "  Old-value:" (:old-value d) "Type:" (type (:old-value d)))
  (println "  New-value:" (:new-value d) "Type:" (type (:new-value d)))
  (println "  Operation:" (:operation d))
  (println))

(println "How delta-to-binding-deltas would interpret this:")
(println "  If old-value is a set, we retract ALL elements")
(println "  If new-value is a set, we add ALL elements")
(println "  Problem: Retracting ALL old values even though only 1 new one was added!")

(shutdown-agents)
