(ns dfdb.or-join-test
  "Tests for or-join operator - logical OR with explicit variable scoping."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Simple OR Tests
;; =============================================================================

(deftest simple-or-test
  (testing "Simple OR without join variables"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/type :admin}
                     {:db/id 2 :user/name "Bob" :user/type :moderator}
                     {:db/id 3 :user/name "Carol" :user/type :user}])

      (let [result (query db '[:find ?name
                               :where
                               [?person :user/name ?name]
                               (or [?person :user/type :admin]
                                   [?person :user/type :moderator])])]
        (is (= 2 (count result)))
        (is (contains? result ["Alice"]))
        (is (contains? result ["Bob"]))))))

(deftest or-with-different-attributes-test
  (testing "OR on different attributes"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/status :verified}
                     {:db/id 2 :user/name "Bob" :user/role :admin}
                     {:db/id 3 :user/name "Carol"}])

      (let [result (query db '[:find ?name
                               :where
                               [?person :user/name ?name]
                               (or [?person :user/status :verified]
                                   [?person :user/role :admin])])]
        (is (= 2 (count result)))
        (is (contains? result ["Alice"]))
        (is (contains? result ["Bob"]))))))

;; =============================================================================
;; or-join Tests
;; =============================================================================

(deftest or-join-simple-test
  (testing "or-join with explicit variable binding"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :person/name "Alice" :person/email "alice@example.com"}
                     {:db/id 2 :person/name "Bob" :person/phone "555-1234"}
                     {:db/id 3 :person/name "Carol" :person/email "carol@example.com" :person/phone "555-5678"}])

      (let [result (query db '[:find ?person ?contact
                               :where
                               [?person :person/name ?name]
                               (or-join [?person ?contact]
                                        [?person :person/email ?contact]
                                        [?person :person/phone ?contact])])]
        (is (= 4 (count result)))
        (is (some #(and (= 1 (first %)) (= "alice@example.com" (second %))) result))
        (is (some #(and (= 2 (first %)) (= "555-1234" (second %))) result))
        (is (some #(and (= 3 (first %)) (= "carol@example.com" (second %))) result))
        (is (some #(and (= 3 (first %)) (= "555-5678" (second %))) result))))))

(deftest or-join-with-filter-test
  (testing "or-join with additional filtering"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :product/name "Laptop" :product/status :in-stock}
                     {:db/id 2 :product/name "Mouse" :product/status :on-sale}
                     {:db/id 3 :product/name "Keyboard"}])

      (let [result (query db '[:find ?name
                               :where
                               [?product :product/name ?name]
                               (or-join [?product]
                                        [?product :product/status :in-stock]
                                        [?product :product/status :on-sale])])]
        (is (= 2 (count result)))
        (is (contains? result ["Laptop"]))
        (is (contains? result ["Mouse"]))))))

(deftest or-join-multiple-variables-test
  (testing "or-join with multiple join variables"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :order/id "O1" :order/status :pending :order/priority :high}
                     {:db/id 2 :order/id "O2" :order/status :completed}
                     {:db/id 3 :order/id "O3" :order/status :urgent}])

      (let [result (query db '[:find ?order
                               :where
                               [?order :order/id ?id]
                               (or-join [?order]
                                        [[?order :order/status :pending]
                                         [?order :order/priority :high]]
                                        [?order :order/status :urgent])])]
        (is (= 2 (count result)))
        (is (contains? result [1]))
        (is (contains? result [3]))))))

;; =============================================================================
;; OR with Patterns
;; =============================================================================

(deftest or-with-join-test
  (testing "OR combined with joins"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/role :admin}
                     {:db/id 2 :user/name "Bob" :user/role :moderator}
                     {:db/id 3 :user/name "Carol" :user/role :user}
                     {:order/id "O1" :order/user 1}
                     {:order/id "O2" :order/user 2}])

      (let [result (query db '[:find ?order-id
                               :where
                               [?order :order/id ?order-id]
                               [?order :order/user ?user]
                               (or [?user :user/role :admin]
                                   [?user :user/role :moderator])])]
        (is (= 2 (count result)))
        (is (contains? result ["O1"]))
        (is (contains? result ["O2"]))))))

(deftest or-with-aggregates-test
  (testing "OR combined with aggregates"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :order/status :pending :order/total 100}
                     {:db/id 2 :order/status :urgent :order/total 200}
                     {:db/id 3 :order/status :completed :order/total 150}])

      (let [result (query db '[:find (sum ?total)
                               :where
                               [?order :order/total ?total]
                               (or [?order :order/status :pending]
                                   [?order :order/status :urgent])])]
        (is (= 1 (count result)))
        (is (= 300 (ffirst result)))))))
