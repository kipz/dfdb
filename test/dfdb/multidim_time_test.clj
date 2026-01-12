(ns dfdb.multidim-time-test
  "Phase 2: Multi-dimensional time tests."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Stub Functions for Not-Yet-Implemented Features
;; =============================================================================

;; =============================================================================
;; Test Suite: Time Dimension Management
;; =============================================================================

(deftest test-define-time-dimension
  (testing "Creating a new time dimension"
    (let [db (create-db)
          result (transact! db
                            [{:dimension/name :time/shipped
                              :dimension/type :instant
                              :dimension/description "When shipment left warehouse"
                              :dimension/indexed? true}])]

      (is (some? (:tx-id result)))

      ;; Dimension is queryable as an entity
      (let [dim (entity-by db :dimension/name :time/shipped)]
        (is (some? dim))
        (is (= :instant (:dimension/type dim)))
        (is (= "When shipment left warehouse" (:dimension/description dim)))
        (is (true? (:dimension/indexed? dim)))))))

(deftest test-transact-with-user-time-dimension
  (testing "Transaction with user-defined time dimension"
    (let [db (create-db)]
      ;; Define dimension first
      (transact! db [{:dimension/name :time/valid
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Transact with that dimension
      (let [result (transact! db
                              {:tx-data [{:user/name "Alice"}]
                               :time-dimensions {:time/valid #inst "2026-01-01T00:00:00Z"}})]

        ;; Deltas include both system-time and valid-time
        (is (= 1 (count (:deltas result))))
        (let [delta (first (:deltas result))]
          (is (inst? (:time/system delta)))  ; system-time always present
          (is (= #inst "2026-01-01T00:00:00Z" (:time/valid delta))))))))

(deftest test-query-as-of-user-dimension
  (testing "Query as-of user-defined time dimension"
    (let [db (create-db)]
      ;; Define valid-time dimension
      (transact! db [{:dimension/name :time/valid
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Add entity at valid-time 2026-01-01
      (transact! db {:tx-data [{:user/name "Alice"}]
                     :time-dimensions {:time/valid #inst "2026-01-01"}})

      ;; Add another at valid-time 2026-01-15
      (transact! db {:tx-data [{:user/id 2 :user/name "Bob"}]
                     :time-dimensions {:time/valid #inst "2026-01-15"}})

      ;; Query as-of 2026-01-10 - should only see Alice
      (let [result (query db {:query '[:find ?name
                                       :where [?e :user/name ?name]]
                              :as-of {:time/valid #inst "2026-01-10"}})]
        (is (= #{["Alice"]} result)))

      ;; Query as-of 2026-01-20 - should see both
      (let [result (query db {:query '[:find ?name
                                       :where [?e :user/name ?name]]
                              :as-of {:time/valid #inst "2026-01-20"}})]
        (is (= #{["Alice"] ["Bob"]} result))))))

(deftest test-retroactive-update-user-dimension
  (testing "Retroactively updating a user time dimension"
    (let [db (create-db)]
      ;; Define dimensions
      (transact! db [{:dimension/name :time/valid
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Initial transaction at valid-time 2026-01-01
      (transact! db {:tx-data [{:user/name "Alice"}]
                     :time-dimensions {:time/valid #inst "2026-01-01"}})

      ;; Retroactive update: change name at valid-time 2026-01-05
      (let [result (transact! db {:tx-data [[:db/add 2 :user/name "Alice Smith"]]
                                  :time-dimensions {:time/valid #inst "2026-01-05"}})]

        ;; Delta includes valid-time
        (is (contains-delta? result
                             {:entity 2
                              :attribute :user/name
                              :old-value "Alice"
                              :new-value "Alice Smith"
                              :operation :assert
                              :time/valid #inst "2026-01-05"}))

        ;; Query at different valid-times
        (let [r1 (query db {:query '[:find ?name
                                     :where [2 :user/name ?name]]
                            :as-of {:time/valid #inst "2026-01-03"}})]
          (is (= #{["Alice"]} r1)))

        (let [r2 (query db {:query '[:find ?name
                                     :where [2 :user/name ?name]]
                            :as-of {:time/valid #inst "2026-01-10"}})]
          (is (= #{["Alice Smith"]} r2)))))))

(deftest test-system-time-never-retroactive
  (testing "System-time cannot be set retroactively"
    (let [db (create-db)
          past-time #inst "2020-01-01"]

      ;; Attempting to set system-time in the past should fail
      (is (thrown-with-msg?
           Exception
           #"system-time.*retroactive"
           (transact! db {:tx-data [{:user/name "Alice"}]
                          :time-dimensions {:time/system past-time}}))))))

(deftest test-multiple-time-dimensions-single-transaction
  (testing "Transaction with multiple user time dimensions"
    (let [db (create-db)]
      ;; Define multiple dimensions
      (transact! db [{:dimension/name :time/ordered
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/shipped
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/delivered
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Transact with multiple dimensions
      (let [result (transact! db
                              {:tx-data [{:order/id 100}]
                               :time-dimensions {:time/ordered #inst "2026-01-01"
                                                 :time/shipped #inst "2026-01-05"
                                                 :time/delivered #inst "2026-01-10"}})]

        ;; All dimensions in delta
        (let [delta (first (:deltas result))]
          (is (inst? (:time/system delta)))
          (is (= #inst "2026-01-01" (:time/ordered delta)))
          (is (= #inst "2026-01-05" (:time/shipped delta)))
          (is (= #inst "2026-01-10" (:time/delivered delta))))))))

(deftest test-undefined-time-dimension-error
  (testing "Using undefined time dimension fails"
    (let [db (create-db)]
      (is (thrown-with-msg?
           Exception
           #"Undefined time dimension"
           (transact! db {:tx-data [{:order/id 100}]
                          :time-dimensions {:time/undefined #inst "2026-01-01"}}))))))

(deftest test-sparse-time-dimensions
  (testing "Facts can have subset of dimensions (sparse)"
    (let [db (create-db)]
      ;; Define two dimensions
      (transact! db [{:dimension/name :time/valid
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/shipped
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Entity A: has valid-time only
      (transact! db {:tx-data [{:entity/id "A"}]
                     :time-dimensions {:time/valid #inst "2026-01-05"}})

      ;; Entity B: has shipped-time only
      (transact! db {:tx-data [{:entity/id "B"}]
                     :time-dimensions {:time/shipped #inst "2026-01-05"}})

      ;; Query as-of valid-time should only return A (B has no valid-time)
      (let [result (query db {:query '[:find ?id
                                       :where [?e :entity/id ?id :at/valid ?vt]]
                              :as-of {:time/valid #inst "2026-01-10"}})]
        (is (= #{["A"]} result)))

      ;; Query as-of shipped-time should only return B
      (let [result (query db {:query '[:find ?id
                                       :where [?e :entity/id ?id :at/shipped ?st]]
                              :as-of {:time/shipped #inst "2026-01-10"}})]
        (is (= #{["B"]} result))))))

;; =============================================================================
;; Test Suite: Time Dimension Constraints
;; =============================================================================

(deftest test-ordering-constraint-validation
  (testing "Ordering constraint prevents invalid data"
    (let [db (create-db)]
      ;; Define dimensions with constraint
      (transact! db [{:dimension/name :time/ordered
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/shipped
                      :dimension/type :instant
                      :dimension/indexed? true
                      :dimension/constraints [{:type :ordering
                                               :after :time/ordered}]}])

      ;; Valid: shipped after ordered
      (is (some? (transact! db {:tx-data [{:order/id 100}]
                                :time-dimensions {:time/ordered #inst "2026-01-01"
                                                  :time/shipped #inst "2026-01-05"}})))

      ;; Invalid: shipped before ordered
      (is (thrown-with-msg?
           Exception
           #"Constraint violation.*shipped.*ordered"
           (transact! db {:tx-data [{:order/id 101}]
                          :time-dimensions {:time/ordered #inst "2026-01-10"
                                            :time/shipped #inst "2026-01-05"}})))))

  (testing "Updating time dimension respects constraints"
    (let [db (create-db)]
      (transact! db [{:dimension/name :time/ordered
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/shipped
                      :dimension/type :instant
                      :dimension/indexed? true
                      :dimension/constraints [{:type :ordering
                                               :after :time/ordered}]}])

      (transact! db {:tx-data [{:order/id 100}]
                     :time-dimensions {:time/ordered #inst "2026-01-01"
                                       :time/shipped #inst "2026-01-05"}})

      ;; Try to update shipped time to before ordered - should fail
      (is (thrown-with-msg?
           Exception
           #"Constraint violation"
           (transact! db {:tx-data [[:db/add [:order/id 100] :order/status :updated]]
                          :time-dimensions {:time/shipped #inst "2025-12-31"}}))))))

(deftest test-derived-dimension
  (testing "Derived dimension computed from other dimensions"
    (let [db (create-db)]
      ;; Define dimensions with derivation
      (transact! db [{:dimension/name :time/shipped
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/delivered
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/delivery-duration
                      :dimension/type :duration
                      :dimension/indexed? false
                      :dimension/derived-from {:op :minus
                                               :operands [:time/delivered
                                                          :time/shipped]}}])

      ;; Add order with shipped and delivered times
      (transact! db {:tx-data [{:order/id 100}]
                     :time-dimensions {:time/shipped #inst "2026-01-01T00:00:00Z"
                                       :time/delivered #inst "2026-01-05T00:00:00Z"}})

      ;; Query the derived dimension
      (let [result (query db '[:find ?duration
                               :where
                               [?order :order/id 100]
                               [?order :order/id _ :at/delivery-duration ?duration]])]
        ;; 4 days in milliseconds
        (is (= #{[345600000]} result))))))

;; =============================================================================
;; Test Suite: Cross-Dimensional Queries
;; =============================================================================

(deftest test-temporal-predicate-cross-dimension
  (testing "Query with temporal predicate across dimensions"
    (let [db (create-db)]
      ;; Setup dimensions
      (transact! db [{:dimension/name :time/ordered
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/shipped
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Add orders with different lead times
      (transact! db {:tx-data [{:order/id 100}]
                     :time-dimensions {:time/ordered #inst "2026-01-01"
                                       :time/shipped #inst "2026-01-03"}})  ; 2 days

      (transact! db {:tx-data [{:order/id 101}]
                     :time-dimensions {:time/ordered #inst "2026-01-05"
                                       :time/shipped #inst "2026-01-08"}})  ; 3 days

      (transact! db {:tx-data [{:order/id 102}]
                     :time-dimensions {:time/ordered #inst "2026-01-10"
                                       :time/shipped #inst "2026-01-11"}})  ; 1 day

      ;; Find orders where shipping took more than 2 days
      (let [result (query db '[:find ?order-id
                               :where
                               [?order :order/id ?order-id] [?order :order/id _ :at/ordered ?ordered]
                               [?order :order/id _ :at/shipped ?shipped]
                               [(- ?shipped ?ordered) ?diff]
                               [(> ?diff 172800000)]])]  ; 2 days in ms
        (is (= #{[101]} result))))))

(deftest test-multi-dimensional-as-of
  (testing "Query as-of multiple dimensions simultaneously"
    (let [db (create-db)]
      ;; Setup dimensions
      (transact! db [{:dimension/name :time/valid
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/shipped
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Complex scenario: order progresses through different times
      (transact! db {:tx-data [{:order/id 100 :order/status :created}]
                     :time-dimensions {:time/valid #inst "2026-01-01"}})

      (transact! db {:tx-data [[:db/add [:order/id 100] :order/status :shipped]]
                     :time-dimensions {:time/valid #inst "2026-01-05"
                                       :time/shipped #inst "2026-01-05"}})

      (transact! db {:tx-data [[:db/add [:order/id 100] :order/status :delivered]]
                     :time-dimensions {:time/valid #inst "2026-01-10"}})

      ;; Query: what was order status at valid-time 2026-01-07?
      (let [result (query db {:query '[:find ?status
                                       :where [?order :order/id 100] [?order :order/status ?status]]
                              :as-of {:time/valid #inst "2026-01-07"}})]
        (is (= #{[:shipped]} result)))

      ;; Query: orders that had shipped-time set as-of valid-time 2026-01-03
      (let [result (query db {:query '[:find ?order
                                       :where
                                       [?order :order/id _ :at/shipped ?st]]
                              :as-of {:time/valid #inst "2026-01-03"}})]
        (is (= #{} result)))  ; shipped-time wasn't set yet

      ;; Query as-of valid-time 2026-01-07
      (let [result (query db {:query '[:find ?order-id ?st
                                       :where
                                       [?order :order/id ?order-id]
                                       [?order :order/status _ :at/shipped ?st]]
                              :as-of {:time/valid #inst "2026-01-07"}})]
        (is (= #{[100 #inst "2026-01-05"]} result))))))

;; =============================================================================
;; Test Suite: Supply Chain End-to-End
;; =============================================================================

(deftest test-supply-chain-basic
  (testing "Supply chain with multiple time dimensions"
    (let [db (create-db)]

      ;; Step 1: Define time dimensions
      (transact! db
                 [{:dimension/name :time/ordered
                   :dimension/type :instant
                   :dimension/indexed? true}
                  {:dimension/name :time/shipped
                   :dimension/type :instant
                   :dimension/indexed? true
                   :dimension/constraints [{:type :ordering :after :time/ordered}]}
                  {:dimension/name :time/delivered
                   :dimension/type :instant
                   :dimension/indexed? true
                   :dimension/constraints [{:type :ordering :after :time/shipped}]}])

      ;; Step 2: Customer places order
      (let [result (transact! db {:tx-data [{:order/id "ORD-100"
                                             :order/customer "Alice"
                                             :order/status :ordered}]
                                  :time-dimensions {:time/ordered #inst "2026-01-01T10:00:00Z"}
                                  :tx-meta {:tx/source "web-ui"}})]
        (is (some? (:tx-id result))))

      ;; Step 3: Warehouse ships order
      (transact! db {:tx-data [[:db/add [:order/id "ORD-100"] :order/status :shipped]]
                     :time-dimensions {:time/shipped #inst "2026-01-02T14:30:00Z"}
                     :tx-meta {:tx/source "warehouse-system"}})

      ;; Step 4: Carrier delivers order
      (transact! db {:tx-data [[:db/add [:order/id "ORD-100"] :order/status :delivered]]
                     :time-dimensions {:time/delivered #inst "2026-01-05T09:15:00Z"}
                     :tx-meta {:tx/source "carrier-system"}})

      ;; Step 5: Query orders in transit (shipped but not delivered) as-of 2026-01-03
      (let [result (query db {:query '[:find ?order-id
                                       :where
                                       [?order :order/id ?order-id] [?order :order/status _ :at/shipped ?st]
                                       (not [?order :order/status _ :at/delivered ?dt])]
                              :as-of {:time/shipped #inst "2026-01-03T23:59:59Z"
                                      :time/delivered #inst "2026-01-03T00:00:00Z"}})]
        (is (= #{["ORD-100"]} result)))

      ;; Step 6: Query shipping duration
      (let [result (query db '[:find ?order-id ?duration
                               :where
                               [?order :order/id ?order-id]
                               [?order :order/status _ :at/shipped ?st]
                               [?order :order/status _ :at/delivered ?dt]
                               [(- ?dt ?st) ?duration]])]
        ;; ~2.75 days in milliseconds
        (is (= 1 (count result)))
        (let [[_order duration] (first result)]
          (is (< 200000000 duration 250000000)))))))
