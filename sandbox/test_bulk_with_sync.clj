(ns test-bulk-with-sync
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

(defn test-with-better-sync
  []
  (let [num-products 1000
        num-updates 20

        db (dfdb/create-db {:storage-backend :memory})

        query-map '[:find ?product ?price
                    :where [?product :product/price ?price]
                    [(< ?price 50)]]

        initial-data (generate-ecommerce-products num-products)
        _ (dfdb/transact! db initial-data)

        subscription-results (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff]
                                                    (swap! subscription-results conj diff))
                                        :mode :incremental})

        ;; Wait longer for initial processing
        _ (Thread/sleep 200)
        _ (dfdb/flush-subscriptions! db {:timeout-ms 5000})

        initial-count (count (:additions (first @subscription-results)))
        _ (println "Initial result count:" initial-count)

        ;; Generate bulk updates
        updates (doall (repeatedly num-updates
                                   #(vec (mapcat (fn [_] (generate-price-update num-products))
                                                 (range (+ 50 (rand-int 50)))))))

        _ (println "Generated" num-updates "bulk updates, sizes:" (mapv count updates))

        ;; Process each update with explicit flush
        _ (doseq [[idx update] (map-indexed vector updates)]
            (dfdb/transact! db update)
            ;; Flush after EACH transaction with longer timeout
            (let [fr (dfdb/flush-subscriptions! db {:timeout-ms 10000})]
              (when-not (:success? fr)
                (println "WARNING: Flush failed/timed out for update" idx ":" fr))))

        ;; Final flush with very long timeout
        final-flush (dfdb/flush-subscriptions! db {:timeout-ms 15000})
        _ (println "Final flush result:" final-flush)

        ;; Wait a bit more to be absolutely sure
        _ (Thread/sleep 500)

        ;; Compute subscription final state
        subscription-final-state
        (reduce
         (fn [state diff]
           (-> state
               (clojure.set/union (:additions diff))
               (clojure.set/difference (:retractions diff))))
         #{}
         @subscription-results)

        ;; Create fresh DB for naive
        naive-db (dfdb/create-db {:storage-backend :memory})
        _ (dfdb/transact! naive-db initial-data)
        _ (doseq [update updates]
            (dfdb/transact! naive-db update))

        final-naive-results (set (query/query naive-db query-map))

        results-match? (= subscription-final-state final-naive-results)]

    (println "\n=== Results ===")
    (println "Subscription final count:" (count subscription-final-state))
    (println "Naive final count:" (count final-naive-results))
    (println "Results match:" results-match?)
    (println "Total diffs received:" (count @subscription-results))

    (when-not results-match?
      (let [sub-only (clojure.set/difference subscription-final-state final-naive-results)
            naive-only (clojure.set/difference final-naive-results subscription-final-state)]
        (println "\nSubscription only (" (count sub-only) "):" sub-only)
        (println "Naive only (" (count naive-only) "):" naive-only)))

    (sub/unsubscribe subscription)
    results-match?))

;; Run multiple times
(println "=== Testing with Better Synchronization ===\n")
(let [results (for [i (range 10)]
                (do
                  (print "Run" (inc i) "... ")
                  (flush)
                  (let [r (test-with-better-sync)]
                    (println (if r "PASS ✓" "FAIL ✗"))
                    r)))]
  (println "\nPassed:" (count (filter identity results)) "/ 10")
  (println "Flake rate:" (format "%.1f%%" (* 100.0 (/ (count (remove identity results)) 10)))))
