(ns dfdb.generative-stress-test
  "Generative stress tests for query and differential dataflow engine.
   Uses property-based testing to generate random transactions and queries,
   testing invariants and edge cases at scale."
  (:require [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dfdb.core :refer :all]
            [clojure.set :as set]))

;;; ===========================================================================
;;; Generators
;;; ===========================================================================

(def entity-id-gen
  "Generate entity IDs from a small pool for interesting overlaps."
  (gen/choose 1 20))

(def attribute-gen
  "Generate attributes from a predefined pool."
  (gen/elements [:user/name :user/email :user/age :user/active?
                 :product/name :product/price :product/quantity
                 :order/id :order/total :order/customer :order/status
                 :emp/id :emp/name :emp/reports-to
                 :post/title :post/content :post/author
                 :tag/name :post/tags]))

(def string-value-gen
  "Generate string values."
  (gen/such-that not-empty gen/string-alphanumeric))

(def number-value-gen
  "Generate number values."
  (gen/choose 1 10000))

(def boolean-value-gen
  "Generate boolean values."
  gen/boolean)

(def value-gen
  "Generate values of various types."
  (gen/one-of [string-value-gen number-value-gen boolean-value-gen]))

(defn datom-gen
  "Generate a single datom [entity-id attribute value]."
  []
  (gen/tuple entity-id-gen attribute-gen value-gen))

(defn transaction-gen
  "Generate a transaction (list of datoms as maps)."
  [max-datoms]
  (gen/vector
   (gen/let [[e a v] (datom-gen)]
     {a v
      :db/id e})
   1 max-datoms))

(defn retraction-gen
  "Generate retraction operations from existing datoms."
  [datoms]
  (when (seq datoms)
    (gen/let [[e a v] (gen/elements datoms)]
      [:db/retract e a v])))

(defn mixed-transaction-gen
  "Generate a mix of additions and retractions."
  [existing-datoms max-ops]
  (gen/vector
   (gen/frequency
    [[7 (gen/let [[e a v] (datom-gen)]
          {a v :db/id e})]
     [3 (if (seq existing-datoms)
          (retraction-gen existing-datoms)
          (gen/let [[e a v] (datom-gen)]
            {a v :db/id e}))]])
   1 max-ops))

