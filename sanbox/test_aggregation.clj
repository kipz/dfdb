(require '[dfdb.core :as dfdb])
(require '[dfdb.performance-test :as perf])

(println "Testing aggregation query...")

(def db (dfdb/create-db {:storage-backend :memory}))

;; Generate small dataset
(def products (perf/generate-ecommerce-products 10))
(def orders (perf/generate-ecommerce-orders 20 10))

(println "Products generated:" (count products))
(println "Orders generated:" (count orders))

(dfdb/transact! db (concat products orders))

(println "\nQuerying...")
(def result (dfdb/query db '[:find ?category (sum ?amount)
                             :where [?order :order/product ?product]
                             [?product :product/category ?category]
                             [?order :order/amount ?amount]]))

(println "Result:" result)
(println "Count:" (count result))

(println "\nChecking intermediate bindings...")
(def step1 (dfdb/query db '[:find ?order ?product
                            :where [?order :order/product ?product]]))
(println "Step 1 (order->product):" (count step1) "results")

(def step2 (dfdb/query db '[:find ?product ?category
                            :where [?product :product/category ?category]]))
(println "Step 2 (product->category):" (count step2) "results")

(def step3 (dfdb/query db '[:find ?order ?amount
                            :where [?order :order/amount ?amount]]))
(println "Step 3 (order->amount):" (count step3) "results")
(println "First 3 amounts:")
(doseq [[order amount] (take 3 step3)]
  (println "  Order:" order "Amount:" amount "Type:" (type amount)))

(shutdown-agents)
