(ns dfdb.extended-tests
  "Extended tests for Phase 1 - edge cases, error conditions, and advanced scenarios."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Lookup Refs and References
;; =============================================================================

(deftest test-lookup-ref-resolution
  (testing "Lookup refs resolve to entity IDs"
    (let [db (create-db)]
      ;; Create entity with unique email
      (transact! db [{:user/email "alice@example.com"
                      :user/name "Alice"}])

      ;; Use lookup ref in transaction
      (transact! db [{:order/id 100
                      :order/customer [:user/email "alice@example.com"]
                      :order/total 50}])

      ;; Verify reference was resolved
      (let [order (entity db 2)
            customer-id (:order/customer order)]
        (is (= 1 customer-id))  ; First entity
        (is (= "Alice" (:user/name (entity db customer-id))))))))

(deftest test-lookup-ref-not-found
  (testing "Lookup ref to non-existent entity throws error"
    (let [db (create-db)]
      (is (thrown-with-msg?
           Exception
           #"Lookup ref not found"
           (transact! db [{:order/customer [:user/email "nonexistent@example.com"]}]))))))

(deftest test-entity-references
  (testing "Entities can reference other entities"
    (let [db (create-db)]
      ;; Create manager
      (transact! db [{:user/id 100 :user/name "Bob"}])

      ;; Create employee referencing manager
      (transact! db [{:user/id 101
                      :user/name "Alice"
                      :user/manager 1}])  ; Reference by entity ID

      ;; Verify reference
      (let [employee (entity db 2)
            manager (entity db (:user/manager employee))]
        (is (= 1 (:user/manager employee)))
        (is (= "Bob" (:user/name manager)))))))

(deftest test-circular-references
  (testing "Circular references are allowed"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :user/name "Alice"}
                     {:db/id 2 :user/name "Bob"}])

      ;; Create circular reference
      (transact! db [[:db/add 1 :user/friend 2]
                     [:db/add 2 :user/friend 1]])

      ;; Verify both directions
      (is (= 2 (:user/friend (entity db 1))))
      (is (= 1 (:user/friend (entity db 2)))))))

;; =============================================================================
;; Multiple Operations and Complex Transactions
;; =============================================================================

(deftest test-multiple-entities-single-transaction
  (testing "Creating multiple entities in one transaction"
    (let [db (create-db)
          result (transact! db [{:user/name "Alice" :user/age 30}
                                {:user/name "Bob" :user/age 25}
                                {:user/name "Charlie" :user/age 35}])]

      ;; All 6 deltas (3 entities x 2 attributes each)
      (is (= 6 (count (:deltas result))))

      ;; All entities exist
      (is (some? (entity db 1)))
      (is (some? (entity db 2)))
      (is (some? (entity db 3)))

      ;; Values are correct
      (is (= "Alice" (:user/name (entity db 1))))
      (is (= 30 (:user/age (entity db 1))))
      (is (= "Bob" (:user/name (entity db 2))))
      (is (= 25 (:user/age (entity db 2)))))))

(deftest test-mixed-operations-in-transaction
  (testing "Mix of adds, updates, and retracts in one transaction"
    (let [db (create-db)]
      ;; Setup
      (transact! db [{:user/id 1 :user/name "Alice" :user/age 30}
                     {:user/id 2 :user/name "Bob" :user/age 25}])

      ;; Mixed operations
      (let [result (transact! db
                              [[:db/add 1 :user/name "Alice Smith"]  ; update
                               [:db/retract 1 :user/age 30]          ; retract
                               [:db/add 2 :user/email "bob@example.com"]  ; add new attr
                               {:user/id 3 :user/name "Charlie"}])]  ; new entity

        ;; Verify results
        (is (= "Alice Smith" (:user/name (entity db 1))))
        (is (nil? (:user/age (entity db 1))))  ; retracted
        (is (= "bob@example.com" (:user/email (entity db 2))))
        (is (= "Charlie" (:user/name (entity-by db :user/id 3))))))))

(deftest test-updating-non-existent-entity
  (testing "Updating attributes on non-existent entity creates it"
    (let [db (create-db)
          result (transact! db [[:db/add 999 :user/name "Ghost"]])]

      (is (= 1 (count (:deltas result))))
      (is (some? (entity db 999)))
      (is (= "Ghost" (:user/name (entity db 999)))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-empty-attribute-name
  (testing "Empty string as attribute value is allowed"
    (let [db (create-db)]
      (transact! db [{:user/name ""}])
      (is (= "" (:user/name (entity db 1)))))))

(deftest test-nil-value-in-map-transaction
  (testing "Nil values in map transactions are ignored"
    (let [db (create-db)
          result (transact! db [{:user/name "Alice" :user/age nil}])]

      ;; Only :user/name delta, no :user/age
      (is (= 1 (count (:deltas result))))
      (is (contains-delta? result {:attribute :user/name}))
      (is (not (contains-delta? result {:attribute :user/age}))))))

(deftest test-special-characters-in-values
  (testing "Special characters in string values"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice O'Brien"
                      :user/bio "Uses \"quotes\" & <tags>"
                      :user/unicode "Hello 世界 🌍"}])

      (let [user (entity db 1)]
        (is (= "Alice O'Brien" (:user/name user)))
        (is (= "Uses \"quotes\" & <tags>" (:user/bio user)))
        (is (= "Hello 世界 🌍" (:user/unicode user)))))))

