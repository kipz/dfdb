(ns dfdb.query-test
  "Phase 2: Basic Datalog query tests."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Test Suite: Basic Pattern Matching
;; =============================================================================

(deftest test-simple-find-query
  (testing "Basic find query"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice" :user/age 30}
                     {:user/name "Bob" :user/age 25}
                     {:user/name "Charlie" :user/age 35}])

      (let [result (query db '[:find ?name
                               :where [?e :user/name ?name]])]
        (is (= #{["Alice"] ["Bob"] ["Charlie"]} result))))))

(deftest test-query-with-predicate
  (testing "Query with predicates"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice" :user/age 30}
                     {:user/name "Bob" :user/age 25}
                     {:user/name "Charlie" :user/age 35}])

      ;; Find users over 30
      (let [result (query db '[:find ?name
                               :where
                               [?e :user/name ?name]
                               [?e :user/age ?age]
                               [(> ?age 30)]])]
        (is (= #{["Charlie"]} result))))))

(deftest test-query-with-join
  (testing "Query with join across entities"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/name "Alice"}
                     {:user/id 2 :user/name "Bob"}
                     {:order/id 100 :order/user 1 :order/total 50}
                     {:order/id 101 :order/user 1 :order/total 75}
                     {:order/id 102 :order/user 2 :order/total 100}])

      ;; Find users and their order counts
      (let [result (query db '[:find ?name (count ?order)
                               :where
                               [?user :user/name ?name]
                               [?order :order/user ?user]])]
        (is (= #{["Alice" 2] ["Bob" 1]} result))))))

(deftest test-query-with-constant-entity
  (testing "Query with constant entity ID"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/age 30}])

      (let [result (query db '[:find ?name ?age
                               :where
                               [1 :user/name ?name]
                               [1 :user/age ?age]])]
        (is (= #{["Alice" 30]} result))))))

(deftest test-query-with-constant-attribute-value
  (testing "Query filtering by specific value"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice" :user/status :active}
                     {:user/name "Bob" :user/status :inactive}
                     {:user/name "Charlie" :user/status :active}])

      (let [result (query db '[:find ?name
                               :where
                               [?e :user/name ?name]
                               [?e :user/status :active]])]
        (is (= #{["Alice"] ["Charlie"]} result))))))

(deftest test-query-multiple-patterns
  (testing "Query with multiple patterns on same entity"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice" :user/age 30 :user/city "NYC"}
                     {:user/name "Bob" :user/age 25 :user/city "SF"}
                     {:user/name "Charlie" :user/age 35}])  ; no city

      ;; Find users with both age and city
      (let [result (query db '[:find ?name
                               :where
                               [?e :user/name ?name]
                               [?e :user/age ?age]
                               [?e :user/city ?city]])]
        (is (= #{["Alice"] ["Bob"]} result))))))

(deftest test-query-with-negation
  (testing "Query with negation (NOT clause)"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice" :user/age 30 :user/verified true}
                     {:user/name "Bob" :user/age 25}
                     {:user/name "Charlie" :user/age 35 :user/verified true}])

      ;; Find users without verification
      (let [result (query db '[:find ?name
                               :where
                               [?e :user/name ?name]
                               (not [?e :user/verified _])])]
        (is (= #{["Bob"]} result))))))

;; =============================================================================
;; Test Suite: Aggregations
;; =============================================================================

(deftest test-aggregation-count
  (testing "Count aggregation"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice"}
                     {:user/name "Bob"}
                     {:user/name "Charlie"}])

      (let [result (query db '[:find (count ?e)
                               :where [?e :user/name _]])]
        (is (= #{[3]} result))))))

(deftest test-aggregation-sum
  (testing "Sum aggregation"
    (let [db (create-db)]
      (transact! db [{:order/user 1 :order/amount 50}
                     {:order/user 1 :order/amount 30}
                     {:order/user 1 :order/amount 20}])

      (let [result (query db '[:find (sum ?amount)
                               :where
                               [?order :order/user 1]
                               [?order :order/amount ?amount]])]
        (is (= #{[100]} result))))))

(deftest test-aggregation-with-grouping
  (testing "Aggregation with grouping"
    (let [db (create-db)]
      (transact! db [{:order/user 1 :order/amount 50}
                     {:order/user 1 :order/amount 30}
                     {:order/user 2 :order/amount 100}
                     {:order/user 2 :order/amount 25}])

      (let [result (query db '[:find ?user (sum ?amount)
                               :where
                               [?order :order/user ?user]
                               [?order :order/amount ?amount]])]
        (is (= #{[1 80] [2 125]} result))))))

(deftest test-aggregation-min-max
  (testing "Min and max aggregations"
    (let [db (create-db)]
      (transact! db [{:product/price 10}
                     {:product/price 20}
                     {:product/price 30}])

      (let [min-result (query db '[:find (min ?price)
                                   :where [?p :product/price ?price]])
            max-result (query db '[:find (max ?price)
                                   :where [?p :product/price ?price]])]
        (is (= #{[10]} min-result))
        (is (= #{[30]} max-result))))))

(deftest test-aggregation-avg
  (testing "Average aggregation"
    (let [db (create-db)]
      (transact! db [{:product/price 10}
                     {:product/price 20}
                     {:product/price 30}])

      (let [result (query db '[:find (avg ?price)
                               :where [?p :product/price ?price]])]
        (is (= #{[20.0]} result))))))

;; =============================================================================
;; Test Suite: Recursive Queries
;; =============================================================================

(deftest test-recursive-transitive-closure
  (testing "Recursive query for transitive relationships"
    (let [db (create-db)]
      ;; Build org hierarchy
      (transact! db [{:user/id 1 :user/name "CEO"}
                     {:user/id 2 :user/name "VP" :user/reports-to 1}
                     {:user/id 3 :user/name "Manager" :user/reports-to 2}
                     {:user/id 4 :user/name "IC1" :user/reports-to 3}
                     {:user/id 5 :user/name "IC2" :user/reports-to 3}])

      ;; Find all people who report (transitively) to CEO
      (let [result (query db '[:find ?name
                               :where
                               [?ceo :user/name "CEO"]
                               [?report :user/reports-to+ ?ceo]
                               [?report :user/name ?name]])]
        (is (= #{["VP"] ["Manager"] ["IC1"] ["IC2"]} result))))))

(deftest test-recursive-with-depth-limit
  (testing "Recursive query with depth limitation"
    (let [db (create-db)]
      ;; Build chain: 1 -> 2 -> 3 -> 4 -> 5
      (transact! db [{:node/id 1 :node/next 2}
                     {:node/id 2 :node/next 3}
                     {:node/id 3 :node/next 4}
                     {:node/id 4 :node/next 5}
                     {:node/id 5}])

      ;; Find nodes reachable from 1 with max depth 2
      (let [result (query db '[:find ?node
                               :where
                               [1 :node/next+ ?node :max-depth 2]])]
        (is (= #{[2] [3]} result))))))  ; Only 2 hops away

;; =============================================================================
;; Test Suite: Query Optimization
;; =============================================================================

(deftest test-query-result-caching
  (testing "Identical queries return cached results"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice"}
                     {:user/name "Bob"}])

      (let [query-form '[:find ?name :where [?e :user/name ?name]]
            r1 (query db query-form)
            r2 (query db query-form)]
        ;; Results should be equal
        (is (= r1 r2))
        (is (= #{["Alice"] ["Bob"]} r1))))))

(deftest test-query-with-unbound-variable
  (testing "Query with wildcard (unbound variable)"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice" :user/age 30}
                     {:user/name "Bob" :user/age 25}])

      ;; Find entities with both name and age (using _ for age value)
      (let [result (query db '[:find ?name
                               :where
                               [?e :user/name ?name]
                               [?e :user/age _]])]
        (is (= #{["Alice"] ["Bob"]} result))))))

