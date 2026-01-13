(ns dfdb.usecase-subscriptions-test
  "Comprehensive Subscription Use Cases (Phase 3).
  Demonstrates differential dataflow subscriptions for real-time updates.
  These tests define expected behavior for Phase 3 implementation."
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [clojure.core.async :as async]
            [dfdb.core :refer :all]))

;; =============================================================================
;; PHASE 3 SPECIFICATION - Subscription Use Cases
;; =============================================================================
;;
;; These tests define the expected behavior of subscriptions.
;; They will fail until Phase 3 (Differential Dataflow) is implemented.
;;
;; Subscription Model:
;; - Subscribe to Datalog query
;; - Receive incremental diffs (additions/retractions)
;; - NOT full re-evaluation - true differential dataflow
;; - Supports: callbacks, core.async channels, manifold streams
;;
;; =============================================================================

(deftest ^:phase3 test-subscription-basic-updates
  (testing "Basic subscription receives incremental updates"
    (let [db (create-db)
          updates (atom [])]

      ;; Subscribe to query
      (let [sub (subscribe db
                           {:query '[:find ?name
                                     :where [?e :user/name ?name]]
                            :mode :incremental
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Initial state (empty)
        (is (= {:additions #{}
                :retractions #{}}
               (first @updates)))

        ;; Add first user
        (transact! db [{:user/name "Alice"}])

        ;; Should receive addition
        (is (= {:additions #{["Alice"]}
                :retractions #{}}
               (second @updates)))

        ;; Add second user
        (transact! db [{:user/name "Bob"}])

        ;; Should receive only new addition
        (is (= {:additions #{["Bob"]}
                :retractions #{}}
               (nth @updates 2)))

        ;; Update user name
        (transact! db [[:db/add 1 :user/name "Alice Smith"]])

        ;; Should receive retraction of old + addition of new
        (is (= {:additions #{["Alice Smith"]}
                :retractions #{["Alice"]}}
               (nth @updates 3)))

        ;; Clean up
        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-with-filter
  (testing "Subscription only notifies on matching results"
    (let [db (create-db)
          updates (atom [])]

      ;; Subscribe to high-value orders only
      (let [sub (subscribe db
                           {:query '[:find ?order-id ?total
                                     :where
                                     [?order :order/id ?order-id]
                                     [?order :order/total ?total]
                                     [(> ?total 1000)]]
                            :mode :incremental
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Small order - no notification
        (transact! db [{:order/id "ORD-1" :order/total 50}])
        (is (= 1 (count @updates)))  ; Only initial empty state

        ;; Large order - notification
        (transact! db [{:order/id "ORD-2" :order/total 5000}])
        (is (= 2 (count @updates)))
        (is (= {:additions #{["ORD-2" 5000]}
                :retractions #{}}
               (second @updates)))

        ;; Update to drop below threshold - retraction
        (transact! db [[:db/add [:order/id "ORD-2"] :order/total 500]])
        (is (= {:additions #{}
                :retractions #{["ORD-2" 5000]}}
               (nth @updates 2)))

        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-aggregation-updates
  (testing "Subscription with aggregation updates incrementally"
    (let [db (create-db)
          updates (atom [])]

      ;; Subscribe to total order value per customer
      (let [sub (subscribe db
                           {:query '[:find ?customer (sum ?total)
                                     :where
                                     [?order :order/customer ?customer]
                                     [?order :order/total ?total]]
                            :mode :incremental
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; First order for customer 1
        (transact! db [{:order/customer 1 :order/total 100}])

        ;; Should see initial total
        (is (= {:additions #{[1 100]}
                :retractions #{}}
               (second @updates)))

        ;; Second order for customer 1
        (transact! db [{:order/customer 1 :order/total 50}])

        ;; Should see retraction of old total, addition of new
        (is (= {:additions #{[1 150]}
                :retractions #{[1 100]}}
               (nth @updates 2)))

        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-recursive-query
  (testing "Subscription to recursive query updates on hierarchy change"
    (let [db (create-db)
          updates (atom [])]

      ;; Initial org structure
      (transact! db [{:emp/id 1 :emp/name "CEO"}
                     {:emp/id 2 :emp/name "VP" :emp/reports-to 1}
                     {:emp/id 3 :emp/name "Manager" :emp/reports-to 2}])

      ;; Subscribe to all reports under CEO
      (let [sub (subscribe db
                           {:query '[:find ?name
                                     :where
                                     [?ceo :emp/name "CEO"]
                                     [?report :emp/reports-to+ ?ceo]
                                     [?report :emp/name ?name]]
                            :mode :incremental
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Initial state
        (is (= {:additions #{["VP"] ["Manager"]}
                :retractions #{}}
               (first @updates)))

        ;; Add new IC under Manager
        (transact! db [{:emp/id 4 :emp/name "IC" :emp/reports-to 3}])

        ;; Should receive addition (IC now reports to CEO transitively)
        (is (= {:additions #{["IC"]}
                :retractions #{}}
               (second @updates)))

        ;; Org restructure - Manager now reports directly to CEO
        (transact! db [[:db/add 3 :emp/reports-to 1]])

        ;; IC still under CEO (different path) - no change to result set
        ;; But internal computation graph updated incrementally
        (is (= 2 (count @updates)))  ; No new update

        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-multi-dimensional-time
  (testing "Subscription with multi-dimensional time filtering"
    (let [db (create-db)
          updates (atom [])]

      ;; Setup dimensions
      (transact! db [{:dimension/name :time/shipped :dimension/type :instant :dimension/indexed? true}])

      ;; Subscribe to shipped orders only
      (let [sub (subscribe db
                           {:query '[:find ?order
                                     :where
                                     [?order :order/id _]
                                     [?order :order/status :shipped]]
                            :mode :incremental
                            :watch-dimensions [:time/system :time/shipped]  ; Only notify on these
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Order placed (no ship time) - no notification
        (transact! db [{:order/id "ORD-1" :order/status :pending}])
        (is (= 1 (count @updates)))  ; Only initial

        ;; Order shipped - notification
        (transact! db {:tx-data [[:db/add "ORD-1" :order/status :shipped]]
                       :time-dimensions {:time/shipped #inst "2026-01-20"}})
        (is (= 2 (count @updates)))

        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-with-transformation
  (testing "Subscription with post-query transformation"
    (let [db (create-db)
          updates (atom [])]

      ;; Subscribe with transformation function
      (let [sub (subscribe db
                           {:query '[:find ?email ?score
                                     :where
                                     [?user :user/email ?email]
                                     [?user :user/score ?score]]
                            :mode :incremental
                            :transform-fn (fn [diff]
                                            ;; Only pass through high scores
                                            (update diff :additions
                                                    (fn [rows]
                                                      (set (filter #(> (second %) 80) rows)))))
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Low score user - filtered out
        (transact! db [{:user/email "low@example.com" :user/score 60}])
        (is (= 1 (count @updates)))  ; Only initial

        ;; High score user - passes filter
        (transact! db [{:user/email "high@example.com" :user/score 95}])
        (is (= 2 (count @updates)))
        (is (= {:additions #{["high@example.com" 95]}
                :retractions #{}}
               (second @updates)))

        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-backpressure
  (testing "Subscription handles backpressure correctly"
    (let [db (create-db)
          processed (atom [])
          slow-processor (fn [diff]
                           (Thread/sleep 100)  ; Slow consumer
                           (swap! processed conj diff))]

      (let [sub (subscribe db
                           {:query '[:find ?n :where [?e :num ?n]]
                            :mode :incremental
                            :buffer-size 5
                            :backpressure :block  ; Block tx if buffer full
                            :callback slow-processor})]

        ;; Rapidly add many entities
        (let [start (System/currentTimeMillis)]
          (dotimes [i 20]
            (transact! db [{:num i}]))
          (let [elapsed (- (System/currentTimeMillis) start)]
            ;; Should have blocked waiting for slow consumer
            (is (> elapsed 500))))  ; At least 5 * 100ms of blocking

        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-multiple-subscribers-same-query
  (testing "Multiple subscriptions to same query share computation"
    (let [db (create-db)
          updates-1 (atom [])
          updates-2 (atom [])
          updates-3 (atom [])]

      ;; Three subscriptions to identical query
      (let [query-form '[:find ?name :where [?e :user/name ?name]]
            sub1 (subscribe db {:query query-form
                                :mode :incremental
                                :callback (fn [diff] (swap! updates-1 conj diff))})
            sub2 (subscribe db {:query query-form
                                :mode :incremental
                                :callback (fn [diff] (swap! updates-2 conj diff))})
            sub3 (subscribe db {:query query-form
                                :mode :incremental
                                :callback (fn [diff] (swap! updates-3 conj diff))})]

        ;; Add user
        (transact! db [{:user/name "Alice"}])

        ;; All three should receive same update
        (is (= (second @updates-1) (second @updates-2) (second @updates-3)))
        (is (= {:additions #{["Alice"]} :retractions #{}}
               (second @updates-1)))

        ;; Clean up
        (unsubscribe sub1)
        (unsubscribe sub2)
        (unsubscribe sub3)))))

(deftest ^:phase3 test-subscription-core-async
  (testing "Subscription delivery via core.async channel"
    (let [db (create-db)]

      ;; Subscribe with core.async
      (let [sub (subscribe db
                           {:query '[:find ?name :where [?e :user/name ?name]]
                            :mode :incremental
                            :delivery :core-async})]  ; Returns channel

        ;; Get channel
        (let [ch (:channel sub)]
          ;; Initial state
          (let [initial (async/<!! ch)]
            (is (= {:additions #{} :retractions #{}} initial)))

          ;; Add user
          (transact! db [{:user/name "Alice"}])

          ;; Receive update from channel
          (let [update (async/<!! ch)]
            (is (= {:additions #{["Alice"]} :retractions #{}} update)))

          (unsubscribe sub))))))

(deftest ^:phase3 test-subscription-time-dimension-selective
  (testing "Subscription triggers only on specific time dimensions"
    (let [db (create-db)
          updates (atom [])]

      (transact! db [{:dimension/name :time/shipped :dimension/type :instant :dimension/indexed? true}
                     {:dimension/name :time/delivered :dimension/type :instant :dimension/indexed? true}])

      ;; Subscribe watching only shipped-time
      (let [sub (subscribe db
                           {:query '[:find ?order
                                     :where [?order :order/id _]]
                            :mode :incremental
                            :watch-dimensions [:time/system :time/shipped]  ; NOT :time/delivered
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Add order with shipped time - triggers
        (transact! db {:tx-data [{:order/id "ORD-1"}]
                       :time-dimensions {:time/shipped #inst "2026-01-20"}})
        (is (= 2 (count @updates)))

        ;; Update with delivered time (not watched) - NO trigger
        (transact! db {:tx-data [[:db/add "ORD-1" :order/delivered true]]
                       :time-dimensions {:time/delivered #inst "2026-01-25"}})
        (is (= 2 (count @updates)))  ; No new update!

        ;; Update with shipped time again - triggers
        (transact! db {:tx-data [[:db/add "ORD-1" :order/tracking "ABC123"]]
                       :time-dimensions {:time/shipped #inst "2026-01-21"}})
        (is (= 3 (count @updates)))

        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-dashboard-materialized-view
  (testing "Dashboard with real-time materialized views"
    (let [db (create-db)
          dashboard-state (atom {})]

      ;; Subscribe to multiple queries for dashboard
      (let [total-revenue-sub
            (subscribe db {:query '[:find (sum ?total)
                                    :where [?order :order/total ?total]]
                           :mode :incremental
                           :callback (fn [diff]
                                       (swap! dashboard-state assoc :total-revenue
                                              (first (first (:additions diff)))))})

            order-count-sub
            (subscribe db {:query '[:find (count ?order)
                                    :where [?order :order/id _]]
                           :mode :incremental
                           :callback (fn [diff]
                                       (swap! dashboard-state assoc :order-count
                                              (first (first (:additions diff)))))})

            avg-order-sub
            (subscribe db {:query '[:find (avg ?total)
                                    :where [?order :order/total ?total]]
                           :mode :incremental
                           :callback (fn [diff]
                                       (swap! dashboard-state assoc :avg-order
                                              (first (first (:additions diff)))))})]

        ;; Add orders
        (transact! db [{:order/id "ORD-1" :order/total 100}])
        (Thread/sleep 50)

        (transact! db [{:order/id "ORD-2" :order/total 200}])
        (Thread/sleep 50)

        ;; Dashboard state updated incrementally
        (is (= 300 (:total-revenue @dashboard-state)))
        (is (= 2 (:order-count @dashboard-state)))
        (is (= 150.0 (:avg-order @dashboard-state)))

        (unsubscribe total-revenue-sub)
        (unsubscribe order-count-sub)
        (unsubscribe avg-order-sub)))))

(deftest ^:phase3 test-subscription-event-sourcing
  (testing "Event sourcing with subscription-based projections"
    (let [db (create-db)
          user-projection (atom {})]

      ;; Subscribe to user projection
      (let [sub (subscribe db
                           {:query '[:find ?user-id ?name ?email ?status
                                     :where
                                     [?user :user/id ?user-id]
                                     [?user :user/name ?name]
                                     [?user :user/email ?email]
                                     [?user :user/status ?status]]
                            :mode :incremental
                            :callback (fn [diff]
                                        ;; Update projection
                                        (doseq [[id name email status] (:additions diff)]
                                          (swap! user-projection assoc id
                                                 {:name name :email email :status status}))
                                        (doseq [[id _ _ _] (:retractions diff)]
                                          (swap! user-projection dissoc id)))})]

        ;; Event: UserCreated
        (transact! db [{:user/id 1
                        :user/name "Alice"
                        :user/email "alice@example.com"
                        :user/status :active}])
        (Thread/sleep 50)

        (is (= {:name "Alice" :email "alice@example.com" :status :active}
               (get @user-projection 1)))

        ;; Event: EmailChanged
        (transact! db [[:db/add 1 :user/email "alice.smith@example.com"]])
        (Thread/sleep 50)

        (is (= "alice.smith@example.com" (:email (get @user-projection 1))))

        (unsubscribe sub)))))

(deftest ^:phase3 test-subscription-reactive-ui
  (testing "Reactive UI components with subscriptions"
    (let [db (create-db)
          chat-messages (atom [])
          online-users (atom #{})]

      ;; Chat message subscription
      (let [msg-sub (subscribe db
                               {:query '[:find ?msg-text ?user ?timestamp
                                         :where
                                         [?msg :message/channel "general"]
                                         [?msg :message/text ?msg-text]
                                         [?msg :message/user ?user]
                                         [?msg :message/timestamp ?timestamp]]
                                :mode :incremental
                                :callback (fn [diff]
                                            (doseq [msg (:additions diff)]
                                              (swap! chat-messages conj msg)))})

            ;; Online users subscription
            user-sub (subscribe db
                                {:query '[:find ?user
                                          :where
                                          [?u :user/name ?user]
                                          [?u :user/online true]]
                                 :mode :incremental
                                 :callback (fn [diff]
                                             (swap! online-users set/union (:additions diff))
                                             (swap! online-users set/difference (:retractions diff)))})]

        ;; User comes online
        (transact! db [{:user/name "Alice" :user/online true}])
        (Thread/sleep 50)
        (is (contains? @online-users ["Alice"]))

        ;; User sends message
        (transact! db [{:message/channel "general"
                        :message/user "Alice"
                        :message/text "Hello!"
                        :message/timestamp #inst "2026-01-20"}])
        (Thread/sleep 50)
        (is (= 1 (count @chat-messages)))

        ;; User goes offline
        (transact! db [[:db/retract [:user/name "Alice"] :user/online true]])
        (Thread/sleep 50)
        (is (not (contains? @online-users ["Alice"])))

        (unsubscribe msg-sub)
        (unsubscribe user-sub)))))

(deftest ^:phase3 test-subscription-performance-1000-subscribers
  (testing "System handles 1000+ concurrent subscriptions"
    (let [db (create-db)
          subscriptions (atom [])]

      ;; Create 1000 subscriptions to different queries
      (dotimes [i 1000]
        (let [sub (subscribe db
                             {:query `[:find ?name
                                       :where
                                       [?e :user/name ?name]
                                       [?e :user/id ~i]]  ; Each watches different user
                              :mode :incremental
                              :callback (fn [diff] nil)})]  ; No-op
          (swap! subscriptions conj sub)))

      ;; Add 100 users
      (dotimes [i 100]
        (transact! db [{:user/id i :user/name (str "User-" i)}]))

      ;; Each subscription should have received relevant updates
      ;; System should have shared computation where possible

      ;; Clean up
      (doseq [sub @subscriptions]
        (unsubscribe sub))

      (is (= 1000 (count @subscriptions))))))

(deftest ^:phase3 test-subscription-retroactive-history
  (testing "Subscription behavior with retroactive updates"
    (let [db (create-db)
          updates (atom [])]

      (transact! db [{:dimension/name :time/valid :dimension/type :instant :dimension/indexed? true}])

      ;; Subscribe with option to see historical updates
      (let [sub (subscribe db
                           {:query '[:find ?data
                                     :where [?e :record/data ?data]]
                            :mode :incremental
                            :see-retroactive? true  ; See history rewrites
                            :callback (fn [diff] (swap! updates conj diff))})]

        ;; Initial record
        (transact! db {:tx-data [{:record/data "v1"}]
                       :time-dimensions {:time/valid #inst "2026-01-01"}})

        ;; Retroactive correction
        (transact! db {:tx-data [[:db/add 1 :record/data "v1-corrected"]]
                       :time-dimensions {:time/valid #inst "2026-01-01"}})  ; Same valid-time

        ;; Should receive update even though it's "in the past"
        (is (>= (count @updates) 3))  ; Initial + v1 + correction

        (unsubscribe sub)))))

;; =============================================================================
;; Helper Functions (Phase 3)
;; =============================================================================

(defn subscribe
  "Subscribe to query - Phase 3 implementation."
  [db config]
  (throw (ex-info "Subscribe not yet implemented - Phase 3" {:config config})))

(defn unsubscribe
  "Unsubscribe - Phase 3 implementation."
  [sub]
  (throw (ex-info "Unsubscribe not yet implemented - Phase 3" {:sub sub})))
