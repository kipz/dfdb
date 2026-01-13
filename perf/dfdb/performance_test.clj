(ns dfdb.performance-test
  (:require [clojure.test :refer :all]
            [dfdb.core :as dfdb]
            [dfdb.subscription :as sub]
            [dfdb.query :as query]
            [clojure.pprint :as pprint]))

;; ============================================================================
;; Timing & Statistics Utilities
;; ============================================================================

(defn nanos->millis [nanos]
  (/ nanos 1e6))

(defn time-fn
  "Time the execution of a function, return [result time-in-nanos]"
  [f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)]
    [result (- end start)]))

(defn mean [coll]
  (if (empty? coll)
    0
    (/ (reduce + coll) (count coll))))

(defn percentile [coll p]
  (if (empty? coll)
    0
    (let [sorted (sort coll)
          idx (int (* (/ p 100.0) (count sorted)))]
      (nth sorted (min idx (dec (count sorted)))))))

(defn stats [times]
  {:mean (mean times)
   :p50 (percentile times 50)
   :p95 (percentile times 95)
   :p99 (percentile times 99)
   :min (apply min times)
   :max (apply max times)})

(defn format-stats [stats-map]
  (format "mean: %.2fms, p50: %.2fms, p95: %.2fms, p99: %.2fms, min: %.2fms, max: %.2fms"
          (nanos->millis (:mean stats-map))
          (nanos->millis (:p50 stats-map))
          (nanos->millis (:p95 stats-map))
          (nanos->millis (:p99 stats-map))
          (nanos->millis (:min stats-map))
          (nanos->millis (:max stats-map))))

(defn memory-usage-mb []
  (let [runtime (Runtime/getRuntime)
        total (.totalMemory runtime)
        free (.freeMemory runtime)]
    (/ (- total free) 1024.0 1024.0)))

;; ============================================================================
;; Data Generators
;; ============================================================================

