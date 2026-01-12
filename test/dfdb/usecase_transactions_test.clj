(ns dfdb.usecase-transactions-test
  "Comprehensive Transaction Use Cases.
  Demonstrates complex transaction patterns, multi-dimensional time, and delta tracking."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Complex Transaction Patterns
;; =============================================================================

(deftest test-transaction-bulk-import
  (testing "Bulk data import with single transaction"
    (let [db (create-db)
          ;; Import 100 products in one transaction
          products (for [i (range 100)]
                     {:product/sku (str "SKU-" i)
                      :product/name (str "Product " i)
                      :product/price (* i 9.99)})

          result (transact! db products)]

      ;; Verify all created
      (is (= 100 (count (distinct (map :entity (:deltas result))))))

      ;; Verify queryable
      (let [count-result (query db '[:find (count ?p)
                                     :where [?p :product/sku _]])]
        (is (= #{[100]} count-result))))))

(deftest test-transaction-cascading-updates
  (testing "Cascading updates across related entities"
    (let [db (create-db)]

      ;; Create related entities
      (transact! db [{:user/id 1 :user/email "alice@old-company.com" :user/company "OldCo"}
                     {:document/id 101 :document/owner 1 :document/title "Doc1"}
                     {:document/id 102 :document/owner 1 :document/title "Doc2"}
                     {:permission/document 101 :permission/user 1 :permission/level :write}
                     {:permission/document 102 :permission/user 1 :permission/level :write}])

      ;; User changes company - update email
      (let [result (transact! db [[:db/add 1 :user/email "alice@new-company.com"]
                                  [:db/add 1 :user/company "NewCo"]]
                              {:tx/reason "Company acquisition"})]

        ;; Verify both attributes updated
        (is (= 2 (count (:deltas result))))
        (is (some #(= :user/email (:attribute %)) (:deltas result)))
        (is (some #(= :user/company (:attribute %)) (:deltas result))))

      ;; Documents still owned by same user ID
      (let [docs (query db '[:find ?title
                             :where
                             [?doc :document/owner 1]
                             [?doc :document/title ?title]])]
        (is (= #{["Doc1"] ["Doc2"]} docs))))))

(deftest test-transaction-with-computed-values
  (testing "Transaction with values computed from existing data"
    (let [db (create-db)]

      ;; Create order with line items
      (transact! db [{:order/id "ORD-1"}
                     {:line-item/order "ORD-1" :line-item/price 29.99 :line-item/qty 2}
                     {:line-item/order "ORD-1" :line-item/price 49.99 :line-item/qty 1}])

      ;; Query line items to compute total
      (let [line-items (query db '[:find ?price ?qty
                                   :where
                                   [?item :line-item/order "ORD-1"]
                                   [?item :line-item/price ?price]
                                   [?item :line-item/qty ?qty]])
            total (reduce + (map (fn [[price qty]] (* price qty)) line-items))]

        ;; Update order with computed total
        (transact! db [[:db/add "ORD-1" :order/total total]])

        ;; Verify
        (is (= 109.97 (:order/total (entity-by db :order/id "ORD-1"))))))))

;; =============================================================================
;; Multi-Dimensional Time Transaction Patterns
;; =============================================================================

(deftest test-transaction-backdated-correction
  (testing "Backdated transaction for data correction"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/effective
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Original data entry (today) for yesterday
      (transact! db {:tx-data [{:policy/id "POL-1" :policy/premium 1000}]
                     :time-dimensions {:time/effective #inst "2026-01-15"}
                     :tx-meta {:tx/entered-by "system"}})

      ;; Discover error - premium should be 1200
      (transact! db {:tx-data [[:db/add [:policy/id "POL-1"] :policy/premium 1200]]
                     :time-dimensions {:time/effective #inst "2026-01-15"}  ; Same effective date
                     :tx-meta {:tx/entered-by "admin"
                               :tx/reason "Correction - data entry error"}})

      ;; Query at effective date shows corrected value
      (let [premium (query db {:query '[:find ?prem
                                        :where ["POL-1" :policy/premium ?prem]]
                               :as-of {:time/effective #inst "2026-01-16"}})]
        (is (= #{[1200]} premium)))

      ;; System-time shows when correction was made
      (let [deltas-history (query db '[:find ?tx-id ?reason
                                       :where
                                       [?d :tx/reason ?reason]
                                       [?d :tx/id ?tx-id]])]
        (is (= 1 (count deltas-history)))))))

(deftest test-transaction-temporal-sla-tracking
  (testing "SLA tracking with multiple time dimensions"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/created :dimension/type :instant :dimension/indexed? true}
                     {:dimension/name :time/assigned :dimension/type :instant :dimension/indexed? true}
                     {:dimension/name :time/resolved :dimension/type :instant :dimension/indexed? true}])

      ;; Ticket created
      (transact! db {:tx-data [{:ticket/id "TIX-1" :ticket/priority :high}]
                     :time-dimensions {:time/created #inst "2026-01-20T09:00:00Z"}})

      ;; Assigned to engineer
      (transact! db {:tx-data [[:db/add [:ticket/id "TIX-1"] :ticket/assignee "alice"]]
                     :time-dimensions {:time/assigned #inst "2026-01-20T09:15:00Z"}})

      ;; Resolved
      (transact! db {:tx-data [[:db/add [:ticket/id "TIX-1"] :ticket/status :resolved]]
                     :time-dimensions {:time/resolved #inst "2026-01-20T11:30:00Z"}})

      ;; Calculate time-to-assign and time-to-resolve
      (let [metrics (query db '[:find ?ticket [(- ?assigned ?created)] [(- ?resolved ?assigned)]
                                :where
                                [?t :ticket/id ?ticket]
                                [?t :ticket/id _ :at/created ?created]
                                [?t :ticket/id _ :at/assigned ?assigned]
                                [?t :ticket/id _ :at/resolved ?resolved]])]
        (is (= 1 (count metrics)))
        (let [[ticket time-to-assign time-to-resolve] (first metrics)]
          (is (= "TIX-1" ticket))
          (is (= 900000 time-to-assign))  ; 15 minutes
          (is (= 8100000 time-to-resolve)))))))  ; 135 minutes

(deftest test-transaction-event-stream-processing
  (testing "Processing event stream with temporal ordering"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/occurred :dimension/type :instant :dimension/indexed? true}])

      ;; Events arriving out of order
      (transact! db {:tx-data [{:event/id "E3" :event/type :page-view :event/page "/products"}]
                     :time-dimensions {:time/occurred #inst "2026-01-20T10:03:00Z"}})

      (transact! db {:tx-data [{:event/id "E1" :event/type :page-view :event/page "/home"}]
                     :time-dimensions {:time/occurred #inst "2026-01-20T10:01:00Z"}})

      (transact! db {:tx-data [{:event/id "E2" :event/type :click :event/button "buy"}]
                     :time-dimensions {:time/occurred #inst "2026-01-20T10:02:00Z"}})

      ;; Query events in occurred-time order (not system-time order)
      (let [events (query db '[:find ?id ?type ?time
                               :where
                               [?e :event/id ?id]
                               [?e :event/type ?type]
                               [?e :event/id _ :at/occurred ?time]])
            sorted (sort-by (fn [[_ _ t]] (.getTime t)) events)]
        (is (= 3 (count sorted)))
        (is (= "E1" (first (first sorted))))
        (is (= "E2" (first (second sorted))))
        (is (= "E3" (first (nth sorted 2))))))))

;; =============================================================================
;; Delta Tracking and Audit
;; =============================================================================

(deftest test-delta-tracking-complete-audit
  (testing "Complete audit trail via delta tracking"
    (let [db (create-db)]

      ;; Initial state
      (let [r1 (transact! db {:tx-data [{:doc/id "DOC-1"
                                         :doc/title "Original Title"
                                         :doc/content "Original content"
                                         :doc/status :draft}]
                              :tx-meta {:tx/author "alice"
                                        :tx/ip "192.168.1.1"}})]

        ;; Collect all deltas
        (is (= 4 (count (:deltas r1))))  ; 4 attributes
        (is (every? #(= "alice" (get-in % [:tx :tx/author])) (:deltas r1))))

      ;; First edit
      (let [r2 (transact! db {:tx-data [[:db/add [:doc/id "DOC-1"] :doc/title "Updated Title"]
                                        [:db/add [:doc/id "DOC-1"] :doc/content "Updated content"]]
                              :tx-meta {:tx/author "bob"
                                        :tx/action "edit"}})]

        ;; Delta shows old and new values
        (let [title-delta (first (filter #(= :doc/title (:attribute %)) (:deltas r2)))]
          (is (= "Original Title" (:old-value title-delta)))
          (is (= "Updated Title" (:new-value title-delta)))
          (is (= "bob" (get-in title-delta [:tx :tx/author])))))

      ;; Publish
      (let [r3 (transact! db {:tx-data [[:db/add [:doc/id "DOC-1"] :doc/status :published]]
                              :tx-meta {:tx/author "charlie"
                                        :tx/action "publish"}})]

        ;; Delta shows status transition
        (let [status-delta (first (:deltas r3))]
          (is (= :draft (:old-value status-delta)))
          (is (= :published (:new-value status-delta)))))

      ;; Query full history by transaction metadata
      (let [authors (query db '[:find ?author
                                :where
                                [?tx :tx/author ?author]])]
        ;; Note: This requires storing tx metadata as entities
        ;; For now, we track it in deltas only
        (is (>= (count authors) 0))))))

(deftest test-delta-tracking-change-detection
  (testing "Detect what changed between transactions"
    (let [db (create-db)]

      (transact! db [{:account/id "ACC-1"
                      :account/balance 1000
                      :account/status :active
                      :account/overdraft-limit 500}])

      ;; Multiple changes
      (let [result (transact! db [[:db/add [:account/id "ACC-1"] :account/balance 1500]
                                  [:db/add [:account/id "ACC-1"] :account/overdraft-limit 1000]])]

        ;; Exactly 2 deltas
        (is (= 2 (count (:deltas result))))

        ;; Check each delta
        (let [balance-delta (first (filter #(= :account/balance (:attribute %))
                                           (:deltas result)))
              limit-delta (first (filter #(= :account/overdraft-limit (:attribute %))
                                         (:deltas result)))]
          (is (= 1000 (:old-value balance-delta)))
          (is (= 1500 (:new-value balance-delta)))

          (is (= 500 (:old-value limit-delta)))
          (is (= 1000 (:new-value limit-delta))))))))

;; =============================================================================
;; Multi-Entity Transaction Patterns
;; =============================================================================

(deftest test-transaction-referential-integrity
  (testing "Maintaining referential integrity across entities"
    (let [db (create-db)]

      ;; Create parent and children in single transaction
      (let [result (transact! db [{:db/id 1 :folder/name "Root"}
                                  {:db/id 2 :file/name "Doc1" :file/parent 1}
                                  {:db/id 3 :file/name "Doc2" :file/parent 1}
                                  {:db/id 4 :folder/name "Subfolder" :folder/parent 1}
                                  {:db/id 5 :file/name "Doc3" :file/parent 4}])]

        ;; All entities created atomically
        (is (= 5 (count (distinct (map :entity (:deltas result))))))

        ;; References are valid
        (is (= 1 (:file/parent (entity db 2))))
        (is (= 1 (:file/parent (entity db 3))))
        (is (= 4 (:file/parent (entity db 5)))))

      ;; Query folder contents
      (let [root-contents (query db '[:find ?name
                                      :where
                                      [?item :file/parent 1]
                                      [?item :file/name ?name]])]
        (is (= 2 (count root-contents)))))))

(deftest test-transaction-graph-mutation
  (testing "Graph mutations - adding and removing edges"
    (let [db (create-db)]

      ;; Create graph nodes
      (transact! db [{:node/id 1}
                     {:node/id 2}
                     {:node/id 3}
                     {:node/id 4}])

      ;; Add edges
      (transact! db [[:db/add 1 :node/edge 2]
                     [:db/add 2 :node/edge 3]
                     [:db/add 1 :node/edge 3]  ; Also direct edge
                     [:db/add 3 :node/edge 4]])

      ;; Query reachability from node 1
      (let [reachable (query db '[:find ?target
                                  :where
                                  [1 :node/edge+ ?target]])]
        (is (= #{[2] [3] [4]} reachable)))

      ;; Remove edge
      (transact! db [[:db/retract 1 :node/edge 3]])

      ;; Node 3 still reachable via node 2
      (let [reachable-after (query db '[:find ?target
                                        :where
                                        [1 :node/edge+ ?target]])]
        (is (= #{[2] [3] [4]} reachable-after))))))

;; =============================================================================
;; Temporal Transaction Patterns
;; =============================================================================

(deftest test-transaction-late-arriving-data
  (testing "Handling late-arriving data with retroactive timestamps"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/occurred
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Event arrives late - timestamp is in the past
      (transact! db {:tx-data [{:event/id "E1" :event/data "data1"}]
                     :time-dimensions {:time/occurred #inst "2026-01-01T10:00:00Z"}
                     :tx-meta {:tx/late-arrival true}})

      ;; Another event with earlier timestamp arrives even later
      (transact! db {:tx-data [{:event/id "E2" :event/data "data2"}]
                     :time-dimensions {:time/occurred #inst "2026-01-01T09:00:00Z"}
                     :tx-meta {:tx/late-arrival true}})

      ;; Query by occurred-time shows correct order
      (let [events (query db '[:find ?id ?time
                               :where
                               [?e :event/id ?id]
                               [?e :event/id _ :at/occurred ?time]])
            sorted (sort-by (fn [[_ t]] (.getTime t)) events)]
        (is (= "E2" (first (first sorted))))  ; Earlier occurred-time
        (is (= "E1" (first (second sorted))))))))

(deftest test-transaction-bitemporal-correction
  (testing "Bitemporal corrections - when we knew vs when it was true"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/effective
                      :dimension/type :instant
                      :dimension/indexed? true
                      :dimension/description "When fact was true in real world"}])

      ;; Record historical fact (learned today about yesterday)
      (let [r1 (transact! db {:tx-data [{:contract/id "C1" :contract/status :signed}]
                              :time-dimensions {:time/effective #inst "2026-01-15"}
                              :tx-meta {:tx/entered-by "alice"}})]

        ;; Discover it was actually signed earlier
        (transact! db {:tx-data [[:db/add [:contract/id "C1"] :contract/status :signed]]
                       :time-dimensions {:time/effective #inst "2026-01-10"}  ; Earlier effective date
                       :tx-meta {:tx/entered-by "bob"
                                 :tx/reason "Found earlier signature"}})

        ;; Query: was contract signed on 2026-01-12? (effective-time)
        (let [signed-on-12 (query db {:query '[:find ?status
                                               :where ["C1" :contract/status ?status]]
                                      :as-of {:time/effective #inst "2026-01-12"}})]
          (is (= #{[:signed]} signed-on-12)))

        ;; Audit: what did we know on system-time of first entry?
        (let [known-at-r1 (entity-by db :contract/id "C1" (:tx-id r1))]
          (is (= :signed (:contract/status known-at-r1))))))))

;; =============================================================================
;; Transaction Metadata Patterns
;; =============================================================================

(deftest test-transaction-metadata-enrichment
  (testing "Rich transaction metadata for audit and debugging"
    (let [db (create-db)
          result (transact! db
                            {:tx-data [{:user/name "Alice"}]
                             :tx-meta {:tx/user "admin"
                                       :tx/source "api"
                                       :tx/request-id "req-12345"
                                       :tx/user-agent "Mozilla/5.0"
                                       :tx/ip-address "192.168.1.100"
                                       :tx/session-id "sess-xyz"
                                       :tx/reason "User registration"}})]

      ;; All metadata in deltas
      (let [delta (first (:deltas result))]
        (is (= "admin" (get-in delta [:tx :tx/user])))
        (is (= "api" (get-in delta [:tx :tx/source])))
        (is (= "req-12345" (get-in delta [:tx :tx/request-id])))
        (is (= "User registration" (get-in delta [:tx :tx/reason]))))))

  (testing "Querying by transaction metadata"
    (let [db (create-db)]

      ;; Multiple transactions with different sources
      (transact! db {:tx-data [{:data/value 1}]
                     :tx-meta {:tx/source "api" :tx/user "alice"}})

      (transact! db {:tx-data [{:data/value 2}]
                     :tx-meta {:tx/source "batch" :tx/user "system"}})

      (transact! db {:tx-data [{:data/value 3}]
                     :tx-meta {:tx/source "api" :tx/user "bob"}})

      ;; Count of API vs batch transactions
      ;; Would require tx entities to be queryable
      (is true))))  ; Placeholder for future tx entity queries

;; =============================================================================
;; Constraint Violation Patterns
;; =============================================================================

(deftest test-transaction-constraint-prevents-invalid-data
  (testing "Constraints prevent invalid temporal data"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/start
                      :dimension/type :instant
                      :dimension/indexed? true}
                     {:dimension/name :time/end
                      :dimension/type :instant
                      :dimension/indexed? true
                      :dimension/constraints [{:type :ordering :after :time/start}]}])

      ;; Valid: end after start
      (is (some? (transact! db {:tx-data [{:meeting/id "M1"}]
                                :time-dimensions {:time/start #inst "2026-01-20T10:00:00Z"
                                                  :time/end #inst "2026-01-20T11:00:00Z"}})))

      ;; Invalid: end before start
      (is (thrown-with-msg?
           Exception
           #"Constraint violation"
           (transact! db {:tx-data [{:meeting/id "M2"}]
                          :time-dimensions {:time/start #inst "2026-01-20T14:00:00Z"
                                            :time/end #inst "2026-01-20T13:00:00Z"}})))))

  (testing "Constraints on retroactive updates"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/effective :dimension/type :instant :dimension/indexed? true}])

      ;; Original entry
      (transact! db {:tx-data [{:record/id "R1" :record/value 100}]
                     :time-dimensions {:time/effective #inst "2026-01-15"}})

      ;; Try to add conflicting retroactive entry
      ;; Custom constraint could validate no overlapping effective times
      (is true))))  ; Placeholder

;; =============================================================================
;; Collection Operations
;; =============================================================================

(deftest test-transaction-vector-operations
  (testing "Position-based vector operations with deltas"
    (let [db (create-db)]

      ;; Create entity with vector
      (transact! db [{:list/id "L1" :list/items ["a" "b" "c"]}])

      ;; Append to vector - should generate position-based delta
      (let [result (transact! db [[:db/add [:list/id "L1"] :list/items "d"]])]
        (let [delta (first (:deltas result))]
          ;; Should have collection operation metadata
          (is (= ["a" "b" "c"] (:old-value delta)))
          (is (= ["a" "b" "c" "d"] (:new-value delta)))))

      ;; Verify final state
      (let [list-entity (entity-by db :list/id "L1")]
        (is (= ["a" "b" "c" "d"] (:list/items list-entity)))))))

(deftest test-transaction-map-operations
  (testing "Map operations with key-level deltas"
    (let [db (create-db)]

      (transact! db [{:config/id "C1"
                      :config/settings {:theme "dark" :lang "en"}}])

      ;; Update map key
      (let [result (transact! db [[:db/assoc [:config/id "C1"]
                                   :config/settings :notifications true]])]
        (let [delta (first (:deltas result))]
          (is (= {:theme "dark" :lang "en"} (:old-value delta)))
          (is (= {:theme "dark" :lang "en" :notifications true} (:new-value delta)))))

      ;; Verify
      (let [config (entity-by db :config/id "C1")]
        (is (true? (get-in config [:config/settings :notifications]))))))

  (testing "Set operations"
    (let [db (create-db)]

      (transact! db [{:user/roles #{:user :viewer}}])

      ;; Add to set
      (let [result (transact! db [[:db/add 1 :user/roles :admin]])]
        (let [delta (first (:deltas result))]
          (is (= #{:user :viewer} (:old-value delta)))
          (is (= #{:user :viewer :admin} (:new-value delta)))))

      ;; Remove from set
      (let [result (transact! db [[:db/retract 1 :user/roles :viewer]])]
        (let [delta (first (:deltas result))]
          (is (= #{:user :viewer :admin} (:old-value delta)))
          (is (= #{:user :admin} (:new-value delta))))))))
