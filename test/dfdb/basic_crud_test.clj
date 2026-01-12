(ns dfdb.basic-crud-test
  "Basic CRUD tests for Phase 1 implementation."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

(deftest test-basic-transact-single-entity
  (testing "Basic entity creation with attributes"
    (let [db (create-db)
          result (transact! db [{:user/id 1
                                 :user/name "Alice"
                                 :user/email "alice@example.com"}])]

      ;; Transaction succeeds
      (is (some? (:tx-id result)))
      (is (inst? (:tx-time result)))

      ;; Returns deltas for all attributes
      (is (= 3 (count (:deltas result))))

      ;; Check individual deltas
      (is (contains-delta? result
                           {:entity 1
                            :attribute :user/id
                            :old-value nil
                            :new-value 1
                            :operation :assert}))

      (is (contains-delta? result
                           {:entity 1
                            :attribute :user/name
                            :old-value nil
                            :new-value "Alice"
                            :operation :assert}))

      ;; Query the entity back
      (let [user (entity db 1)]
        (is (= "Alice" (:user/name user)))
        (is (= "alice@example.com" (:user/email user)))))))

(deftest test-update-entity-attribute
  (testing "Updating an existing attribute generates correct delta"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/name "Alice"}])

      ;; Small delay to ensure different timestamps

      (let [result (transact! db [[:db/add 1 :user/name "Alice Smith"]])]

        ;; Delta shows old and new value
        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/name
                              :old-value "Alice"
                              :new-value "Alice Smith"
                              :operation :assert}))

        ;; Small delay before query

        ;; Query shows updated value
        (is (= "Alice Smith" (:user/name (entity db 1))))))))

(deftest test-retract-attribute
  (testing "Retracting an attribute"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/name "Alice"}])

      (let [result (transact! db [[:db/retract 1 :user/name "Alice"]])]

        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/name
                              :old-value "Alice"
                              :new-value nil
                              :operation :retract}))

        ;; Attribute is gone
        (is (nil? (:user/name (entity db 1))))))))

(deftest test-transaction-metadata
  (testing "Transaction metadata is captured in deltas"
    (let [db (create-db)
          result (transact! db
                            {:tx-data [{:user/id 1 :user/name "Alice"}]
                             :tx-meta {:tx/user "admin"
                                       :tx/source "api"
                                       :tx/reason "User signup"}})]

      ;; All deltas include tx metadata
      (doseq [delta (:deltas result)]
        (is (= "admin" (get-in delta [:tx :tx/user])))
        (is (= "api" (get-in delta [:tx :tx/source])))
        (is (= "User signup" (get-in delta [:tx :tx/reason])))))))

(deftest test-empty-transaction
  (testing "Empty transaction is allowed"
    (let [db (create-db)
          result (transact! db [])]
      (is (some? (:tx-id result)))
      (is (= 0 (count (:deltas result)))))))

(deftest test-entity-lookup
  (testing "Entity lookup by unique attribute"
    (let [db (create-db)]
      (transact! db [{:user/email "alice@example.com"
                      :user/name "Alice"}])

      ;; Can look up by email
      (let [user (entity-by db :user/email "alice@example.com")]
        (is (some? user))
        (is (= "Alice" (:user/name user)))
        (is (= "alice@example.com" (:user/email user)))))))

(deftest test-tempid-resolution
  (testing "Temporary IDs are resolved to real entity IDs"
    (let [db (create-db)
          result (transact! db [{:db/id -1
                                 :user/name "Alice"}
                                {:db/id -2
                                 :user/name "Bob"}])]

      ;; Both entities were created
      (is (= 2 (count (:deltas result))))  ; 2 entities x 1 attr (:user/name)

      ;; Can retrieve both entities
      (is (some? (entity db 1)))
      (is (some? (entity db 2))))))