(defn generate-social-network
  "Generate a social network with users and friend relationships"
  [num-users avg-friends-per-user]
  (let [users (range 1 (inc num-users))]
    (concat
      ;; Create users
     (for [user users]
       [:db/add user :user/name (str "User-" user)])
      ;; Create friendships (bidirectional)
     (mapcat
      (fn [user]
        (let [num-friends (+ (rand-int (* 2 avg-friends-per-user)) 1)
              friends (take num-friends (shuffle (remove #{user} users)))]
          (mapcat
           (fn [friend]
             [[:db/add user :friend friend]
              [:db/add friend :friend user]])
           friends)))
      (take (/ num-users 2) users))))) ;; Only half to avoid duplicates

(defn generate-ecommerce-products
  "Generate products with prices and inventory"
  [num-products]
  (for [id (range 1 (inc num-products))]
    {:db/id id
     :product/name (str "Product-" id)
     :product/price (+ 10 (rand-int 200))
     :inventory/quantity (rand-int 100)}))

(defn generate-ecommerce-orders
  "Generate orders for products"
  [num-orders num-products]
  (let [categories ["electronics" "books" "clothing" "home" "sports"]]
    (concat
      ;; Add categories to products
     (for [id (range 1 (inc num-products))]
       [:db/add id :product/category (rand-nth categories)])
      ;; Create orders (using map notation for multiple attributes)
     (for [order-id (range 1 (inc num-orders))]
       {:db/id (+ num-products order-id)
        :order/product (inc (rand-int num-products))
        :order/amount (+ 10 (rand-int 500))}))))

(defn generate-active-sessions
  "Generate user sessions"
  [num-sessions num-users]
  (for [session-id (range 1 (inc num-sessions))]
    {:db/id session-id
     :session/user (inc (rand-int num-users))
     :session/active true
     :session/timestamp (System/currentTimeMillis)}))

;; ============================================================================
;; Update Generators
;; ============================================================================

(defn generate-friendship-update [num-users]
  (let [user1 (inc (rand-int num-users))
        user2 (inc (rand-int num-users))]
    (if (= user1 user2)
      (generate-friendship-update num-users)
      [[:db/add user1 :friend user2]
       [:db/add user2 :friend user1]])))

(defn generate-price-update [num-products]
  (let [product (inc (rand-int num-products))
        new-price (+ 10 (rand-int 200))]
    [[:db/add product :product/price new-price]]))

(defn generate-inventory-update [num-products]
  (let [product (inc (rand-int num-products))
        new-qty (rand-int 100)]
    [[:db/add product :inventory/quantity new-qty]]))

(defn generate-order-update [num-products next-order-id]
  [{:db/id next-order-id
    :order/product (inc (rand-int num-products))
    :order/amount (+ 10 (rand-int 500))}])

(defn generate-session-update [num-sessions]
  (let [session-id (inc (rand-int num-sessions))
        active? (> (rand) 0.3)] ;; 70% active
    [{:db/id session-id
      :session/active active?
      :session/timestamp (System/currentTimeMillis)}]))

;; ============================================================================
;; Benchmark Harness
;; ============================================================================

(defn warmup-jvm
  "Warm up the JVM with some queries"
  [db query-map num-warmup]
  (dotimes [_ num-warmup]
    (query/query db query-map)))

(defn results->set
  "Convert query results to a set for comparison"
  [results]
  (set results))

(defn run-benchmark
  "Run a comprehensive performance comparison"
  [{:keys [scenario-name
           db
           query-map
           initial-data-fn
           update-generator-fn
           num-updates
           warmup-updates
           scale-description]
    :or {warmup-updates 2}}]

  (println "\n" (apply str (repeat 80 "=")))
  (println "Scenario:" scenario-name)
  (println "Scale:" scale-description)
  (println "Updates:" num-updates)
  (println (apply str (repeat 80 "=")))

  ;; Setup initial data
  (println "\nSetting up initial data...")
  (let [initial-data (initial-data-fn)
        _ (dfdb/transact! db initial-data)
        _ (println "Initial data loaded")
        _ (println "Initial memory usage:" (format "%.2f MB" (memory-usage-mb)))

        ;; Warmup
        _ (println "\nWarming up JVM...")
        _ (warmup-jvm db query-map warmup-updates)

        ;; === Subscription Setup ===
        _ (println "\n--- Subscription Setup ---")

        subscription-results (atom [])
        subscription-errors (atom [])

        ;; Measure compilation time
        [subscription compilation-time]
        (time-fn
         (fn []
           (try
             (sub/subscribe db {:query query-map
                                :callback (fn [diff]
                                            (swap! subscription-results conj diff))
                                :mode :incremental})
             (catch Exception e
               (swap! subscription-errors conj e)
               nil))))

        _ (println "Compilation time:" (format "%.2f ms" (nanos->millis compilation-time)))

        ;; Check if subscription compiled successfully
        subscription-failed? (not (nil? (first @subscription-errors)))]

    (if subscription-failed?
      (do
        (println "ERROR: Subscription compilation failed!")
        (println "Error:" (first @subscription-errors))
        {:error "Subscription compilation failed"
         :exception (first @subscription-errors)})

      (do
        (println "Subscription active. Initial results delivered.")
        (println "Initial result count:" (count (:additions (first @subscription-results))))

        ;; Generate all updates
        (println "\n--- Generating Updates ---")
        (let [updates (doall (repeatedly num-updates update-generator-fn))
              _ (println "Generated" num-updates "update batches")

              ;; Take memory snapshot before subscription benchmark
              mem-before-sub (memory-usage-mb)

              ;; === Subscription Benchmark ===
              _ (println "\n--- Subscription Benchmark ---")
              sub-times (doall
                         (for [update updates]
                           (let [[_ elapsed] (time-fn #(dfdb/transact! db update))]
                             elapsed)))

              mem-after-sub (memory-usage-mb)
              sub-stats (stats sub-times)
              sub-total-ms (nanos->millis (reduce + sub-times))

              _ (println "Completed" num-updates "updates via subscription")
              _ (println "Stats:" (format-stats sub-stats))
              _ (println "Total time:" (format "%.2f ms" sub-total-ms))
              _ (println "Throughput:" (format "%.2f updates/sec" (/ num-updates (/ sub-total-ms 1000.0))))
              _ (println "Memory delta:" (format "%.2f MB" (- mem-after-sub mem-before-sub)))

              ;; Collect subscription results for validation
              final-sub-results (last @subscription-results)

              ;; === Create fresh DB for naive queries ===
              _ (println "\n--- Naive Query Benchmark ---")
              naive-db (dfdb/create-db (select-keys db [:storage-backend]))
              _ (dfdb/transact! naive-db initial-data)

              mem-before-naive (memory-usage-mb)

              ;; Naive query: transact + query after each update
              naive-times (doall
                           (for [update updates]
                             (do
                               (dfdb/transact! naive-db update)
                               (let [[_ elapsed] (time-fn #(query/query naive-db query-map))]
                                 elapsed))))

              mem-after-naive (memory-usage-mb)
              naive-stats (stats naive-times)
              naive-total-ms (nanos->millis (reduce + naive-times))

              _ (println "Completed" num-updates "updates with naive queries")
              _ (println "Stats:" (format-stats naive-stats))
              _ (println "Total time:" (format "%.2f ms" naive-total-ms))
              _ (println "Throughput:" (format "%.2f updates/sec" (/ num-updates (/ naive-total-ms 1000.0))))
              _ (println "Memory delta:" (format "%.2f MB" (- mem-after-naive mem-before-naive)))

              ;; Final validation - do results match?
              final-naive-results (query/query naive-db query-map)

              ;; Compute subscription final state by applying all deltas
              subscription-final-state
              (reduce
               (fn [state diff]
                 (-> state
                     (clojure.set/union (:additions diff))
                     (clojure.set/difference (:retractions diff))))
               #{}
               @subscription-results)

              naive-set (results->set final-naive-results)

              results-match? (= subscription-final-state naive-set)

              _ (println "\n--- Results Validation ---")
              _ (println "Subscription final result count:" (count subscription-final-state))
              _ (println "Naive query final result count:" (count naive-set))
              _ (println "Results match:" results-match?)

              _ (when-not results-match?
                  (println "ERROR: Results do not match!")
                  (println "Subscription only:" (clojure.set/difference subscription-final-state naive-set))
                  (println "Naive only:" (clojure.set/difference naive-set subscription-final-state)))

              ;; Calculate speedup
              speedup (/ (nanos->millis (:mean naive-stats))
                         (nanos->millis (:mean sub-stats)))

              _ (println "\n--- Summary ---")
              _ (println (format "Speedup: %.1fx" speedup))
              _ (println (format "Subscription avg latency: %.2f ms" (nanos->millis (:mean sub-stats))))
              _ (println (format "Naive query avg latency: %.2f ms" (nanos->millis (:mean naive-stats))))]

          ;; Return results map
          {:scenario scenario-name
           :scale scale-description
           :num-updates num-updates
           :compilation-time-ms (nanos->millis compilation-time)
           :subscription-stats sub-stats
           :naive-stats naive-stats
           :subscription-total-ms sub-total-ms
           :naive-total-ms naive-total-ms
           :speedup speedup
           :subscription-mem-delta-mb (- mem-after-sub mem-before-sub)
           :naive-mem-delta-mb (- mem-after-naive mem-before-naive)
           :results-match? results-match?
           :subscription-result-count (count subscription-final-state)
           :naive-result-count (count naive-set)})))))

;; ============================================================================
;; Scenario Definitions
;; ============================================================================

(deftest scenario-1-social-network
  (testing "Social Network: Friend Recommendations (Friends of Friends)"
    (let [num-users 500
          avg-friends 8
          num-updates 50

          db (dfdb/create-db {:storage-backend :memory})

          query-map '[:find ?fof
                      :where [?user :friend ?friend]
                      [?friend :friend ?fof]]

          results (run-benchmark
                   {:scenario-name "Social Network - Friend Recommendations"
                    :db db
                    :query-map query-map
                    :initial-data-fn #(generate-social-network num-users avg-friends)
                    :update-generator-fn #(generate-friendship-update num-users)
                    :num-updates num-updates
                    :scale-description (format "%d users, avg %d friends" num-users avg-friends)})]

      (is (:results-match? results) "Subscription results should match naive queries")
      (is (> (:speedup results) 1.0) "Subscriptions should be faster for multi-join queries"))))

(deftest scenario-2-ecommerce-inventory
  (testing "E-commerce: Products in Stock Under $50"
    (let [num-products 5000
          num-updates 100

          db (dfdb/create-db {:storage-backend :memory})

          query-map '[:find ?product ?price
                      :where [?product :inventory/quantity ?qty]
                      [?product :product/price ?price]
                      [(> ?qty 0)]
                      [(< ?price 50)]]

          results (run-benchmark
                   {:scenario-name "E-commerce - Low Price In-Stock Products"
                    :db db
                    :query-map query-map
                    :initial-data-fn #(generate-ecommerce-products num-products)
                    :update-generator-fn #(if (> (rand) 0.5)
                                            (generate-price-update num-products)
                                            (generate-inventory-update num-products))
                    :num-updates num-updates
                    :scale-description (format "%d products" num-products)})]

      (is (:results-match? results) "Subscription results should match naive queries")
      (is (> (:speedup results) 1.0) "Subscriptions should be faster for predicate-heavy queries"))))

(deftest scenario-3-analytics-aggregation
  (testing "Analytics: Total Sales by Category"
    (let [num-products 1000
          num-orders 5000
          num-updates 50

          db (dfdb/create-db {:storage-backend :memory})

          query-map '[:find ?category (sum ?amount)
                      :where [?order :order/product ?product]
                      [?product :product/category ?category]
                      [?order :order/amount ?amount]]

          ;; Counter for generating unique order IDs
          next-order-id (atom (+ num-products num-orders 1))

          results (run-benchmark
                   {:scenario-name "Analytics - Sales by Category"
                    :db db
                    :query-map query-map
                    :initial-data-fn #(concat
                                       (generate-ecommerce-products num-products)
                                       (generate-ecommerce-orders num-orders num-products))
                    :update-generator-fn #(let [order-id @next-order-id]
                                            (swap! next-order-id inc)
                                            (generate-order-update num-products order-id))
                    :num-updates num-updates
                    :scale-description (format "%d products, %d initial orders" num-products num-orders)})]

      (is (:results-match? results) "Subscription results should match naive queries")
      (is (> (:speedup results) 1.0) "Subscriptions should be faster for aggregations"))))

(deftest scenario-4-high-churn-sessions
  (testing "High-Churn: Active User Sessions"
    (let [num-users 500
          num-sessions 2000
          num-updates 200

          db (dfdb/create-db {:storage-backend :memory})

          query-map '[:find ?user ?session
                      :where [?session :session/user ?user]
                      [?session :session/active true]]

          results (run-benchmark
                   {:scenario-name "High-Churn - Active Sessions"
                    :db db
                    :query-map query-map
                    :initial-data-fn #(generate-active-sessions num-sessions num-users)
                    :update-generator-fn #(generate-session-update num-sessions)
                    :num-updates num-updates
                    :scale-description (format "%d users, %d sessions" num-users num-sessions)})]

      (is (:results-match? results) "Subscription results should match naive queries")
      (is (> (:speedup results) 1.0) "Subscriptions should be faster for high-churn workloads"))))

;; ============================================================================
;; Scale Variation Tests
;; ============================================================================

(deftest scenario-1-scale-small
  (testing "Social Network: Small Scale (100 users)"
    (let [num-users 100
          avg-friends 5
          num-updates 50

          db (dfdb/create-db {:storage-backend :memory})

          query-map '[:find ?fof
                      :where [?user :friend ?friend]
                      [?friend :friend ?fof]]

          results (run-benchmark
                   {:scenario-name "Social Network - Small Scale"
                    :db db
                    :query-map query-map
                    :initial-data-fn #(generate-social-network num-users avg-friends)
                    :update-generator-fn #(generate-friendship-update num-users)
                    :num-updates num-updates
                    :scale-description (format "%d users, avg %d friends" num-users avg-friends)})]

      (is (:results-match? results)))))

