(ns dfdb.core-test
  "Comprehensive test specification for dfdb.
  This file defines expected behaviors through test scenarios.
  Written in TDD style - define behavior first, implement later."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Stub Functions for Not-Yet-Implemented Features
;; =============================================================================

(defn query
  "Execute query against database - TODO: implement"
  [db query-map]
  (throw (ex-info "Query not yet implemented" {:query query-map})))

(defn subscribe
  "Subscribe to query for incremental updates - TODO: implement"
  [db subscription-config]
  (throw (ex-info "Subscribe not yet implemented" {:config subscription-config})))

(defn unsubscribe
  "Unsubscribe from query - TODO: implement"
  [subscription]
  (throw (ex-info "Unsubscribe not yet implemented" {:sub subscription})))

(defn add-constraint!
  "Add validation constraint - TODO: implement"
  [db attribute-or-dimension constraint-fn]
  (throw (ex-info "Constraints not yet implemented" {})))

;; =============================================================================
;; Test Suite 1: Basic CRUD Operations
;; =============================================================================

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

      (let [result (transact! db [[:db/add 1 :user/name "Alice Smith"]])]

        ;; Delta shows old and new value
        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/name
                              :old-value "Alice"
                              :new-value "Alice Smith"
                              :operation :assert}))

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

;; =============================================================================
;; Test Suite 2: Multi-Dimensional Time
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

      ;; Dimension is queryable
      (let [dim (entity-by db :dimension/name :time/shipped)]
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
                              {:tx-data [{:user/id 1 :user/name "Alice"}]
                               :time-dimensions {:time/valid #inst "2026-01-01T00:00:00Z"}})]

        ;; Deltas include both system-time and valid-time
        (doseq [delta (:deltas result)]
          (is (inst? (:time/system delta)))  ; always present
          (is (= #inst "2026-01-01T00:00:00Z" (:time/valid delta))))))))

(deftest test-query-as-of-system-time
  (testing "Query as-of specific system-time"
    (let [db (create-db)
          t1 (current-time)]

      (transact! db [{:user/id 1 :user/name "Alice"}])
      (Thread/sleep 10)
      (let [t2 (current-time)]
        (transact! db [[:db/add 1 :user/name "Alice Smith"]])

        ;; Query as-of t2 (before update)
        (let [result (query db {:query [:find ?name
                                        :where [?e :user/name ?name]]
                                :as-of {:time/system t2}})]
          (is (= #{["Alice"]} result)))

        ;; Query as-of now (after update)
        (let [result (query db {:query [:find ?name
                                        :where [?e :user/name ?name]]})]
          (is (= #{["Alice Smith"]} result)))))))

(deftest test-query-as-of-user-dimension
  (testing "Query as-of user-defined time dimension"
    (let [db (create-db)]
      ;; Define valid-time dimension
      (transact! db [{:dimension/name :time/valid
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Add entity at valid-time 2026-01-01
      (transact! db {:tx-data [{:user/id 1 :user/name "Alice"}]
                     :time-dimensions {:time/valid #inst "2026-01-01"}})

      ;; Add another at valid-time 2026-01-15
      (transact! db {:tx-data [{:user/id 2 :user/name "Bob"}]
                     :time-dimensions {:time/valid #inst "2026-01-15"}})

      ;; Query as-of 2026-01-10 - should only see Alice
      (let [result (query db {:query [:find ?name
                                      :where [?e :user/name ?name]]
                              :as-of {:time/valid #inst "2026-01-10"}})]
        (is (= #{["Alice"]} result)))

      ;; Query as-of 2026-01-20 - should see both
      (let [result (query db {:query [:find ?name
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
      (transact! db {:tx-data [{:user/id 1 :user/name "Alice"}]
                     :time-dimensions {:time/valid #inst "2026-01-01"}})

      ;; Retroactive update: change name at valid-time 2026-01-05
      (let [result (transact! db {:tx-data [[:db/add 1 :user/name "Alice Smith"]]
                                  :time-dimensions {:time/valid #inst "2026-01-05"}})]

        ;; Delta includes valid-time
        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/name
                              :old-value "Alice"
                              :new-value "Alice Smith"
                              :operation :assert
                              :time/valid #inst "2026-01-05"}))

        ;; Query at different valid-times
        (is (= "Alice"
               (-> (query db {:query [:find ?name
                                      :where [1 :user/name ?name]]
                              :as-of {:time/valid #inst "2026-01-03"}})
                   first first)))

        (is (= "Alice Smith"
               (-> (query db {:query [:find ?name
                                      :where [1 :user/name ?name]]
                              :as-of {:time/valid #inst "2026-01-10"}})
                   first first)))))))

(deftest test-system-time-never-retroactive
  (testing "System-time cannot be set retroactively"
    (let [db (create-db)
          past-time #inst "2020-01-01"]

      ;; Attempting to set system-time in the past should fail
      (is (thrown? Exception
                   (transact! db {:tx-data [{:user/id 1 :user/name "Alice"}]
                                  :time-dimensions {:time/system past-time}})))

      ;; System-time is always set to current time by system
      (let [before (current-time)
            result (transact! db [{:user/id 1 :user/name "Alice"}])
            after (current-time)
            tx-time (:tx-time result)]
        (is (<= before tx-time after))))))

;; =============================================================================
;; Test Suite 3: Collection Operations (Position-Based)
;; =============================================================================

(deftest test-vector-conj
  (testing "Adding element to vector generates position-based delta"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/tags ["clojure" "rust"]}])

      (let [result (transact! db [[:db/add 1 :user/tags "databases"]])]

        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/tags
                              :old-value ["clojure" "rust"]
                              :new-value ["clojure" "rust" "databases"]
                              :operation :assert
                              :collection-op :conj
                              :collection-index 2
                              :collection-element "databases"}))))))

(deftest test-vector-remove-by-index
  (testing "Removing element from vector by index"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/tags ["clojure" "rust" "databases"]}])

      ;; Remove element at index 1 ("rust")
      (let [result (transact! db [[:db/retract-at 1 :user/tags 1]])]

        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/tags
                              :old-value ["clojure" "rust" "databases"]
                              :new-value ["clojure" "databases"]
                              :operation :retract
                              :collection-op :dissoc
                              :collection-index 1
                              :collection-element "rust"}))))))

(deftest test-vector-update-at-index
  (testing "Updating element at specific index"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/scores [10 20 30]}])

      ;; Update index 1 to 25
      (let [result (transact! db [[:db/update-at 1 :user/scores 1 25]])]

        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/scores
                              :old-value [10 20 30]
                              :new-value [10 25 30]
                              :operation :assert
                              :collection-op :assoc
                              :collection-index 1
                              :collection-element-old 20
                              :collection-element-new 25}))))))

(deftest test-set-operations
  (testing "Set additions and removals"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/roles #{:admin :user}}])

      ;; Add to set
      (let [result (transact! db [[:db/add 1 :user/roles :moderator]])]
        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/roles
                              :old-value #{:admin :user}
                              :new-value #{:admin :user :moderator}
                              :operation :assert
                              :collection-op :conj
                              :collection-element :moderator})))

      ;; Remove from set
      (let [result (transact! db [[:db/retract 1 :user/roles :user]])]
        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/roles
                              :old-value #{:admin :user :moderator}
                              :new-value #{:admin :moderator}
                              :operation :retract
                              :collection-op :disj
                              :collection-element :user}))))))

(deftest test-map-operations
  (testing "Map key additions and removals"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/preferences {:theme "dark" :lang "en"}}])

      ;; Add key to map
      (let [result (transact! db [[:db/assoc 1 :user/preferences :notifications true]])]
        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/preferences
                              :old-value {:theme "dark" :lang "en"}
                              :new-value {:theme "dark" :lang "en" :notifications true}
                              :operation :assert
                              :collection-op :assoc
                              :collection-key :notifications
                              :collection-element true})))

      ;; Update existing key
      (let [result (transact! db [[:db/assoc 1 :user/preferences :theme "light"]])]
        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/preferences
                              :collection-op :assoc
                              :collection-key :theme
                              :collection-element-old "dark"
                              :collection-element-new "light"})))

      ;; Remove key from map
      (let [result (transact! db [[:db/dissoc 1 :user/preferences :lang]])]
        (is (contains-delta? result
                             {:entity 1
                              :attribute :user/preferences
                              :operation :retract
                              :collection-op :dissoc
                              :collection-key :lang
                              :collection-element "en"}))))))

;; =============================================================================
;; Test Suite 4: References
;; =============================================================================

(deftest test-entity-id-reference
  (testing "References via explicit entity IDs"
    (let [db (create-db)]
      ;; Create manager
      (transact! db [{:user/id 100 :user/name "Bob"}])

      ;; Create employee with reference to manager
      (transact! db [{:user/id 101
                      :user/name "Alice"
                      :user/manager 100}])

      ;; Query with join
      (let [result (query db [:find ?emp-name ?mgr-name
                              :where
                              [?emp :user/name ?emp-name]
                              [?emp :user/manager ?mgr]
                              [?mgr :user/name ?mgr-name]])]
        (is (= #{["Alice" "Bob"]} result))))))

(deftest test-lookup-ref
  (testing "References via lookup refs"
    (let [db (create-db)]
      ;; Create manager
      (transact! db [{:user/email "bob@example.com"
                      :user/name "Bob"}])

      ;; Create employee with lookup ref to manager
      (transact! db [{:user/email "alice@example.com"
                      :user/name "Alice"
                      :user/manager [:user/email "bob@example.com"]}])

      ;; Verify reference was resolved
      (let [alice (entity-by db :user/email "alice@example.com")
            bob (entity-by db :user/email "bob@example.com")
            manager-id (:user/manager alice)]
        (is (= (:db/id bob) manager-id))))))

(deftest test-nested-document-decomposition
  (testing "Nested maps decompose into separate entities"
    (let [db (create-db)
          result (transact! db [{:user/id 1
                                 :user/name "Alice"
                                 :user/address {:address/street "123 Main St"
                                                :address/city "Springfield"
                                                :address/zip "12345"}}])]

      ;; Check that address became a separate entity
      (let [user (entity db 1)
            address-id (:user/address user)
            address (entity db address-id)]

        (is (some? address-id))
        (is (= "123 Main St" (:address/street address)))
        (is (= "Springfield" (:address/city address)))
        (is (= "12345" (:address/zip address))))

      ;; Query with nested join
      (let [result (query db [:find ?name ?city
                              :where
                              [?u :user/name ?name]
                              [?u :user/address ?addr]
                              [?addr :address/city ?city]])]
        (is (= #{["Alice" "Springfield"]} result))))))

;; =============================================================================
;; Test Suite 5: Basic Queries
;; =============================================================================

(deftest test-simple-find-query
  (testing "Basic find query"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice" :user/age 30}
                     {:user/name "Bob" :user/age 25}
                     {:user/name "Charlie" :user/age 35}])

      (let [result (query db [:find ?name
                              :where [?e :user/name ?name]])]
        (is (= #{["Alice"] ["Bob"] ["Charlie"]} result))))))

(deftest test-query-with-predicate
  (testing "Query with predicates"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice" :user/age 30}
                     {:user/name "Bob" :user/age 25}
                     {:user/name "Charlie" :user/age 35}])

      ;; Find users over 30
      (let [result (query db [:find ?name
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
      (let [result (query db [:find ?name (count ?order)
                              :where
                              [?user :user/name ?name]
                              [?order :order/user ?user]])]
        (is (= #{["Alice" 2] ["Bob" 1]} result))))))

;; =============================================================================
;; Test Suite 6: Subscriptions and Incremental Updates
;; =============================================================================

(deftest test-basic-subscription
  (testing "Subscribe to query and receive incremental updates"
    (let [db (create-db)
          updates (atom [])
          sub (subscribe db
                         {:query [:find ?name
                                  :where [?e :user/name ?name]]
                          :mode :incremental
                          :callback (fn [diff] (swap! updates conj diff))})]

      ;; Initial subscription gets current results
      (is (= {:additions #{}
              :retractions #{}}
             (first @updates)))

      ;; Add entity
      (transact! db [{:user/name "Alice"}])

      ;; Should receive addition
      (Thread/sleep 50)  ; give time for async notification
      (is (= {:additions #{["Alice"]}
              :retractions #{}}
             (second @updates)))

      ;; Update entity name
      (transact! db [[:db/add (entity-id-by db :user/name "Alice")
                      :user/name "Alice Smith"]])

      ;; Should receive retraction of old + addition of new
      (Thread/sleep 50)
      (is (= {:additions #{["Alice Smith"]}
              :retractions #{["Alice"]}}
             (nth @updates 2)))

      ;; Clean up
      (unsubscribe sub))))

(deftest test-subscription-with-filter
  (testing "Subscription with predicate only notifies on matching results"
    (let [db (create-db)
          updates (atom [])
          sub (subscribe db
                         {:query [:find ?name ?age
                                  :where
                                  [?e :user/name ?name]
                                  [?e :user/age ?age]
                                  [(> ?age 30)]]
                          :mode :incremental
                          :callback (fn [diff] (swap! updates conj diff))})]

      ;; Add user under threshold - no notification
      (transact! db [{:user/name "Young" :user/age 25}])
      (Thread/sleep 50)
      (is (= 1 (count @updates)))  ; only initial empty result

      ;; Add user over threshold - notification
      (transact! db [{:user/name "Elder" :user/age 35}])
      (Thread/sleep 50)
      (is (= 2 (count @updates)))
      (is (= {:additions #{["Elder" 35]}
              :retractions #{}}
             (second @updates)))

      ;; Update age to drop below threshold - retraction
      (let [eid (entity-id-by db :user/name "Elder")]
        (transact! db [[:db/add eid :user/age 29]]))
      (Thread/sleep 50)
      (is (= {:additions #{}
              :retractions #{["Elder" 35]}}
             (nth @updates 2)))

      (unsubscribe sub))))

(deftest test-subscription-with-aggregation
  (testing "Subscription with aggregation updates incrementally"
    (let [db (create-db)
          updates (atom [])
          sub (subscribe db
                         {:query [:find ?user (sum ?amount)
                                  :where
                                  [?order :order/user ?user]
                                  [?order :order/amount ?amount]]
                          :mode :incremental
                          :callback (fn [diff] (swap! updates conj diff))})]

      ;; Add first order
      (transact! db [{:order/user 1 :order/amount 50}])
      (Thread/sleep 50)
      (is (= {:additions #{[1 50]}
              :retractions #{}}
             (second @updates)))

      ;; Add second order for same user
      (transact! db [{:order/user 1 :order/amount 30}])
      (Thread/sleep 50)
      ;; Should see retraction of old total, addition of new
      (is (= {:additions #{[1 80]}
              :retractions #{[1 50]}}
             (nth @updates 2)))

      (unsubscribe sub))))

(deftest test-subscription-time-dimension-filtering
  (testing "Subscription only fires on watched time dimensions"
    (let [db (create-db)]
      ;; Define dimensions
      (transact! db [{:dimension/name :time/valid
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/shipped
                      :dimension/type :instant
                      :dimension/indexed? true}])

      (let [updates (atom [])
            sub (subscribe db
                           {:query [:find ?order
                                    :where [?order :order/id _]]
                            :mode :incremental
                            :watch-dimensions [:time/system :time/shipped]
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Add order with shipped time - should notify
        (transact! db {:tx-data [{:order/id 100}]
                       :time-dimensions {:time/shipped #inst "2026-01-10"}})
        (Thread/sleep 50)
        (is (= 2 (count @updates)))  ; initial + addition

        ;; Update valid-time (not watched) - should NOT notify
        (transact! db {:tx-data [[:db/add (entity-id-by db :order/id 100)
                                  :order/status :valid]]
                       :time-dimensions {:time/valid #inst "2026-01-15"}})
        (Thread/sleep 50)
        (is (= 2 (count @updates)))  ; no new update

        ;; Update with shipped-time (watched) - should notify
        (transact! db {:tx-data [[:db/add (entity-id-by db :order/id 100)
                                  :order/status :delivered]]
                       :time-dimensions {:time/shipped #inst "2026-01-11"}})
        (Thread/sleep 50)
        (is (= 3 (count @updates)))  ; new update received

        (unsubscribe sub)))))

(deftest test-subscription-backpressure
  (testing "Subscription blocks transaction if buffer full (backpressure)"
    (let [db (create-db)
          processed (atom [])
          ;; Create subscription with small buffer
          sub (subscribe db
                         {:query [:find ?n :where [?e :num ?n]]
                          :mode :incremental
                          :buffer-size 2
                          :backpressure :block
                          :callback (fn [diff]
                                      (Thread/sleep 100)  ; slow consumer
                                      (swap! processed conj diff))})]

      ;; Rapidly add many entities
      (let [start (System/currentTimeMillis)]
        (dotimes [i 10]
          (transact! db [{:num i}]))
        (let [elapsed (- (System/currentTimeMillis) start)]
          ;; Should have blocked due to backpressure
          (is (> elapsed 200))))  ; at least 2 * 100ms sleeps

      (unsubscribe sub))))

;; =============================================================================
;; Test Suite 7: Recursive Queries
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
      (let [result (query db [:find ?name
                              :where
                              [?ceo :user/name "CEO"]
                              [?report :user/reports-to+ ?ceo]
                              [?report :user/name ?name]])]
        (is (= #{["VP"] ["Manager"] ["IC1"] ["IC2"]} result))))))

(deftest test-recursive-incremental-update
  (testing "Recursive query subscription updates on hierarchy change"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/name "CEO"}
                     {:user/id 2 :user/name "VP" :user/reports-to 1}
                     {:user/id 3 :user/name "Manager" :user/reports-to 2}])

      (let [updates (atom [])
            sub (subscribe db
                           {:query [:find ?name
                                    :where
                                    [?ceo :user/name "CEO"]
                                    [?report :user/reports-to+ ?ceo]
                                    [?report :user/name ?name]]
                            :mode :incremental
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Initial state
        (is (= {:additions #{["VP"] ["Manager"]}
                :retractions #{}}
               (first @updates)))

        ;; Add new IC under Manager
        (transact! db [{:user/id 4 :user/name "IC" :user/reports-to 3}])
        (Thread/sleep 50)
        (is (= {:additions #{["IC"]}
                :retractions #{}}
               (second @updates)))

        ;; Move Manager to report directly to CEO (org restructure)
        (transact! db [[:db/add 3 :user/reports-to 1]])
        (Thread/sleep 50)
        ;; IC should still be in results (still under CEO transitively)
        ;; But query should have recomputed
        (let [current-result (query db [:find ?name
                                        :where
                                        [?ceo :user/name "CEO"]
                                        [?report :user/reports-to+ ?ceo]
                                        [?report :user/name ?name]])]
          (is (= #{["VP"] ["Manager"] ["IC"]} current-result)))

        (unsubscribe sub)))))

;; =============================================================================
;; Test Suite 8: Aggregations
;; =============================================================================

(deftest test-aggregation-count
  (testing "Count aggregation"
    (let [db (create-db)]
      (transact! db [{:user/name "Alice"}
                     {:user/name "Bob"}
                     {:user/name "Charlie"}])

      (let [result (query db [:find (count ?e)
                              :where [?e :user/name _]])]
        (is (= [[3]] result))))))

(deftest test-aggregation-sum
  (testing "Sum aggregation"
    (let [db (create-db)]
      (transact! db [{:order/user 1 :order/amount 50}
                     {:order/user 1 :order/amount 30}
                     {:order/user 1 :order/amount 20}])

      (let [result (query db [:find (sum ?amount)
                              :where
                              [?order :order/user 1]
                              [?order :order/amount ?amount]])]
        (is (= [[100]] result))))))

(deftest test-aggregation-with-grouping
  (testing "Aggregation with grouping"
    (let [db (create-db)]
      (transact! db [{:order/user 1 :order/amount 50}
                     {:order/user 1 :order/amount 30}
                     {:order/user 2 :order/amount 100}
                     {:order/user 2 :order/amount 25}])

      (let [result (query db [:find ?user (sum ?amount)
                              :where
                              [?order :order/user ?user]
                              [?order :order/amount ?amount]])]
        (is (= #{[1 80] [2 125]} result))))))

(deftest test-custom-aggregate-function
  (testing "Custom aggregation function"
    (let [db (create-db)]
      (transact! db [{:product/name "Widget" :product/price 10}
                     {:product/name "Gadget" :product/price 20}
                     {:product/name "Doohickey" :product/price 30}])

      ;; Custom avg function
      (let [result (query db [:find (avg ?price)
                              :where [?p :product/price ?price]])]
        (is (= [[20]] result))))))

;; =============================================================================
;; Test Suite 9: Constraints and Validation
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
      ;; Setup
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
           (transact! db {:tx-data [[:db/add 100 :order/status :updated]]
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
      (let [result (query db [:find ?duration
                              :where
                              [?order :order/id 100]
                              [?order :order/id _ :at/delivery-duration ?duration]])]
        ;; 4 days in milliseconds
        (is (= [[345600000]] result))))))

(deftest test-user-defined-constraint-function
  (testing "User-defined constraint function"
    (let [db (create-db)
          ;; Custom constraint: value must be positive
          positive? (fn [delta]
                      (or (nil? (:new-value delta))
                          (pos? (:new-value delta))))]

      ;; Register constraint
      (add-constraint! db :order/amount positive?)

      ;; Valid transaction
      (is (some? (transact! db [{:order/amount 100}])))

      ;; Invalid transaction
      (is (thrown-with-msg?
           Exception
           #"Constraint violation"
           (transact! db [{:order/amount -50}]))))))

;; =============================================================================
;; Test Suite 10: Cross-Dimensional Queries
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
      (let [result (query db [:find ?order
                              :where
                              [?order :order/id _ :at/ordered ?ordered]
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

      ;; Complex scenario: order created at different times
      (transact! db {:tx-data [{:order/id 100 :order/status :created}]
                     :time-dimensions {:time/valid #inst "2026-01-01"}})

      (transact! db {:tx-data [[:db/add 100 :order/status :shipped]]
                     :time-dimensions {:time/valid #inst "2026-01-05"
                                       :time/shipped #inst "2026-01-05"}})

      (transact! db {:tx-data [[:db/add 100 :order/status :delivered]]
                     :time-dimensions {:time/valid #inst "2026-01-10"}})

      ;; Query: what was order status at valid-time 2026-01-07?
      (let [result (query db {:query [:find ?status
                                      :where [100 :order/status ?status]]
                              :as-of {:time/valid #inst "2026-01-07"}})]
        (is (= #{[:shipped]} result)))

      ;; Query: orders that had shipped-time set as-of valid-time 2026-01-03
      (let [result (query db {:query [:find ?order
                                      :where
                                      [?order :order/id _ :at/shipped ?st]]
                              :as-of {:time/valid #inst "2026-01-03"}})]
        (is (= #{} result)))  ; shipped-time wasn't set yet at valid-time 2026-01-03

      ;; Query as-of valid-time 2026-01-07
      (let [result (query db {:query [:find ?order ?st
                                      :where
                                      [?order :order/id _]
                                      [?order :order/id _ :at/shipped ?st]]
                              :as-of {:time/valid #inst "2026-01-07"}})]
        (is (= #{[100 #inst "2026-01-05"]} result))))))

(deftest test-incomparable-timestamps
  (testing "Queries with incomparable timestamps (missing dimensions)"
    (let [db (create-db)]
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
      (let [result (query db {:query [:find ?id
                                      :where [?e :entity/id ?id]]
                              :as-of {:time/valid #inst "2026-01-10"}})]
        (is (= #{["A"]} result)))

      ;; Query as-of shipped-time should only return B
      (let [result (query db {:query [:find ?id
                                      :where [?e :entity/id ?id]]
                              :as-of {:time/shipped #inst "2026-01-10"}})]
        (is (= #{["B"]} result))))))

;; =============================================================================
;; Test Suite 11: Complex Supply Chain Scenario
;; =============================================================================

(deftest test-supply-chain-end-to-end
  (testing "Complete supply chain scenario with multiple time dimensions"
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
                   :dimension/constraints [{:type :ordering :after :time/shipped}]}
                  {:dimension/name :time/received
                   :dimension/type :instant
                   :dimension/indexed? true
                   :dimension/constraints [{:type :ordering :after :time/delivered}]}])

      ;; Step 2: Customer places order
      (let [result (transact! db {:tx-data [{:order/id "ORD-100"
                                             :order/customer "Alice"
                                             :order/items ["Widget" "Gadget"]
                                             :order/status :ordered}]
                                  :time-dimensions {:time/ordered #inst "2026-01-01T10:00:00Z"}
                                  :tx-meta {:tx/source "web-ui"}})]
        (is (some? (:tx-id result))))

      ;; Step 3: Subscribe to order status changes
      (let [updates (atom [])
            sub (subscribe db
                           {:query [:find ?order ?status
                                    :where
                                    [?order :order/id "ORD-100"]
                                    [?order :order/status ?status]]
                            :mode :incremental
                            :watch-dimensions [:time/system :time/ordered
                                               :time/shipped :time/delivered]
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Step 4: Warehouse ships order
        (transact! db {:tx-data [[:db/add "ORD-100" :order/status :shipped]]
                       :time-dimensions {:time/shipped #inst "2026-01-02T14:30:00Z"}
                       :tx-meta {:tx/source "warehouse-system"}})

        (Thread/sleep 50)

        ;; Step 5: Carrier delivers order
        (transact! db {:tx-data [[:db/add "ORD-100" :order/status :delivered]]
                       :time-dimensions {:time/delivered #inst "2026-01-05T09:15:00Z"}
                       :tx-meta {:tx/source "carrier-system"}})

        (Thread/sleep 50)

        ;; Step 6: Customer confirms receipt
        (transact! db {:tx-data [[:db/add "ORD-100" :order/status :received]]
                       :time-dimensions {:time/received #inst "2026-01-05T16:00:00Z"}
                       :tx-meta {:tx/source "mobile-app"}})

        (Thread/sleep 50)

        ;; Verify updates received
        (is (>= (count @updates) 4))

        (unsubscribe sub))

      ;; Step 7: Query orders in transit (shipped but not delivered) as-of 2026-01-03
      (let [result (query db {:query [:find ?order
                                      :where
                                      [?order :order/id _ :at/shipped ?st]
                                      (not [?order :order/id _ :at/delivered ?dt])]
                              :as-of {:time/shipped #inst "2026-01-03T23:59:59Z"
                                      :time/delivered #inst "2026-01-03T00:00:00Z"}})]
        (is (= #{["ORD-100"]} result)))

      ;; Step 8: Query shipping duration
      (let [result (query db [:find ?order ?duration
                              :where
                              [?order :order/id _]
                              [?order :order/id _ :at/shipped ?st]
                              [?order :order/id _ :at/delivered ?dt]
                              [(- ?dt ?st) ?duration]])]
        ;; ~2.75 days in milliseconds
        (is (= 1 (count result)))
        (is (< 200000000 (second (first result)) 250000000)))

      ;; Step 9: Retroactive correction - update ordered time
      (let [result (transact! db {:tx-data [[:db/add "ORD-100" :order/corrected true]]
                                  :time-dimensions {:time/ordered #inst "2026-01-01T09:00:00Z"}})]
        (is (some? result)))

      ;; Step 10: Historical query - what did we know on 2026-01-04?
      (let [result (query db {:query [:find ?order ?status
                                      :where
                                      [?order :order/id "ORD-100"]
                                      [?order :order/status ?status]]
                              :as-of {:time/system #inst "2026-01-04T00:00:00Z"}})]
        (is (= #{["ORD-100" :shipped]} result))))))

;; =============================================================================
;; Test Suite 12: Edge Cases and Error Conditions
;; =============================================================================

(deftest test-query-nonexistent-entity
  (testing "Querying non-existent entity returns empty"
    (let [db (create-db)
          result (query db [:find ?name
                            :where [999 :user/name ?name]])]
      (is (= #{} result)))))

(deftest test-retract-nonexistent-attribute
  (testing "Retracting non-existent attribute is no-op"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/name "Alice"}])

      (let [result (transact! db [[:db/retract 1 :user/age 25]])]
        ;; No deltas since attribute didn't exist
        (is (= 0 (count (:deltas result))))))))

(deftest test-circular-reference
  (testing "Circular references are allowed"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/name "Alice"}
                     {:user/id 2 :user/name "Bob"}])

      ;; Create circular reference
      (transact! db [[:db/add 1 :user/friend 2]
                     [:db/add 2 :user/friend 1]])

      ;; Verify both directions
      (is (= 2 (:user/friend (entity db 1))))
      (is (= 1 (:user/friend (entity db 2)))))))

(deftest test-self-reference
  (testing "Self-reference is allowed"
    (let [db (create-db)]
      (transact! db [{:user/id 1 :user/name "Alice"}])
      (transact! db [[:db/add 1 :user/manager 1]])

      (is (= 1 (:user/manager (entity db 1)))))))

(deftest test-undefined-time-dimension
  (testing "Using undefined time dimension fails"
    (let [db (create-db)]
      (is (thrown-with-msg?
           Exception
           #"Undefined time dimension"
           (transact! db {:tx-data [{:order/id 100}]
                          :time-dimensions {:time/undefined #inst "2026-01-01"}}))))))

(deftest test-concurrent-transactions
  (testing "Concurrent transactions are serializable"
    (let [db (create-db)]
      (transact! db [{:counter/id 1 :counter/value 0}])

      ;; Run 10 concurrent increments
      (let [futures (doall
                     (for [_ (range 10)]
                       (future
                         (let [current (:counter/value (entity db 1))]
                           (Thread/sleep (rand-int 10))
                           (transact! db [[:db/add 1 :counter/value (inc current)]])))))]
        (doseq [f futures] @f)

        ;; Final value should be 10 (all increments applied)
        (is (= 10 (:counter/value (entity db 1))))))))

(deftest test-large-collection
  (testing "Large collections are handled efficiently"
    (let [db (create-db)
          large-vec (vec (range 10000))]

      ;; Insert large vector
      (transact! db [{:data/id 1 :data/values large-vec}])

      ;; Verify stored correctly
      (is (= large-vec (:data/values (entity db 1))))

      ;; Append to large vector
      (let [result (transact! db [[:db/add 1 :data/values 10000]])]
        (is (contains-delta? result
                             {:entity 1
                              :attribute :data/values
                              :collection-op :conj
                              :collection-index 10000
                              :collection-element 10000}))))))

(deftest test-null-values
  (testing "Nil values are handled correctly"
    (let [db (create-db)]
      ;; Can't explicitly set attribute to nil (use retract instead)
      (is (thrown?
           Exception
           (transact! db [{:user/id 1 :user/name nil}])))

      ;; Nil in collection is treated as absence
      (transact! db [{:user/id 1 :user/tags ["clojure" nil "rust"]}])
      (is (= ["clojure" "rust"] (:user/tags (entity db 1)))))))

(deftest test-empty-transaction
  (testing "Empty transaction is allowed"
    (let [db (create-db)
          result (transact! db [])]
      (is (some? (:tx-id result)))
      (is (= 0 (count (:deltas result)))))))
