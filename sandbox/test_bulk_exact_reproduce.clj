(ns test-bulk-exact-reproduce
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]
            [dfdb.subscription :as sub]))

(defn generate-ecommerce-products [num-products]
  (for [i (range 1 (inc num-products))]
    {:db/id i
     :product/name (str "Product " i)
     :product/price (+ 10 (rand-int 200))}))

(defn generate-price-update [num-products]
  (let [product (inc (rand-int num-products))
        new-price (+ 10 (rand-int 200))]
    [[:db/add product :product/price new-price]]))

(defn test-exact-reproduction
  "Exactly reproduce the performance test scenario"
  []
  (let [num-products 1000
        num-updates 20

        db (dfdb/create-db {:storage-backend :memory})

        query-map '[:find ?product ?price
                    :where [?product :product/price ?price]
                    [(< ?price 50)]]

        ;; Generate initial data
        initial-data (generate-ecommerce-products num-products)
        _ (dfdb/transact! db initial-data)

        ;; Set up subscription
        subscription-results (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff]
                                                    (swap! subscription-results conj diff))
                                        :mode :incremental})

        _ (Thread/sleep 100)
        _ (println "Initial result count:" (count (:additions (first @subscription-results))))

        ;; Generate updates EXACTLY like the performance test
        updates (doall (repeatedly num-updates
                                   #(vec (mapcat (fn [_] (generate-price-update num-products))
                                                 (range (+ 50 (rand-int 50)))))))

        _ (println "Generated" num-updates "bulk updates")
        _ (println "First update size:" (count (first updates)))

        ;; Run subscription benchmark
        _ (doseq [update updates]
            (dfdb/transact! db update))

        ;; Flush
        flush-result (dfdb/flush-subscriptions! db {:timeout-ms 10000})
        _ (println "Flush result:" flush-result)

        ;; Compute subscription final state
        subscription-final-state
        (reduce
         (fn [state diff]
           (-> state
               (clojure.set/union (:additions diff))
               (clojure.set/difference (:retractions diff))))
         #{}
         @subscription-results)

        ;; Create fresh DB for naive queries
        naive-db (dfdb/create-db {:storage-backend :memory})
        _ (dfdb/transact! naive-db initial-data)
        _ (doseq [update updates]
            (dfdb/transact! naive-db update))

        final-naive-results (query/query naive-db query-map)
        naive-set (set final-naive-results)

        results-match? (= subscription-final-state naive-set)]

    (println "\n=== Results ===")
    (println "Subscription final count:" (count subscription-final-state))
    (println "Naive final count:" (count naive-set))
    (println "Results match:" results-match?)

    (when-not results-match?
      (println "\nSubscription only:" (clojure.set/difference subscription-final-state naive-set))
      (println "Naive only:" (clojure.set/difference naive-set subscription-final-state))

      (println "\nTotal diffs received:" (count @subscription-results))
      (println "Checking for duplicate additions/retractions...")

      (doseq [[i diff] (map-indexed vector @subscription-results)]
        (when (or (> (count (:additions diff)) 50)
                  (> (count (:retractions diff)) 50))
          (println "Diff" i ": additions=" (count (:additions diff)) "retractions=" (count (:retractions diff))))))

    (sub/unsubscribe subscription)
    results-match?))

;; Run test
(println "=== Exact Reproduction of Bulk Updates Test ===\n")
(let [result (test-exact-reproduction)]
  (println "\nTest" (if result "PASSED ✓" "FAILED ✗")))