(deftest scenario-1-scale-large
  (testing "Social Network: Large Scale (5000 users)"
    (let [num-users 5000
          avg-friends 20
          num-updates 100

          db (dfdb/create-db {:storage-backend :memory})

          query-map '[:find ?fof
                      :where [?user :friend ?friend]
                      [?friend :friend ?fof]]

          results (run-benchmark
                   {:scenario-name "Social Network - Large Scale"
                    :db db
                    :query-map query-map
                    :initial-data-fn #(generate-social-network num-users avg-friends)
                    :update-generator-fn #(generate-friendship-update num-users)
                    :num-updates num-updates
                    :scale-description (format "%d users, avg %d friends" num-users avg-friends)})]

      (is (:results-match? results)))))

;; ============================================================================
;; Batch Update Tests
;; ============================================================================

(deftest scenario-batch-micro-updates
  (testing "Batch Size: Micro Updates (1-5 datoms)"
    (let [num-products 1000
          num-updates 50

          db (dfdb/create-db {:storage-backend :memory})

          query-map '[:find ?product ?price
                      :where [?product :product/price ?price]
                      [(< ?price 50)]]

          results (run-benchmark
                   {:scenario-name "Micro Updates (1-5 datoms)"
                    :db db
                    :query-map query-map
                    :initial-data-fn #(generate-ecommerce-products num-products)
                    :update-generator-fn #(generate-price-update num-products)
                    :num-updates num-updates
                    :scale-description (format "%d products, micro updates" num-products)})]

      (is (:results-match? results)))))

