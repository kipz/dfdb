(ns test-bulk-updates
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]
            [dfdb.subscription :as sub]))

(defn test-bulk-updates-correctness
  "Test correctness of bulk updates with subscriptions"
  []
  (let [db (dfdb/create-db {:storage-backend :memory})

        ;; Initial data: 10 products with various prices
        initial-data (for [i (range 1 11)]
                       {:db/id i
                        :product/name (str "Product " i)
                        :product/price (+ 30 (* i 10))})  ; Prices: 40, 50, 60, ..., 130

        _ (dfdb/transact! db initial-data)

        ;; Query for products with price < 50
        query-map '[:find ?product ?price
                    :where [?product :product/price ?price]
                    [(< ?price 50)]]

        ;; Track subscription results
        subscription-diffs (atom [])

        ;; Create subscription
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff]
                                                    (swap! subscription-diffs conj diff))
                                        :mode :incremental})

        ;; Wait for initial result
        _ (Thread/sleep 50)

        _ (println "Initial subscription result:" (first @subscription-diffs))
        _ (println "Initial query result:" (query/query db query-map))

        ;; Bulk update: Update many products in single transaction
        ;; Some products will cross the threshold multiple times if we're not careful
        bulk-update [;; Product 1: 40 -> 45 (stays in result)
                     [:db/add 1 :product/price 45]
                     ;; Product 2: 50 -> 35 (enters result)
                     [:db/add 2 :product/price 35]
                     ;; Product 3: 60 -> 48 -> 55 (should NOT be in result)
                     [:db/add 3 :product/price 48]
                     [:db/add 3 :product/price 55]
                     ;; Product 4: 70 -> 42 (enters result)
                     [:db/add 4 :product/price 42]]

        _ (dfdb/transact! db bulk-update)
        _ (dfdb/flush-subscriptions! db)

        ;; Compute subscription final state
        subscription-final
        (reduce
         (fn [state diff]
           (-> state
               (clojure.set/union (:additions diff))
               (clojure.set/difference (:retractions diff))))
         #{}
         @subscription-diffs)

        ;; Query final state
        naive-final (set (query/query db query-map))

        match? (= subscription-final naive-final)]

    (println "\n=== Results ===")
    (println "Subscription final:" (sort subscription-final))
    (println "Naive final:" (sort naive-final))
    (println "Match?" match?)
    (println "Subscription only:" (clojure.set/difference subscription-final naive-final))
    (println "Naive only:" (clojure.set/difference naive-final subscription-final))

    (when-not match?
      (println "\nAll diffs received:")
      (doseq [[i diff] (map-indexed vector @subscription-diffs)]
        (println "Diff" i ":" diff)))

    (sub/unsubscribe subscription)
    match?))

;; Run the test
(println "Testing bulk updates correctness...")
(let [result (test-bulk-updates-correctness)]
  (println "\nTest" (if result "PASSED ✓" "FAILED ✗")))
