(ns test-duplicate-in-transaction
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]
            [dfdb.subscription :as sub]))

(defn test-duplicate-updates-in-single-tx
  "Test what happens when same entity/attribute updated multiple times in one transaction"
  []
  (let [db (dfdb/create-db {:storage-backend :memory})

        ;; Initial: product 1 has price 60
        _ (dfdb/transact! db [{:db/id 1 :product/price 60}])

        query-map '[:find ?product ?price
                    :where [?product :product/price ?price]
                    [(< ?price 50)]]

        subscription-diffs (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff]
                                                    (swap! subscription-diffs conj diff))
                                        :mode :incremental})

        _ (Thread/sleep 50)
        _ (println "Initial diffs:" @subscription-diffs)
        _ (reset! subscription-diffs [])

        ;; Single transaction with DUPLICATE updates to same product
        ;; Product 1: 60 -> 35 -> 55
        tx-result (dfdb/transact! db [[:db/add 1 :product/price 35]  ; Enters result set
                                      [:db/add 1 :product/price 55]]) ; Exits result set

        _ (println "\nTransaction deltas:")
        _ (doseq [d (:deltas tx-result)]
            (println "  Entity:" (:entity d) "Attr:" (:attribute d)
                     "Old:" (:old-value d) "New:" (:new-value d)))

        _ (dfdb/flush-subscriptions! db {:timeout-ms 5000})
        _ (Thread/sleep 100)

        _ (println "\nSubscription diffs received:")
        _ (doseq [[i diff] (map-indexed vector @subscription-diffs)]
            (println "  Diff" i ":" diff))

        subscription-final
        (reduce
         (fn [state diff]
           (-> state
               (clojure.set/union (:additions diff))
               (clojure.set/difference (:retractions diff))))
         #{}
         @subscription-diffs)

        naive-final (set (query/query db query-map))

        match? (= subscription-final naive-final)]

    (println "\n=== Results ===")
    (println "Subscription final:" subscription-final)
    (println "Naive final:" naive-final)
    (println "Match?" match?)
    (println "Expected: #{} (product 1 ends at price 55, which is >= 50)")

    (sub/unsubscribe subscription)
    match?))

;; Run test
(println "=== Testing Duplicate Updates in Single Transaction ===\n")
(dotimes [i 10]
  (print "Run" (inc i) ": ")
  (if (test-duplicate-updates-in-single-tx)
    (println "PASS ✓")
    (println "FAIL ✗"))
  (println))
