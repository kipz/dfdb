(ns dfdb.not-join-test
  "Tests for enhanced not-join operator - NOT with explicit variable scoping."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Basic NOT Tests (should already work)
;; =============================================================================

(deftest basic-not-test
  (testing "Basic NOT clause without join"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/status :active}
                     {:db/id 2 :user/name "Bob"}])

      (let [result (query db '[:find ?name
                               :where
                               [?user :user/name ?name]
                               (not [?user :user/status :active])])]
        (is (= 1 (count result)))
        (is (contains? result ["Bob"]))))))

;; =============================================================================
;; not-join Tests
;; =============================================================================

(deftest not-join-simple-test
  (testing "not-join with explicit variable binding"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice"}
                     {:db/id 2 :user/name "Bob" :user/status :suspended}
                     {:db/id 3 :user/name "Carol"}])

      (let [result (query db '[:find ?name
                               :where
                               [?user :user/name ?name]
                               (not-join [?user]
                                         [?user :user/status :suspended])])]
        (is (= 2 (count result)))
        (is (contains? result ["Alice"]))
        (is (contains? result ["Carol"]))))))

(deftest not-join-with-multiple-patterns-test
  (testing "not-join with multiple patterns in NOT clause"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :product/name "Laptop" :product/status :available}
                     {:db/id 2 :product/name "Mouse" :product/status :available}
                     {:db/id 3 :product/name "Keyboard" :product/status :available}
                     {:order/id "O1" :order/product 2 :order/status :pending}])

      (let [result (query db '[:find ?name
                               :where
                               [?product :product/name ?name]
                               [?product :product/status :available]
                               (not-join [?product]
                                         [?order :order/product ?product]
                                         [?order :order/status :pending])])]
        (is (= 2 (count result)))
        (is (contains? result ["Laptop"]))
        (is (contains? result ["Keyboard"]))
        (is (not (contains? result ["Mouse"])))))))

(deftest not-join-with-predicates-test
  (testing "not-join with predicates inside NOT"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/age 30}
                     {:db/id 2 :user/name "Bob" :user/age 16}
                     {:db/id 3 :user/name "Carol" :user/age 25}
                     {:subscription/user 2 :subscription/status :active}])

      (let [result (query db '[:find ?name
                               :where
                               [?user :user/name ?name]
                               [?user :user/age ?age]
                               [(>= ?age 18)]
                               (not-join [?user]
                                         [?sub :subscription/user ?user]
                                         [?sub :subscription/status :active])])]
        (is (= 2 (count result)))
        (is (contains? result ["Alice"]))
        (is (contains? result ["Carol"]))))))

;; =============================================================================
;; NOT with Aggregates
;; =============================================================================

(deftest not-with-aggregates-test
  (testing "NOT combined with aggregates"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :product/name "A" :product/price 100 :product/status :discontinued}
                     {:db/id 2 :product/name "B" :product/price 200}
                     {:db/id 3 :product/name "C" :product/price 150}])

      (let [result (query db '[:find (avg ?price)
                               :where
                               [?product :product/price ?price]
                               (not [?product :product/status :discontinued])])]
        (is (= 1 (count result)))
        (is (= 175.0 (ffirst result)))))))

;; =============================================================================
;; NOT with Joins
;; =============================================================================

(deftest not-join-after-join-test
  (testing "not-join after regular join"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/id "U1"}
                     {:db/id 2 :user/name "Bob" :user/id "U2"}
                     {:order/id "O1" :order/user-id "U1"}
                     {:blacklist/user-id "U1"}])

      (let [result (query db '[:find ?name
                               :where
                               [?user :user/id ?uid]
                               [?user :user/name ?name]
                               (not-join [?uid]
                                         [?bl :blacklist/user-id ?uid])])]
        (is (= 1 (count result)))
        (is (contains? result ["Bob"]))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest not-join-empty-result-test
  (testing "not-join when NOT pattern matches nothing"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice"}
                     {:db/id 2 :user/name "Bob"}])

      (let [result (query db '[:find ?name
                               :where
                               [?user :user/name ?name]
                               (not-join [?user]
                                         [?user :user/status :banned])])]
        (is (= 2 (count result)))
        (is (contains? result ["Alice"]))
        (is (contains? result ["Bob"]))))))

(deftest not-join-all-match-test
  (testing "not-join when NOT pattern matches all"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/status :active}
                     {:db/id 2 :user/name "Bob" :user/status :active}])

      (let [result (query db '[:find ?name
                               :where
                               [?user :user/name ?name]
                               (not-join [?user]
                                         [?user :user/status :active])])]
        (is (empty? result))))))
