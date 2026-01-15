(ns dfdb.pull-api-test
  "Tests for Datomic-style pull API."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]
            [dfdb.pull :as pull]))

;; =============================================================================
;; Pull All Attributes (Wildcard)
;; =============================================================================

(deftest pull-wildcard-test
  (testing "Pull all attributes with [*]"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/age 30 :user/email "alice@example.com"}])

      (let [result (pull/pull db 1 '[*])]
        (is (= 1 (:db/id result)))
        (is (= "Alice" (:user/name result)))
        (is (= 30 (:user/age result)))
        (is (= "alice@example.com" (:user/email result)))))))

(deftest pull-wildcard-multiple-values-test
  (testing "Pull with cardinality-many attributes"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/tags ["clojure" "datalog" "database"]}])

      (let [result (pull/pull db 1 '[*])]
        (is (= 1 (:db/id result)))
        (is (= "Alice" (:user/name result)))
        (is (= ["clojure" "datalog" "database"] (:user/tags result)))))))

;; =============================================================================
;; Pull Specific Attributes
;; =============================================================================

(deftest pull-specific-attributes-test
  (testing "Pull specific attributes"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/age 30 :user/email "alice@example.com"}])

      (let [result (pull/pull db 1 '[:user/name :user/age])]
        (is (= 1 (:db/id result)))
        (is (= "Alice" (:user/name result)))
        (is (= 30 (:user/age result)))
        (is (nil? (:user/email result)) "Email not requested, should be nil")))))

(deftest pull-nonexistent-attribute-test
  (testing "Pull nonexistent attribute returns nil"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice"}])

      (let [result (pull/pull db 1 '[:user/name :user/nonexistent])]
        (is (= "Alice" (:user/name result)))
        (is (nil? (:user/nonexistent result)))))))

(deftest pull-nonexistent-entity-test
  (testing "Pull nonexistent entity"
    (let [db (create-db)]
      (let [result (pull/pull db 999 '[:user/name])]
        (is (= 999 (:db/id result)))
        (is (empty? (dissoc result :db/id)) "Should only have :db/id")))))

;; =============================================================================
;; Pull with Nested Entities (Join)
;; =============================================================================

(deftest pull-nested-simple-test
  (testing "Pull nested entity"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :order/id "O1" :order/customer 2}
                     {:db/id 2 :customer/name "Bob" :customer/email "bob@example.com"}])

      (let [result (pull/pull db 1 '[:order/id {:order/customer [:customer/name]}])]
        (is (= "O1" (:order/id result)))
        (is (= {:db/id 2 :customer/name "Bob"}
               (:order/customer result)))))))

(deftest pull-nested-multiple-attributes-test
  (testing "Pull nested entity with multiple attributes"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :order/id "O1" :order/customer 2}
                     {:db/id 2 :customer/name "Bob" :customer/email "bob@example.com" :customer/age 25}])

      (let [result (pull/pull db 1 '[:order/id {:order/customer [:customer/name :customer/email]}])]
        (is (= "O1" (:order/id result)))
        (is (= {:db/id 2 :customer/name "Bob" :customer/email "bob@example.com"}
               (:order/customer result)))))))

(deftest pull-nested-wildcard-test
  (testing "Pull nested entity with wildcard"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :order/id "O1" :order/customer 2}
                     {:db/id 2 :customer/name "Bob" :customer/email "bob@example.com"}])

      (let [result (pull/pull db 1 '[:order/id {:order/customer [*]}])]
        (is (= "O1" (:order/id result)))
        (let [customer (:order/customer result)]
          (is (= 2 (:db/id customer)))
          (is (= "Bob" (:customer/name customer)))
          (is (= "bob@example.com" (:customer/email customer))))))))

;; =============================================================================
;; Pull with Cardinality-Many References
;; =============================================================================

