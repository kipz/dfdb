(ns dfdb.performance-smoke-test
  "Smoke test to validate subscription vs naive query results match with small data"
  (:require [clojure.test :refer :all]
            [dfdb.core :as dfdb]
            [dfdb.subscription :as sub]
            [dfdb.query :as query]))

(deftest smoke-test-simple-pattern
  (testing "Simple pattern query: subscriptions match naive queries"
    (let [db (dfdb/create-db {:storage-backend :memory})
          query-map '[:find ?price
                      :where [?product :product/price ?price]
                      [(< ?price 50)]]

          subscription-results (atom [])
          _ (sub/subscribe db {:query query-map
                               :callback (fn [diff]
                                           (swap! subscription-results conj diff))
                               :mode :incremental})

          ;; Initial data
          _ (dfdb/transact! db [[:db/add 1 :product/name "Widget"]
                                [:db/add 1 :product/price 25]])

          ;; More updates
          _ (dfdb/transact! db [[:db/add 2 :product/name "Gadget"]
                                [:db/add 2 :product/price 45]])

          _ (dfdb/transact! db [[:db/add 3 :product/name "Expensive"]
                                [:db/add 3 :product/price 75]])

          _ (dfdb/transact! db [[:db/add 1 :product/price 30]])  ; Update

          ;; Compute subscription final state
          subscription-final (reduce
                              (fn [state diff]
                                (-> state
                                    (clojure.set/union (:additions diff))
                                    (clojure.set/difference (:retractions diff))))
                              #{}
                              @subscription-results)

          ;; Get naive query result
          naive-result (set (query/query db query-map))]

      (is (= subscription-final naive-result)
          (str "Subscription and naive query results should match.\n"
               "Subscription: " subscription-final "\n"
               "Naive: " naive-result)))))

(deftest smoke-test-join
  (testing "Join query: subscriptions match naive queries"
    (let [db (dfdb/create-db {:storage-backend :memory})
          query-map '[:find ?fof
                      :where [?user :friend ?friend]
                      [?friend :friend ?fof]]

          subscription-results (atom [])
          _ (sub/subscribe db {:query query-map
                               :callback (fn [diff]
                                           (swap! subscription-results conj diff))
                               :mode :incremental})

          ;; Create small friend network
          _ (dfdb/transact! db [[:db/add 1 :user/name "Alice"]
                                [:db/add 2 :user/name "Bob"]
                                [:db/add 3 :user/name "Charlie"]
                                [:db/add 1 :friend 2]
                                [:db/add 2 :friend 1]
                                [:db/add 2 :friend 3]
                                [:db/add 3 :friend 2]])

          ;; Add another friendship
          _ (dfdb/transact! db [[:db/add 3 :friend 1]
                                [:db/add 1 :friend 3]])

          ;; Compute subscription final state
          subscription-final (reduce
                              (fn [state diff]
                                (-> state
                                    (clojure.set/union (:additions diff))
                                    (clojure.set/difference (:retractions diff))))
                              #{}
                              @subscription-results)

          ;; Get naive query result
          naive-result (set (query/query db query-map))]

      (is (= subscription-final naive-result)
          (str "Subscription and naive query results should match.\n"
               "Subscription: " subscription-final "\n"
               "Naive: " naive-result)))))

(deftest smoke-test-aggregation
  (testing "Aggregation query: subscriptions match naive queries"
    (let [db (dfdb/create-db {:storage-backend :memory})
          query-map '[:find ?category (sum ?amount)
                      :where [?order :order/category ?category]
                      [?order :order/amount ?amount]]

          subscription-results (atom [])
          _ (sub/subscribe db {:query query-map
                               :callback (fn [diff]
                                           (swap! subscription-results conj diff))
                               :mode :incremental})

          ;; Add orders
          _ (dfdb/transact! db [[:db/add 1 :order/category "books"]
                                [:db/add 1 :order/amount 50]])

          _ (dfdb/transact! db [[:db/add 2 :order/category "books"]
                                [:db/add 2 :order/amount 30]])

          _ (dfdb/transact! db [[:db/add 3 :order/category "electronics"]
                                [:db/add 3 :order/amount 200]])

          ;; Compute subscription final state
          subscription-final (reduce
                              (fn [state diff]
                                (-> state
                                    (clojure.set/union (:additions diff))
                                    (clojure.set/difference (:retractions diff))))
                              #{}
                              @subscription-results)

          ;; Get naive query result
          naive-result (set (query/query db query-map))]

      (is (= subscription-final naive-result)
          (str "Subscription and naive query results should match.\n"
               "Subscription: " subscription-final "\n"
               "Naive: " naive-result)))))

(defn run-smoke-tests []
  (println "\n=== Running Smoke Tests ===\n")
  (smoke-test-simple-pattern)
  (smoke-test-join)
  (smoke-test-aggregation)
  (println "\n=== All Smoke Tests Passed ===\n"))

(comment
  (run-smoke-tests))
