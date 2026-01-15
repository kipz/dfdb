(require '[dfdb.core :as dfdb])
(require '[dfdb.index :as index])

(def db (dfdb/create-db {:storage-backend :memory}))

;; Add initial friendships
(dfdb/transact! db [[:db/add 1 :friend 2]
                    [:db/add 2 :friend 1]])

(println "After initial transaction:")
(println "Querying for entity 1's friends...")
(def storage (:storage db))
(def datoms1 (index/scan-eavt storage [:eavt 1 :friend] [:eavt 1 (index/successor-value :friend)]))
(println "Datoms found:" (count datoms1))
(doseq [[k v] (take 5 datoms1)]
  (println "  Key:" k)
  (println "  Value:" v)
  (println "  :v field:" (:v v) "Type:" (type (:v v))))

;; Now add another friend for entity 1
(println "\nAdding another friend for entity 1...")
(dfdb/transact! db [[:db/add 1 :friend 3]
                    [:db/add 3 :friend 1]])

(println "\nAfter second transaction:")
(println "Querying for entity 1's friends again...")
(def datoms2 (index/scan-eavt storage [:eavt 1 :friend] [:eavt 1 (index/successor-value :friend)]))
(println "Datoms found:" (count datoms2))
(doseq [[k v] (take 10 datoms2)]
  (println "  Key:" k)
  (println "  Value:" v)
  (println "  :v field:" (:v v) "Type:" (type (:v v))))

(println "\nChecking if any :v fields are sets...")
(def set-values (filter #(set? (:v (second %))) datoms2))
(if (empty? set-values)
  (println "No sets found in :v fields (correct)")
  (do
    (println "FOUND SETS!")
    (doseq [[k v] set-values]
      (println "  " k " -> " v))))

(shutdown-agents)
