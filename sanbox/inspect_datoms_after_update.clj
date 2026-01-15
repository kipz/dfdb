(require '[dfdb.core :as dfdb])
(require '[dfdb.index :as index])

(def db (dfdb/create-db {:storage-backend :memory}))

;; Add initial friendship
(dfdb/transact! db [[:db/add 1 :friend 2]
                    [:db/add 2 :friend 1]])

(println "After initial transaction:")
(def storage (:storage db))
(def datoms1 (index/scan-aevt storage [:aevt :friend] [:aevt (index/successor-value :friend)]))
(println "Datoms in AEVT for :friend:" (count datoms1))
(doseq [[k v] (take 10 datoms1)]
  (println "  " k " -> " v))

;; Add another friend for entity 1
(println "\nAdding more friends for entity 1...")
(dfdb/transact! db [[:db/add 1 :friend 3]
                    [:db/add 1 :friend 4]
                    [:db/add 3 :friend 1]
                    [:db/add 4 :friend 1]])

(println "\nAfter adding more friends:")
(def datoms2 (index/scan-aevt storage [:aevt :friend] [:aevt (index/successor-value :friend)]))
(println "Datoms in AEVT for :friend:" (count datoms2))
(doseq [[k v] (take 20 datoms2)]
  (println "  " k " -> "  {:e (:e v) :v (:v v) :op (:op v) :tx-id (:tx-id v)}))

(println "\nChecking for entity 1 specifically:")
(def entity1-datoms (filter (fn [[_k v]] (= 1 (:e v))) datoms2))
(println "Entity 1 has" (count entity1-datoms) "datoms:")
(doseq [[k v] entity1-datoms]
  (println "  Friend:" (:v v) "Op:" (:op v) "Tx:" (:tx-id v)))

(println "\nQuerying [?e :friend ?v]:")
(def result (dfdb/query db '[:find ?e ?v :where [?e :friend ?v]]))
(println "Result count:" (count result))
(println "Entity 1's friends in results:")
(doseq [[e v] (filter #(= 1 (first %)) result)]
  (println "  Friend:" v "Type:" (type v)))

(shutdown-agents)