(def variable-gen
  "Generate query variables."
  (gen/elements ['?e '?e1 '?e2 '?name '?age '?price '?total '?customer '?author]))

(defn pattern-gen
  "Generate a query pattern [?e :attr ?val]."
  []
  (gen/let [entity-var variable-gen
            attr attribute-gen
            value-var variable-gen]
    [entity-var attr value-var]))

(defn simple-query-gen
  "Generate a simple :find/:where query."
  [max-patterns]
  (gen/let [patterns (gen/vector (pattern-gen) 1 max-patterns)
            vars (gen/not-empty (gen/vector variable-gen 1 3))]
    {:find vars
     :where patterns}))

;;; ===========================================================================
;;; Test Helpers
;;; ===========================================================================

(defn extract-datoms
  "Extract datoms from transaction data."
  [tx-data]
  (mapcat (fn [item]
            (if (map? item)
              (let [e (:db/id item)]
                (for [[k v] (dissoc item :db/id)]
                  [e k v]))
              [(rest item)]))
          tx-data))

(defn apply-transactions!
  "Apply a sequence of transactions to a database."
  [db transactions]
  (doseq [tx transactions]
    (transact! db tx))
  db)

(defn query-result-set
  "Execute a query and return results as a set."
  [db query-map]
  (try
    (set (query db query-map))
    (catch Exception e
      #{}))) ; Return empty set on query errors

(defn subscription-final-state
  "Create subscription, apply transactions, return final state."
  [db query-vec transactions]
  (let [updates (atom [])
        _ (subscribe db {:query query-vec
                         :callback (fn [diff] (swap! updates conj diff))})
        _ (doseq [tx transactions]
            (transact! db tx))
        final-adds (reduce set/union #{} (map :additions @updates))
        final-retracts (reduce set/union #{} (map :retractions @updates))]
    (set/difference final-adds final-retracts)))

;;; ===========================================================================
;;; Property Tests
;;; ===========================================================================

(defspec subscription-matches-query-test
  30 ; num-tests
  (prop/for-all [transactions (gen/vector (transaction-gen 5) 1 10)]
                (let [db1 (create-db)
                      db2 (create-db)

          ;; Apply transactions to db2 for direct query
                      _ (apply-transactions! db2 transactions)

          ;; Query for all entities and attributes
                      query-vec [:find '?e '?a '?v :where ['?e '?a '?v]]

          ;; Get results both ways (subscription-final-state applies transactions to db1)
                      sub-results (subscription-final-state db1 query-vec transactions)
                      query-results (query-result-set db2 query-vec)]

      ;; Results should match
                  (= sub-results query-results))))

(defspec add-then-retract-is-empty-test
  50 ; num-tests
  (prop/for-all [datoms (gen/vector (datom-gen) 1 20)]
                (let [db (create-db)

          ;; Group datoms by [e a] to get final values only
                      datom-map (into {} (map (fn [[e a v]] [[e a] v]) datoms))
                      unique-datoms (map (fn [[[e a] v]] [e a v]) datom-map)

          ;; Add all unique datoms
                      add-tx (map (fn [[e a v]] {:db/id e a v}) unique-datoms)
                      _ (transact! db add-tx)

          ;; Retract all unique datoms with correct values
                      retract-tx (map (fn [[e a v]] [:db/retract e a v]) unique-datoms)
                      _ (transact! db retract-tx)

          ;; Query for any data - should be empty
                      results (query db [:find '?e '?a '?v :where ['?e '?a '?v]])]

                  (empty? results))))

(defspec high-churn-correctness-test
  30 ; num-tests
  (prop/for-all [churn-ops (gen/vector
                            (gen/frequency
                             [[5 (gen/let [[e a v] (datom-gen)]
                                   {:op :add :data {:db/id e a v}})]
                              [3 (gen/let [[e a] (gen/tuple entity-id-gen attribute-gen)]
                                   {:op :retract :entity e :attr a})]])
                            10 100)]
                (let [db (create-db)

          ;; Track what should be in DB
                      expected-state (atom {})

          ;; Apply operations
                      _ (doseq [op churn-ops]
                          (case (:op op)
                            :add (let [data (:data op)
                                       e (:db/id data)
                                       [a v] (first (dissoc data :db/id))]
                                   (transact! db [data])
                                   (swap! expected-state assoc-in [e a] v))

                            :retract (let [e (:entity op)
                                           a (:attr op)
                                           current-val (get-in @expected-state [e a])]
                                       (when current-val
                                         (transact! db [[:db/retract e a current-val]])
                                         (swap! expected-state update e dissoc a)))))

          ;; Verify final state matches
                      actual-entities (into {}
                                            (for [[e attrs] @expected-state
                                                  :when (seq attrs)]
                                              [e (entity db e)]))

                      expected-entities (into {}
                                              (for [[e attrs] @expected-state
                                                    :when (seq attrs)]
                                                [e attrs]))]

      ;; Check each expected entity
                  (every? (fn [[e expected-attrs]]
                            (let [actual-ent (get actual-entities e)]
                              (every? (fn [[a v]]
                                        (= v (get actual-ent a)))
                                      expected-attrs)))
                          expected-entities))))

(defspec query-result-size-bounded-test
  50 ; num-tests
  (prop/for-all [transactions (gen/vector (transaction-gen 5) 1 10)]
                (let [db (create-db)
                      _ (apply-transactions! db transactions)

          ;; Count total datoms added
                      total-datoms (count (mapcat extract-datoms transactions))

          ;; Query for all entities with any attribute
                      results (query db [:find '?e '?a '?v :where ['?e '?a '?v]])

                      result-count (count results)]

      ;; Result count should not exceed total datoms added
                  (<= result-count total-datoms))))

(defspec concurrent-subscriptions-consistency-test
  30 ; num-tests
  (prop/for-all [transactions (gen/vector (transaction-gen 3) 1 5)]
                (let [db (create-db)
                      updates1 (atom [])
                      updates2 (atom [])
                      updates3 (atom [])

                      query-vec [:find '?name :where ['?e :user/name '?name]]

          ;; Create three subscriptions
                      _ (subscribe db {:query query-vec
                                       :callback (fn [diff] (swap! updates1 conj diff))})
                      _ (subscribe db {:query query-vec
                                       :callback (fn [diff] (swap! updates2 conj diff))})
                      _ (subscribe db {:query query-vec
                                       :callback (fn [diff] (swap! updates3 conj diff))})

          ;; Apply transactions
                      _ (doseq [tx transactions]
                          (transact! db tx))]

      ;; All subscriptions should receive identical updates
                  (and (= @updates1 @updates2)
                       (= @updates2 @updates3)))))

;;; ===========================================================================
;;; Stress Tests (specific scenarios)
;;; ===========================================================================

(deftest stress-test-large-dataset
  (testing "Handling large dataset with many entities and attributes"
    (let [db (create-db)

          ;; Create 1000 users with multiple attributes
          users (for [i (range 1 1001)]
                  {:db/id i
                   :user/name (str "User-" i)
                   :user/email (str "user" i "@example.com")
                   :user/age (+ 18 (mod i 50))
                   :user/status "active"}) ; Use string status instead of boolean

          _ (transact! db users)

          ;; Query all users with active status
          results (query db [:find '?e '?name
                             :where ['?e :user/name '?name]
                             ['?e :user/status "active"]])

          expected-count 1000] ; All should have active status

      (is (= expected-count (count results))
          "Should find all active users in large dataset"))))

(deftest stress-test-deep-joins
  (testing "Deep joins across multiple patterns"
    (let [db (create-db)

          ;; Create hierarchical data: posts, authors, tags
          _ (transact! db [{:db/id 1 :user/name "Alice" :user/email "alice@example.com"}
                           {:db/id 2 :user/name "Bob" :user/email "bob@example.com"}

                           {:db/id 101 :post/title "Post 1" :post/author 1}
                           {:db/id 102 :post/title "Post 2" :post/author 1}
                           {:db/id 103 :post/title "Post 3" :post/author 2}

                           {:db/id 201 :tag/name "clojure" :post/tags 101}
                           {:db/id 202 :tag/name "testing" :post/tags 101}
                           {:db/id 203 :tag/name "clojure" :post/tags 102}])

          ;; Deep join: find author names for posts with "clojure" tag
          results (query db [:find '?author-name '?post-title
                             :where ['?tag :tag/name "clojure"]
                             ['?tag :post/tags '?post]
                             ['?post :post/title '?post-title]
                             ['?post :post/author '?author]
                             ['?author :user/name '?author-name]])]

      (is (= 2 (count results))
          "Should join through tags -> posts -> authors")
      (is (every? #(= "Alice" (first %)) results)
          "All posts with clojure tag are by Alice"))))

(deftest stress-test-aggregation-correctness
  (testing "Aggregation correctness with sum, count, min, max, avg"
    (let [db (create-db)

          ;; Create orders for different customers
          _ (transact! db [{:db/id 1 :order/customer "Alice" :order/total 100}
                           {:db/id 2 :order/customer "Alice" :order/total 200}
                           {:db/id 3 :order/customer "Alice" :order/total 150}
                           {:db/id 4 :order/customer "Bob" :order/total 500}
                           {:db/id 5 :order/customer "Bob" :order/total 300}])

          ;; Test sum aggregation
          sum-results (query db [:find '?customer '(sum ?total)
                                 :where ['?order :order/customer '?customer]
                                 ['?order :order/total '?total]])

          alice-sum (some #(when (= "Alice" (first %)) (second %)) sum-results)
          bob-sum (some #(when (= "Bob" (first %)) (second %)) sum-results)]

      (is (= 450 alice-sum) "Alice's order total should be 450")
      (is (= 800 bob-sum) "Bob's order total should be 800")

      ;; Test count aggregation
      (let [count-results (query db [:find '?customer '(count ?order)
                                     :where ['?order :order/customer '?customer]])
            alice-count (some #(when (= "Alice" (first %)) (second %)) count-results)
            bob-count (some #(when (= "Bob" (first %)) (second %)) count-results)]

        (is (= 3 alice-count) "Alice should have 3 orders")
        (is (= 2 bob-count) "Bob should have 2 orders")))))

(deftest stress-test-subscription-with-rapid-updates
  (testing "Subscription handles rapid consecutive updates correctly"
    (let [db (create-db)
          updates (atom [])

          _ (subscribe db {:query [:find '?name :where ['?e :user/name '?name]]
                           :callback (fn [diff] (swap! updates conj diff))})

          ;; Rapidly add 100 users
          _ (doseq [i (range 100)]
              (transact! db [{:db/id i :user/name (str "User-" i)}]))

          total-additions (reduce + (map #(count (:additions %)) @updates))
          total-retractions (reduce + (map #(count (:retractions %)) @updates))]

      (is (= 100 total-additions) "Should have 100 total additions")
      (is (= 0 total-retractions) "Should have no retractions"))))

(deftest stress-test-complex-predicate-filters
  (testing "Complex predicate combinations"
    (let [db (create-db)

          ;; Create products with various prices
          _ (transact! db (for [i (range 1 51)]
                            {:db/id i
                             :product/name (str "Product-" i)
                             :product/price (* i 10)
                             :product/quantity i}))

          ;; Find products where price > 200 AND price < 400 AND quantity > 15
          results (query db [:find '?name '?price '?qty
                             :where ['?p :product/name '?name]
                             ['?p :product/price '?price]
                             ['?p :product/quantity '?qty]
                             ['(> ?price 200)]
                             ['(< ?price 400)]
                             ['(> ?qty 15)]])]

      (is (every? (fn [[_ price qty]]
                    (and (> price 200) (< price 400) (> qty 15)))
                  results)
          "All results should satisfy the predicates")

      ;; Expected: products 21-39 (price 210-390, qty 21-39)
      (is (= 19 (count results)) "Should find 19 matching products"))))

(deftest stress-test-retraction-cascade
  (testing "Retractions cascade correctly through subscriptions"
    (let [db (create-db)
          updates (atom [])]

      ;; Subscribe to join query
      (subscribe db {:query [:find '?name '?age
                             :where ['?e :user/name '?name]
                             ['?e :user/age '?age]]
                     :callback (fn [diff] (swap! updates conj diff))})

      ;; Add user with name and age
      (transact! db [{:db/id 1 :user/name "Alice" :user/age 30}])

      ;; Should have addition
      (is (contains? (:additions (last @updates)) ["Alice" 30]))

      ;; Retract age - join should disappear
      (transact! db [[:db/retract 1 :user/age 30]])

      ;; Should have retraction
      (is (contains? (:retractions (last @updates)) ["Alice" 30]))

      ;; Add age back - join should reappear
      (transact! db [[:db/add 1 :user/age 31]])

      ;; Should have new addition with updated age
      (is (contains? (:additions (last @updates)) ["Alice" 31])))))

;;; ===========================================================================
;;; Run all tests
;;; ===========================================================================

(defn run-all-tests
  "Helper to run all tests including generative ones."
  []
  (run-tests 'dfdb.generative-stress-test))
