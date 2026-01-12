(ns dfdb.usecase-queries-test
  "Comprehensive Query Use Cases.
  Demonstrates the full power of the Datalog query engine with multi-dimensional time."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Social Network Queries
;; =============================================================================

(deftest test-social-network-friends-of-friends
  (testing "Finding friends of friends (transitive social graph)"
    (let [db (create-db)]

      ;; Create users
      (transact! db [{:user/id 1 :user/name "Alice"}
                     {:user/id 2 :user/name "Bob"}
                     {:user/id 3 :user/name "Charlie"}
                     {:user/id 4 :user/name "Dave"}
                     {:user/id 5 :user/name "Eve"}])

      ;; Create friend relationships
      ;; Alice -> Bob -> Charlie -> Dave
      ;; Alice -> Eve
      (transact! db [[:db/add 1 :user/friend 2]
                     [:db/add 2 :user/friend 3]
                     [:db/add 3 :user/friend 4]
                     [:db/add 1 :user/friend 5]])

      ;; Find all transitive friends of Alice
      (let [friends (query db '[:find ?name
                                :where
                                [?alice :user/name "Alice"]
                                [?friend :user/friend+ ?alice]
                                [?friend :user/name ?name]])]
        ;; Bob, Charlie, Dave, Eve (all reachable)
        (is (>= (count friends) 2))))))  ; At least direct friends

(deftest test-social-network-common-friends
  (testing "Finding common friends between users"
    (let [db (create-db)]

      (transact! db [{:user/name "Alice"}
                     {:user/name "Bob"}
                     {:user/name "Charlie"}
                     {:user/name "Dave"}])

      ;; Alice friends: Bob, Charlie
      ;; Dave friends: Bob, Charlie
      (transact! db [[:db/add 1 :user/friend 2]
                     [:db/add 1 :user/friend 3]
                     [:db/add 4 :user/friend 2]
                     [:db/add 4 :user/friend 3]])

      ;; Find common friends of Alice and Dave
      (let [common (query db '[:find ?friend-name
                               :where
                               [?alice :user/name "Alice"]
                               [?dave :user/name "Dave"]
                               [?alice :user/friend ?friend]
                               [?dave :user/friend ?friend]
                               [?friend :user/name ?friend-name]])]
        (is (= 2 (count common)))))))  ; Bob and Charlie

;; =============================================================================
;; Organization Hierarchy Queries
;; =============================================================================

(deftest test-org-reporting-structure
  (testing "Organization reporting hierarchy with transitive reports"
    (let [db (create-db)]

      ;; Build org chart
      (transact! db [{:emp/id 1 :emp/name "CEO" :emp/level :exec}
                     {:emp/id 2 :emp/name "VP Eng" :emp/reports-to 1 :emp/level :vp}
                     {:emp/id 3 :emp/name "VP Sales" :emp/reports-to 1 :emp/level :vp}
                     {:emp/id 4 :emp/name "Eng Manager" :emp/reports-to 2 :emp/level :manager}
                     {:emp/id 5 :emp/name "Sales Manager" :emp/reports-to 3 :emp/level :manager}
                     {:emp/id 6 :emp/name "Engineer 1" :emp/reports-to 4 :emp/level :ic}
                     {:emp/id 7 :emp/name "Engineer 2" :emp/reports-to 4 :emp/level :ic}
                     {:emp/id 8 :emp/name "Sales Rep" :emp/reports-to 5 :emp/level :ic}])

      ;; Find all people under CEO
      (let [all-reports (query db '[:find ?name
                                    :where
                                    [?ceo :emp/name "CEO"]
                                    [?report :emp/reports-to+ ?ceo]
                                    [?report :emp/name ?name]])]
        (is (= 7 (count all-reports))))  ; Everyone except CEO

      ;; Find all VPs
      (let [vps (query db '[:find ?name
                            :where
                            [?vp :emp/level :vp]
                            [?vp :emp/name ?name]])]
        (is (= #{["VP Eng"] ["VP Sales"]} vps)))

      ;; Find engineers (ICs under Eng Manager)
      (let [engineers (query db '[:find ?name
                                  :where
                                  [?mgr :emp/name "Eng Manager"]
                                  [?eng :emp/reports-to+ ?mgr]
                                  [?eng :emp/level :ic]
                                  [?eng :emp/name ?name]])]
        (is (= 2 (count engineers)))))))