(deftest pull-many-refs-test
  (testing "Pull cardinality-many references"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/friends [2 3]}
                     {:db/id 2 :user/name "Bob"}
                     {:db/id 3 :user/name "Carol"}])

      (let [result (pull/pull db 1 '[:user/name {:user/friends [:user/name]}])]
        (is (= "Alice" (:user/name result)))
        (is (= 2 (count (:user/friends result))))
        (is (some #(= "Bob" (:user/name %)) (:user/friends result)))
        (is (some #(= "Carol" (:user/name %)) (:user/friends result)))))))

;; =============================================================================
;; Pull with Reverse Lookup
;; =============================================================================

(deftest pull-reverse-lookup-test
  (testing "Pull reverse relationship with _"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice"}
                     {:db/id 2 :user/name "Bob" :user/manager 1}
                     {:db/id 3 :user/name "Carol" :user/manager 1}])

      (let [result (pull/pull db 1 '[:user/name {:user/_manager [:user/name]}])]
        (is (= "Alice" (:user/name result)))
        (is (= 2 (count (:user/_manager result))))
        (is (some #(= "Bob" (:user/name %)) (:user/_manager result)))
        (is (some #(= "Carol" (:user/name %)) (:user/_manager result)))))))

;; =============================================================================
;; Pull in Query Context
;; =============================================================================

(deftest pull-in-query-test
  (testing "Use pull in :find clause"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/age 30}
                     {:db/id 2 :user/name "Bob" :user/age 25}])

      (let [result (query db '[:find (pull ?e [:user/name :user/age])
                               :where [?e :user/name _]])]
        (is (= 2 (count result)))
        (is (some #(= "Alice" (:user/name (first %))) result))
        (is (some #(= "Bob" (:user/name (first %))) result))))))

(deftest pull-in-query-with-filter-test
  (testing "Pull with where clause filtering"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/age 30}
                     {:db/id 2 :user/name "Bob" :user/age 25}])

      (let [result (query db '[:find (pull ?e [*])
                               :where
                               [?e :user/age ?age]
                               [(> ?age 28)]])]
        (is (= 1 (count result)))
        (let [pulled (ffirst result)]
          (is (= "Alice" (:user/name pulled))))))))

;; =============================================================================
;; Pull with Limits
;; =============================================================================

(deftest pull-with-limit-test
  (testing "Pull with :limit option"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice" :user/posts [10 11 12 13 14]}
                     {:db/id 10 :post/title "Post 1"}
                     {:db/id 11 :post/title "Post 2"}
                     {:db/id 12 :post/title "Post 3"}
                     {:db/id 13 :post/title "Post 4"}
                     {:db/id 14 :post/title "Post 5"}])

      (let [result (pull/pull db 1 '[:user/name {:user/posts [:post/title] :limit 3}])]
        (is (= "Alice" (:user/name result)))
        (is (<= (count (:user/posts result)) 3))))))

;; =============================================================================
;; Pull Edge Cases
;; =============================================================================

(deftest pull-empty-pattern-test
  (testing "Pull with empty pattern list"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice"}])

      (let [result (pull/pull db 1 '[])]
        (is (= {:db/id 1} result))))))

(deftest pull-nil-reference-test
  (testing "Pull when reference attribute is nil/missing"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :order/id "O1"}])

      (let [result (pull/pull db 1 '[:order/id {:order/customer [:customer/name]}])]
        (is (= "O1" (:order/id result)))
        (is (nil? (:order/customer result)))))))

(deftest pull-deep-nesting-test
  (testing "Pull with deep nesting (3 levels)"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :company/name "ACME" :company/ceo 2}
                     {:db/id 2 :person/name "Alice" :person/address 3}
                     {:db/id 3 :address/city "NYC" :address/country 4}
                     {:db/id 4 :country/name "USA"}])

      (let [result (pull/pull db 1 '[:company/name
                                     {:company/ceo
                                      [:person/name
                                       {:person/address
                                        [:address/city
                                         {:address/country [:country/name]}]}]}])]
        (is (= "ACME" (:company/name result)))
        (is (= "Alice" (get-in result [:company/ceo :person/name])))
        (is (= "NYC" (get-in result [:company/ceo :person/address :address/city])))
        (is (= "USA" (get-in result [:company/ceo :person/address :address/country :country/name])))))))