(deftest test-very-large-number
  (testing "Large numbers are handled correctly"
    (let [db (create-db)
          big-num 9007199254740991]  ; Max safe integer in JS (for comparison)
      (transact! db [{:counter/value big-num}])
      (is (= big-num (:counter/value (entity db 1)))))))

(deftest test-boolean-values
  (testing "Boolean values as attributes"
    (let [db (create-db)]
      (transact! db [{:user/active true
                      :user/verified false}])

      (let [user (entity db 1)]
        (is (true? (:user/active user)))
        (is (false? (:user/verified user)))))))

(deftest test-keyword-as-value
  (testing "Keywords as attribute values"
    (let [db (create-db)]
      (transact! db [{:user/role :admin
                      :user/status :active}])

      (let [user (entity db 1)]
        (is (= :admin (:user/role user)))
        (is (= :active (:user/status user)))))))

;; =============================================================================
;; Time Travel Queries
;; =============================================================================

(deftest test-entity-at-specific-time
  (testing "Query entity at specific transaction (by tx-id for precision)"
    (let [db (create-db)]

      (let [result1 (transact! db [{:user/name "Alice" :user/age 30}])
            result2 (transact! db [[:db/add 1 :user/age 31]])
            result3 (transact! db [[:db/add 1 :user/age 32]])]

        ;; Query at specific transaction IDs (precise, no millisecond ambiguity)
        (is (= 30 (:user/age (entity db 1 (:tx-id result1)))))
        (is (= 31 (:user/age (entity db 1 (:tx-id result2)))))
        (is (= 32 (:user/age (entity db 1 (:tx-id result3)))))
        (is (= 32 (:user/age (entity db 1))))  ; Current time

        ;; Can also query by wall-clock time
        (is (= 32 (:user/age (entity db 1 (:tx-time result3)))))))))

(deftest test-attribute-history
  (testing "Track history of attribute changes over time"
    (let [db (create-db)]
      ;; Create user and track each transaction ID
      (let [r1 (transact! db [{:user/name "Alice" :user/status :pending}])
            r2 (transact! db [[:db/add 1 :user/status :active]])
            r3 (transact! db [[:db/add 1 :user/status :inactive]])
            r4 (transact! db [[:db/add 1 :user/status :archived]])]

        ;; Query at each transaction (by tx-id for precision)
        (is (= :pending (:user/status (entity db 1 (:tx-id r1)))))
        (is (= :active (:user/status (entity db 1 (:tx-id r2)))))
        (is (= :inactive (:user/status (entity db 1 (:tx-id r3)))))
        (is (= :archived (:user/status (entity db 1 (:tx-id r4)))))
        (is (= :archived (:user/status (entity db 1))))  ; Current time
        ))))

;; =============================================================================
;; Transaction Metadata
;; =============================================================================

(deftest test-multiple-tx-with-different-metadata
  (testing "Different transactions have different metadata"
    (let [db (create-db)
          result1 (transact! db {:tx-data [{:user/name "Alice"}]
                                 :tx-meta {:tx/user "admin" :tx/source "api"}})
          result2 (transact! db {:tx-data [{:user/name "Bob"}]
                                 :tx-meta {:tx/user "system" :tx/source "batch"}})]

      ;; First transaction metadata
      (is (= "admin" (get-in (first (:deltas result1)) [:tx :tx/user])))
      (is (= "api" (get-in (first (:deltas result1)) [:tx :tx/source])))

      ;; Second transaction metadata
      (is (= "system" (get-in (first (:deltas result2)) [:tx :tx/user])))
      (is (= "batch" (get-in (first (:deltas result2)) [:tx :tx/source]))))))

;; =============================================================================
;; Error Conditions
;; =============================================================================

(deftest test-invalid-tempid-in-value-position
  (testing "Unresolved tempid in value position throws error"
    (let [db (create-db)]
      (is (thrown-with-msg?
           Exception
           #"Unresolved tempid"
           (transact! db [{:user/name "Alice" :user/manager -99}]))))))  ; tempid never assigned

(deftest test-invalid-transaction-format
  (testing "Invalid transaction data format throws error"
    (let [db (create-db)]
      (is (thrown?
           Exception
           (transact! db ["invalid"]))))))  ; String instead of map/vector

;; =============================================================================
;; Performance and Scale Tests
;; =============================================================================

(deftest test-many-attributes-single-entity
  (testing "Entity with many attributes"
    (let [db (create-db)
          attrs (into {} (for [i (range 100)]
                           [(keyword (str "attr" i)) (str "value" i)]))]
      (transact! db [(assoc attrs :db/id 1)])

      (let [entity-data (entity db 1)]
        (is (= 100 (count (dissoc entity-data :db/id))))
        (is (= "value50" (:attr50 entity-data)))))))

(deftest test-sequential-updates
  (testing "Many sequential updates to same entity"
    (let [db (create-db)]
      (transact! db [{:counter/value 0}])

      ;; 10 sequential increments
      (dotimes [i 10]
        (let [current (:counter/value (entity db 1))]
          (transact! db [[:db/add 1 :counter/value (inc current)]])))

      (is (= 10 (:counter/value (entity db 1)))))))