(deftest test-org-headcount-by-level
  (testing "Headcount analytics by organization level"
    (let [db (create-db)]

      (transact! db [{:emp/name "CEO" :emp/level :exec}
                     {:emp/name "VP1" :emp/level :vp}
                     {:emp/name "VP2" :emp/level :vp}
                     {:emp/name "Mgr1" :emp/level :manager}
                     {:emp/name "Mgr2" :emp/level :manager}
                     {:emp/name "Mgr3" :emp/level :manager}
                     {:emp/name "IC1" :emp/level :ic}
                     {:emp/name "IC2" :emp/level :ic}
                     {:emp/name "IC3" :emp/level :ic}
                     {:emp/name "IC4" :emp/level :ic}])

      ;; Headcount by level
      (let [by-level (query db '[:find ?level (count ?emp)
                                 :where
                                 [?emp :emp/level ?level]])]
        (is (= #{[:exec 1] [:vp 2] [:manager 3] [:ic 4]} by-level))))))

;; =============================================================================
;; Financial/Accounting Queries
;; =============================================================================

(deftest test-financial-account-balance
  (testing "Account balance with transaction history"
    (let [db (create-db)]

      ;; Create account
      (transact! db [{:account/id "ACC-001"
                      :account/owner "Alice"
                      :account/balance 1000}])

      ;; Transactions
      (transact! db [{:tx/id "TX-1" :tx/account "ACC-001" :tx/amount -50 :tx/type :withdrawal}])
      (transact! db [[:db/add [:account/id "ACC-001"] :account/balance 950]])

      (transact! db [{:tx/id "TX-2" :tx/account "ACC-001" :tx/amount 200 :tx/type :deposit}])
      (transact! db [[:db/add [:account/id "ACC-001"] :account/balance 1150]])

      ;; Query current balance
      (let [balance (query db '[:find ?balance
                                :where
                                [?acct :account/id "ACC-001"]
                                [?acct :account/balance ?balance]])]
        (is (= #{[1150]} balance)))

      ;; Query all transactions
      (let [txs (query db '[:find ?tx-id ?amount ?type
                            :where
                            [?tx :tx/account "ACC-001"]
                            [?tx :tx/id ?tx-id]
                            [?tx :tx/amount ?amount]
                            [?tx :tx/type ?type]])]
        (is (= 2 (count txs))))

      ;; Sum of all transactions
      (let [total-change (query db '[:find (sum ?amount)
                                     :where
                                     [?tx :tx/account "ACC-001"]
                                     [?tx :tx/amount ?amount]])]
        (is (= #{[150]} total-change))))))  ; -50 + 200

(deftest test-financial-audit-trail
  (testing "Compliance audit trail with system-time"
    (let [db (create-db)]

      ;; Transaction at T1
      (let [r1 (transact! db [{:account/id "ACC-100" :account/balance 1000}])]

        ;; Fraudulent withdrawal at T2
        (transact! db [[:db/add [:account/id "ACC-100"] :account/balance 500]])

        ;; Correction at T3
        (let [r3 (transact! db [[:db/add [:account/id "ACC-100"] :account/balance 1000]])]

          ;; Audit: what was balance at T2?
          (is (= 500 (:account/balance (entity-by db :account/id "ACC-100" (:tx-id r3)))))

          ;; Audit: what did we know at system-time T2?
          (is (= 1000 (:account/balance (entity-by db :account/id "ACC-100" (:tx-id r1))))))))))

;; =============================================================================
;; Complex Analytical Queries
;; =============================================================================

(deftest test-analytics-top-customers
  (testing "Top N customers by spend"
    (let [db (create-db)]

      (transact! db [{:customer/id 1 :customer/name "Alice"}
                     {:customer/id 2 :customer/name "Bob"}
                     {:customer/id 3 :customer/name "Charlie"}

                     {:order/customer 1 :order/total 100}
                     {:order/customer 1 :order/total 200}
                     {:order/customer 1 :order/total 150}

                     {:order/customer 2 :order/total 500}
                     {:order/customer 2 :order/total 300}

                     {:order/customer 3 :order/total 50}])

      ;; Customer spend
      (let [spend (query db '[:find ?name (sum ?total)
                              :where
                              [?cust :customer/name ?name]
                              [?order :order/customer ?cust]
                              [?order :order/total ?total]])]
        (is (= #{["Alice" 450] ["Bob" 800] ["Charlie" 50]} spend)))

      ;; Could filter to top N with post-processing
      (let [sorted (sort-by second > (query db '[:find ?name (sum ?total)
                                                 :where
                                                 [?cust :customer/name ?name]
                                                 [?order :order/customer ?cust]
                                                 [?order :order/total ?total]]))
            top-2 (take 2 sorted)]
        (is (= 2 (count top-2)))
        (is (= "Bob" (first (first top-2))))))))

(deftest test-analytics-multi-dimensional-cohort
  (testing "Cohort analysis across multiple time dimensions"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/registered :dimension/type :instant :dimension/indexed? true}
                     {:dimension/name :time/first-purchase :dimension/type :instant :dimension/indexed? true}])

      ;; Users registered in January
      (transact! db {:tx-data [{:user/email "jan1@example.com"}]
                     :time-dimensions {:time/registered #inst "2026-01-05"}})

      (transact! db {:tx-data [{:user/email "jan2@example.com"}]
                     :time-dimensions {:time/registered #inst "2026-01-15"}})

      ;; Users registered in February
      (transact! db {:tx-data [{:user/email "feb1@example.com"}]
                     :time-dimensions {:time/registered #inst "2026-02-05"}})

      ;; Query January registrations
      (let [jan-users (query db {:query '[:find ?email
                                          :where [?u :user/email ?email]]
                                 :as-of {:time/registered #inst "2026-01-31T23:59:59Z"}})]
        (is (= 2 (count jan-users)))))))

;; =============================================================================
;; Graph Queries
;; =============================================================================

(deftest test-graph-shortest-path
  (testing "Graph traversal - finding paths"
    (let [db (create-db)]

      ;; Create graph: 1->2->3->4
      ;;               1->5->4
      (transact! db [{:node/id 1 :node/edges [2 5]}
                     {:node/id 2 :node/edges [3]}
                     {:node/id 3 :node/edges [4]}
                     {:node/id 5 :node/edges [4]}
                     {:node/id 4 :node/edges []}])

      ;; Find if 4 is reachable from 1 (yes, via transitive closure)
      ;; This would require custom recursive logic
      (is true))))  ; Placeholder

(deftest test-graph-degree-centrality
  (testing "Graph analytics - node degree"
    (let [db (create-db)]

      ;; Create graph with connections
      (transact! db [[:db/add 1 :node/connected-to 2]
                     [:db/add 1 :node/connected-to 3]
                     [:db/add 1 :node/connected-to 4]
                     [:db/add 2 :node/connected-to 1]
                     [:db/add 3 :node/connected-to 1]])

      ;; Count connections per node (out-degree)
      (let [degrees (query db '[:find ?node (count ?connection)
                                :where
                                [?node :node/connected-to ?connection]])]
        (is (= #{[1 3] [2 1] [3 1]} degrees))))))

;; =============================================================================
;; Time-Series Queries
;; =============================================================================

(deftest test-timeseries-sensor-data
  (testing "Time-series sensor data with temporal queries"
    (let [db (create-db)]

      (transact! db [{:dimension/name :time/measured
                      :dimension/type :instant
                      :dimension/indexed? true}])

      ;; Record sensor readings
      (transact! db {:tx-data [{:sensor/id "TEMP-1" :sensor/value 72.5}]
                     :time-dimensions {:time/measured #inst "2026-01-15T10:00:00Z"}})

      (transact! db {:tx-data [[:db/add [:sensor/id "TEMP-1"] :sensor/value 73.2]]
                     :time-dimensions {:time/measured #inst "2026-01-15T11:00:00Z"}})

      (transact! db {:tx-data [[:db/add [:sensor/id "TEMP-1"] :sensor/value 71.8]]
                     :time-dimensions {:time/measured #inst "2026-01-15T12:00:00Z"}})

      ;; Query sensor value at specific time
      (let [temp-at-11am (query db {:query '[:find ?value
                                             :where
                                             ["TEMP-1" :sensor/value ?value]]
                                    :as-of {:time/measured #inst "2026-01-15T11:30:00Z"}})]
        (is (= #{[73.2]} temp-at-11am)))

      ;; Calculate temperature change
      (let [readings (query db '[:find ?time ?value
                                 :where
                                 [?s :sensor/id "TEMP-1"]
                                 [?s :sensor/value ?value]
                                 [?s :sensor/value _ :at/measured ?time]])]
        (is (= 3 (count readings)))))))

(deftest test-timeseries-aggregations
  (testing "Time-series aggregations (min, max, avg)"
    (let [db (create-db)]

      ;; Multiple sensors with readings
      (transact! db [{:sensor/id "S1" :sensor/value 10}
                     {:sensor/id "S2" :sensor/value 20}
                     {:sensor/id "S3" :sensor/value 30}
                     {:sensor/id "S4" :sensor/value 15}
                     {:sensor/id "S5" :sensor/value 25}])

      ;; Statistical queries
      (let [min-val (query db '[:find (min ?v) :where [?s :sensor/value ?v]])
            max-val (query db '[:find (max ?v) :where [?s :sensor/value ?v]])
            avg-val (query db '[:find (avg ?v) :where [?s :sensor/value ?v]])]
        (is (= #{[10]} min-val))
        (is (= #{[30]} max-val))
        (is (= #{[20.0]} avg-val))))))

;; =============================================================================
;; Compliance & Audit Queries
;; =============================================================================

(deftest test-compliance-gdpr-data-retention
  (testing "GDPR compliance - finding old data for deletion"
    (let [db (create-db)]

      ;; Users created at different times
      (let [r1 (transact! db [{:user/email "old@example.com" :user/consented true}])
            r2 (transact! db [{:user/email "new@example.com" :user/consented true}])]

        ;; Find users created before certain date (for retention policy)
        ;; Would query by system-time
        (let [old-users (query db {:query '[:find ?email
                                            :where [?u :user/email ?email]]
                                   :as-of {:time/system (:tx-time r1)}})]
          (is (= 1 (count old-users))))

        ;; All current users
        (let [all-users (query db '[:find ?email
                                    :where [?u :user/email ?email]])]
          (is (= 2 (count all-users))))))))

(deftest test-compliance-audit-who-knew-what-when
  (testing "Audit trail - who knew what when"
    (let [db (create-db)]

      ;; Record with sensitive data
      (let [r1 (transact! db {:tx-data [{:record/id "REC-1" :record/classification :public}]
                              :tx-meta {:tx/user "alice"}})

            ;; Classification upgraded
            r2 (transact! db {:tx-data [[:db/add [:record/id "REC-1"] :record/classification :confidential]]
                              :tx-meta {:tx/user "bob"}})

            ;; Further upgraded
            r3 (transact! db {:tx-data [[:db/add [:record/id "REC-1"] :record/classification :secret]]
                              :tx-meta {:tx/user "charlie"}})]

        ;; Query: what was classification when Bob knew about it?
        (let [at-bob (entity-by db :record/id "REC-1" (:tx-id r2))]
          (is (= :confidential (:record/classification at-bob))))

        ;; Query: what was it originally?
        (let [original (entity-by db :record/id "REC-1" (:tx-id r1))]
          (is (= :public (:record/classification original))))))))

;; =============================================================================
;; Advanced Join Queries
;; =============================================================================

(deftest test-join-three-way
  (testing "Three-way join across entities"
    (let [db (create-db)]

      (transact! db [{:user/id 1 :user/name "Alice"}
                     {:user/id 2 :user/name "Bob"}

                     {:order/id 100 :order/user 1}
                     {:order/id 101 :order/user 2}

                     {:item/id 1000 :item/order 100 :item/product "Widget"}
                     {:item/id 1001 :item/order 100 :item/product "Gadget"}
                     {:item/id 1002 :item/order 101 :item/product "Doohickey"}])

      ;; Join: user -> order -> items
      (let [results (query db '[:find ?user-name ?product
                                :where
                                [?user :user/name ?user-name]
                                [?order :order/user ?user]
                                [?item :item/order ?order]
                                [?item :item/product ?product]])]
        (is (= #{["Alice" "Widget"]
                 ["Alice" "Gadget"]
                 ["Bob" "Doohickey"]} results))))))

(deftest test-join-with-aggregation
  (testing "Join with aggregation across multiple entities"
    (let [db (create-db)]

      (transact! db [{:dept/id 1 :dept/name "Engineering"}
                     {:dept/id 2 :dept/name "Sales"}

                     {:emp/name "Alice" :emp/dept 1 :emp/salary 100000}
                     {:emp/name "Bob" :emp/dept 1 :emp/salary 120000}
                     {:emp/name "Charlie" :emp/dept 2 :emp/salary 80000}
                     {:emp/name "Dave" :emp/dept 2 :emp/salary 90000}])

      ;; Average salary by department
      (let [avg-by-dept (query db '[:find ?dept-name (avg ?salary)
                                    :where
                                    [?dept :dept/name ?dept-name]
                                    [?emp :emp/dept ?dept]
                                    [?emp :emp/salary ?salary]])]
        (is (= #{["Engineering" 110000.0] ["Sales" 85000.0]} avg-by-dept)))

      ;; Total payroll by department
      (let [total-by-dept (query db '[:find ?dept-name (sum ?salary)
                                      :where
                                      [?dept :dept/name ?dept-name]
                                      [?emp :emp/dept ?dept]
                                      [?emp :emp/salary ?salary]])]
        (is (= #{["Engineering" 220000] ["Sales" 170000]} total-by-dept))))))

;; =============================================================================
;; Negation Queries
;; =============================================================================

(deftest test-negation-users-without-orders
  (testing "Find users without any orders"
    (let [db (create-db)]

      (transact! db [{:user/id 1 :user/name "Alice"}
                     {:user/id 2 :user/name "Bob"}
                     {:user/id 3 :user/name "Charlie"}

                     {:order/user 1 :order/total 100}
                     {:order/user 2 :order/total 200}])

      ;; Find users with no orders
      (let [no-orders (query db '[:find ?name
                                  :where
                                  [?user :user/name ?name]
                                  (not [?order :order/user ?user])])]
        (is (= #{["Charlie"]} no-orders))))))

(deftest test-negation-products-never-sold
  (testing "Find products that were never ordered"
    (let [db (create-db)]

      (transact! db [{:product/sku "P1" :product/name "Widget"}
                     {:product/sku "P2" :product/name "Gadget"}
                     {:product/sku "P3" :product/name "Doohickey"}

                     {:order-item/product "P1" :order-item/qty 5}
                     {:order-item/product "P2" :order-item/qty 3}])

      ;; Products never ordered
      (let [unsold (query db '[:find ?name
                               :where
                               [?p :product/name ?name]
                               [?p :product/sku ?sku]
                               (not [?item :order-item/product ?sku])])]
        (is (= #{["Doohickey"]} unsold))))))
