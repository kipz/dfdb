(ns debug-bulk-updates
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]
            [dfdb.subscription :as sub]))

(defn test-large-bulk-update
  "Reproduce the bulk updates issue"
  []
  (let [db (dfdb/create-db {:storage-backend :memory})

        ;; Initial: 1000 products with random prices
        initial-data (for [i (range 1 1001)]
                       {:db/id i
                        :product/name (str "Product " i)
                        :product/price (+ 10 (rand-int 200))})

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

        _ (Thread/sleep 100)
        initial-count (count (:additions (first @subscription-diffs)))
        _ (println "Initial products with price < 50:" initial-count)

        ;; Generate a LARGE bulk update (60 operations)
        bulk-update (vec (mapcat (fn [_]
                                   (let [product (inc (rand-int 1000))
                                         new-price (+ 10 (rand-int 200))]
                                     [[:db/add product :product/price new-price]]))
                                 (range 60)))

        _ (println "\nGenerating bulk update with" (count bulk-update) "operations")
        _ (dfdb/transact! db bulk-update)

        ;; Wait for subscription processing with longer timeout
        flush-result (dfdb/flush-subscriptions! db {:timeout-ms 10000})
        _ (println "Flush result:" flush-result)

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

        match? (= subscription-final naive-final)
        sub-count (count subscription-final)
        naive-count (count naive-final)]

    (println "\n=== Results ===")
    (println "Subscription final count:" sub-count)
    (println "Naive final count:" naive-count)
    (println "Match?" match?)
    (println "Difference:" (Math/abs (- sub-count naive-count)))

    (when-not match?
      (let [sub-only (clojure.set/difference subscription-final naive-final)
            naive-only (clojure.set/difference naive-final subscription-final)]
        (println "\nSubscription only (" (count sub-only) " results):")
        (doseq [r (take 10 sub-only)]
          (println "  " r))
        (println "\nNaive only (" (count naive-only) " results):")
        (doseq [r (take 10 naive-only)]
          (println "  " r))

        ;; Check if any of the differing products were in the bulk update
        (let [updated-products (set (map second bulk-update))
              sub-only-products (set (map first sub-only))
              naive-only-products (set (map first naive-only))]
          (println "\nUpdated products in bulk update:" (count updated-products))
          (println "Sub-only products in updated set:" (clojure.set/intersection sub-only-products updated-products))
          (println "Naive-only products in updated set:" (clojure.set/intersection naive-only-products updated-products)))))

    (println "\nAll diffs received:" (count @subscription-diffs))
    (doseq [[i diff] (map-indexed vector @subscription-diffs)]
      (println "Diff" i ": additions=" (count (:additions diff)) "retractions=" (count (:retractions diff))))

    (sub/unsubscribe subscription)
    match?))

;; Run the test multiple times to check for flakiness
(println "=== Testing Bulk Updates (multiple runs) ===\n")
(let [results (doall (for [run (range 5)]
                       (do
                         (println "Run" (inc run) "...")
                         (test-large-bulk-update)
                         (println))))]
  (println "\n=== Summary ===")
  (println "Runs passed:" (count (filter identity results)) "/" (count results))
  (println "All passed?" (every? identity results)))
