(require '[dfdb.core :as dfdb])
(require '[dfdb.db :as db])
(require '[dfdb.index :as index])

(def test-db (db/create-db {:storage-type :memory}))

(println "Adding multiple friends separately:")
(dfdb/transact! test-db [{:user/name "Alice"}
                         {:user/name "Bob"}
                         {:user/name "Charlie"}])

(dfdb/transact! test-db [[:db/add 1 :user/friend 2]
                         [:db/add 1 :user/friend 3]])

(println "\nStorage keys for :user/friend:")
(doseq [[k v] @(.-data_atom (:storage test-db))]
  (when (and (vector? k) (= (nth k 2 nil) :user/friend))
    (println "  Key:" k)
    (println "    Val:" (select-keys v [:e :a :v :op]))))

(println "\nQuery for Alice's friends:")
(println "  " (dfdb/query test-db '[:find ?f :where [1 :user/friend ?f]]))