(deftest scenario-batch-bulk-updates
  (testing "Batch Size: Bulk Updates (50-100 datoms)"
    (let [num-products 1000
          num-updates 20

          db (dfdb/create-db {:storage-backend :memory})

          query-map '[:find ?product ?price
                      :where [?product :product/price ?price]
                      [(< ?price 50)]]

          results (run-benchmark
                   {:scenario-name "Bulk Updates (50-100 datoms)"
                    :db db
                    :query-map query-map
                    :initial-data-fn #(generate-ecommerce-products num-products)
                    :update-generator-fn #(vec (mapcat (fn [_] (generate-price-update num-products))
                                                       (range (+ 50 (rand-int 50)))))
                    :num-updates num-updates
                    :scale-description (format "%d products, bulk updates" num-products)})]

      (is (:results-match? results)))))

;; ============================================================================
;; Run All Benchmarks
;; ============================================================================

(defn run-all-benchmarks
  "Run all benchmarks and generate a summary report"
  []
  (println "\n\n")
  (println (apply str (repeat 80 "#")))
  (println "# DFDB PERFORMANCE BENCHMARKS: SUBSCRIPTIONS vs NAIVE QUERIES")
  (println (apply str (repeat 80 "#")))
  (println)

  (let [test-results [(scenario-1-social-network)
                      (scenario-2-ecommerce-inventory)
                      (scenario-3-analytics-aggregation)
                      (scenario-4-high-churn-sessions)
                      (scenario-1-scale-small)
                      (scenario-1-scale-large)
                      (scenario-batch-micro-updates)
                      (scenario-batch-bulk-updates)]]

    (println "\n\n")
    (println (apply str (repeat 80 "=")))
    (println "BENCHMARK SUITE COMPLETE")
    (println (apply str (repeat 80 "=")))

    test-results))

(comment
  ;; Run all benchmarks from REPL
  (run-all-benchmarks)

  ;; Run individual scenarios
  (scenario-1-social-network)
  (scenario-2-ecommerce-inventory)
  (scenario-3-analytics-aggregation)
  (scenario-4-high-churn-sessions))
