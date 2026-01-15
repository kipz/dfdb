(require '[dfdb.core :as dfdb])
(require '[dfdb.performance-test :as perf])

(def db (dfdb/create-db {:storage-backend :memory}))

;; Generate small dataset
(def products (perf/generate-ecommerce-products 5))
(def orders (perf/generate-ecommerce-orders 10 5))

(println "Products:")
(doseq [p products]
  (println "  " p))

(println "\nOrders:")
(doseq [o orders]
  (println "  " o))

(dfdb/transact! db (concat products orders))

(println "\nQuerying for all orders with amounts...")
(def orders-with-amounts (dfdb/query db '[:find ?order ?amount
                                          :where [?order :order/amount ?amount]]))
(println "Orders with amounts:" (count orders-with-amounts))
(doseq [[order amount] (take 10 orders-with-amounts)]
  (println "  Order:" order "Amount:" amount "Type:" (type amount) "Nil?" (nil? amount)))

(println "\nQuerying for products with categories...")
(def products-with-categories (dfdb/query db '[:find ?product ?category
                                               :where [?product :product/category ?category]]))
(println "Products with categories:" (count products-with-categories))
(doseq [[product category] (take 10 products-with-categories)]
  (println "  Product:" product "Category:" category))

(println "\nFull join query (no aggregation)...")
(def full-join (dfdb/query db '[:find ?order ?product ?category ?amount
                                :where [?order :order/product ?product]
                                [?product :product/category ?category]
                                [?order :order/amount ?amount]]))
(println "Full join results:" (count full-join))
(doseq [[order product category amount] (take 10 full-join)]
  (println "  Order:" order "Product:" product "Category:" category "Amount:" amount "Nil?" (nil? amount)))

(println "\nNow trying aggregation...")
(try
  (def agg-result (dfdb/query db '[:find ?category (sum ?amount)
                                   :where [?order :order/product ?product]
                                   [?product :product/category ?category]
                                   [?order :order/amount ?amount]]))
  (println "Aggregation result:" agg-result)
  (catch Exception e
    (println "ERROR:" (.getMessage e))
    (println "Cause:" (.getCause e))
    (.printStackTrace e)))

(shutdown-agents)
