(require '[dfdb.core :as dfdb])
(require '[dfdb.index :as index])

(def db (dfdb/create-db {:storage-backend :memory}))

(println "Testing map notation...")
(dfdb/transact! db [[:db/add 1 :product/name "Widget" :product/price 100]])

(println "\nQuerying what's stored for entity 1...")
(def storage (:storage db))
(def entity1-datoms (index/scan-eavt storage [:eavt 1] [:eavt 2]))
(println "Datoms for entity 1:" (count entity1-datoms))
(doseq [[k v] entity1-datoms]
  (println "  " k " -> " v))

(println "\nQuerying for product name...")
(def names (dfdb/query db '[:find ?name :where [?e :product/name ?name]]))
(println "Names:" names)

(println "\nQuerying for product price...")
(def prices (dfdb/query db '[:find ?price :where [?e :product/price ?price]]))
(println "Prices:" prices)

(println "\n\nNow testing with order...")
(dfdb/transact! db [[:db/add 10 :order/product 1 :order/amount 500]])

(println "\nQuerying what's stored for entity 10...")
(def entity10-datoms (index/scan-eavt storage [:eavt 10] [:eavt 11]))
(println "Datoms for entity 10:" (count entity10-datoms))
(doseq [[k v] entity10-datoms]
  (println "  " k " -> " v))

(println "\nQuerying for order amounts...")
(def amounts (dfdb/query db '[:find ?amount :where [?e :order/amount ?amount]]))
(println "Amounts:" amounts)

(println "\nQuerying for order products...")
(def order-products (dfdb/query db '[:find ?product :where [?e :order/product ?product]]))
(println "Order products:" order-products)

(shutdown-agents)
