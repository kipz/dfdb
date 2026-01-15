(ns dfdb.recursive-aggregate-test
  "Tests for recursive queries combined with aggregates.
  Verifies that transitive closure works correctly with aggregation."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Recursive + Count Tests
;; =============================================================================

(deftest recursive-count-basic-test
  (testing "Count all transitive reports under a manager"
    (let [db (create-db)]
      ;; Build org hierarchy: CEO -> Alice -> Bob, Carol
      ;;                           -> Dave -> Eve
      (transact! db [{:id 1 :name "CEO" :employee/id "E001"}
                     {:id 2 :name "Alice" :employee/id "E002" :reports-to 1}
                     {:id 3 :name "Bob" :employee/id "E003" :reports-to 2}
                     {:id 4 :name "Carol" :employee/id "E004" :reports-to 2}
                     {:id 5 :name "Dave" :employee/id "E005" :reports-to 1}
                     {:id 6 :name "Eve" :employee/id "E006" :reports-to 5}])

      ;; Count all transitive reports under CEO
      (let [result (query db '[:find (count ?report)
                               :where
                               [?ceo :name "CEO"]
                               [?report :reports-to+ ?ceo]])]
        (is (= 1 (count result)))
        (is (= 5 (ffirst result))
            "CEO should have 5 transitive reports (Alice, Bob, Carol, Dave, Eve)")))))

(deftest recursive-count-multiple-managers-test
  (testing "Count transitive reports per manager"
    (let [db (create-db)]
      (transact! db [{:id 1 :name "CEO" :employee/id "E001"}
                     {:id 2 :name "Alice" :employee/id "E002" :reports-to 1}
                     {:id 3 :name "Bob" :employee/id "E003" :reports-to 2}
                     {:id 4 :name "Carol" :employee/id "E004" :reports-to 2}
                     {:id 5 :name "Dave" :employee/id "E005" :reports-to 1}
                     {:id 6 :name "Eve" :employee/id "E006" :reports-to 5}])

      ;; Count reports per manager
      (let [result (into {} (query db '[:find ?manager (count ?report)
                                        :where
                                        [?mgr :name ?manager]
                                        [?report :reports-to+ ?mgr]]))]
        (is (= 5 (get result "CEO")) "CEO has 5 transitive reports")
        (is (= 2 (get result "Alice")) "Alice has 2 transitive reports (Bob, Carol)")
        (is (= 1 (get result "Dave")) "Dave has 1 transitive report (Eve)")))))

;; =============================================================================
;; Recursive + Sum Tests
;; =============================================================================

(deftest recursive-sum-salaries-test
  (testing "Sum salaries of all transitive reports"
    (let [db (create-db)]
      (transact! db [{:id 1 :name "CEO" :salary 200000}
                     {:id 2 :name "Alice" :salary 150000 :reports-to 1}
                     {:id 3 :name "Bob" :salary 100000 :reports-to 2}
                     {:id 4 :name "Carol" :salary 100000 :reports-to 2}
                     {:id 5 :name "Dave" :salary 120000 :reports-to 1}
                     {:id 6 :name "Eve" :salary 90000 :reports-to 5}])

      ;; Sum salaries of CEO's transitive reports
      (let [result (query db '[:find (sum ?salary)
                               :where
                               [?ceo :name "CEO"]
                               [?report :reports-to+ ?ceo]
                               [?report :salary ?salary]])]
        (is (= 1 (count result)))
        (is (= 560000 (ffirst result))
            "Total salaries: 150k + 100k + 100k + 120k + 90k = 560k")))))

(deftest recursive-sum-per-manager-test
  (testing "Sum salaries of transitive reports per manager"
    (let [db (create-db)]
      (transact! db [{:id 1 :name "CEO" :salary 200000}
                     {:id 2 :name "Alice" :salary 150000 :reports-to 1}
                     {:id 3 :name "Bob" :salary 100000 :reports-to 2}
                     {:id 4 :name "Carol" :salary 100000 :reports-to 2}])

      (let [result (into {} (query db '[:find ?manager (sum ?salary)
                                        :where
                                        [?mgr :name ?manager]
                                        [?report :reports-to+ ?mgr]
                                        [?report :salary ?salary]]))]
        (is (= 350000 (get result "CEO")) "CEO's reports: 150k + 100k + 100k")
        (is (= 200000 (get result "Alice")) "Alice's reports: 100k + 100k")))))

;; =============================================================================
;; Recursive + Advanced Aggregates Tests
;; =============================================================================

(deftest recursive-avg-salary-test
  (testing "Average salary of transitive reports"
    (let [db (create-db)]
      (transact! db [{:id 1 :name "CEO"}
                     {:id 2 :name "Alice" :salary 150000 :reports-to 1}
                     {:id 3 :name "Bob" :salary 100000 :reports-to 2}
                     {:id 4 :name "Carol" :salary 100000 :reports-to 2}])

      (let [result (query db '[:find (avg ?salary)
                               :where
                               [?ceo :name "CEO"]
                               [?report :reports-to+ ?ceo]
                               [?report :salary ?salary]])]
        (is (= 1 (count result)))
        (is (< (Math/abs (- 116666.67 (ffirst result))) 1.0)
            "Average salary: (150k + 100k + 100k) / 3 ≈ 116.7k")))))

(deftest recursive-median-salary-test
  (testing "Median salary of transitive reports"
    (let [db (create-db)]
      (transact! db [{:id 1 :name "CEO"}
                     {:id 2 :name "Alice" :salary 150000 :reports-to 1}
                     {:id 3 :name "Bob" :salary 100000 :reports-to 2}
                     {:id 4 :name "Carol" :salary 100000 :reports-to 2}
                     {:id 5 :name "Dave" :salary 120000 :reports-to 1}
                     {:id 6 :name "Eve" :salary 90000 :reports-to 5}])

      (let [result (query db '[:find (median ?salary)
                               :where
                               [?ceo :name "CEO"]
                               [?report :reports-to+ ?ceo]
                               [?report :salary ?salary]])]
        (is (= 1 (count result)))
        ;; Salaries: 90k, 100k, 100k, 120k, 150k -> median = 100k
        (is (= 100000.0 (ffirst result))
            "Median of [90k, 100k, 100k, 120k, 150k] should be 100k")))))

(deftest recursive-variance-test
  (testing "Variance of salaries in transitive reports"
    (let [db (create-db)]
      (transact! db [{:id 1 :name "CEO"}
                     {:id 2 :name "Alice" :salary 100000 :reports-to 1}
                     {:id 3 :name "Bob" :salary 100000 :reports-to 1}
                     {:id 4 :name "Carol" :salary 100000 :reports-to 1}])

      (let [result (query db '[:find (variance ?salary)
                               :where
                               [?ceo :name "CEO"]
                               [?report :reports-to+ ?ceo]
                               [?report :salary ?salary]])]
        (is (= 1 (count result)))
        (is (< (ffirst result) 0.01)
            "Variance of identical salaries should be 0")))))

(deftest recursive-count-distinct-test
  (testing "Count distinct salary levels in org hierarchy"
    (let [db (create-db)]
      (transact! db [{:id 1 :name "CEO"}
                     {:id 2 :name "Alice" :salary 150000 :reports-to 1}
                     {:id 3 :name "Bob" :salary 100000 :reports-to 2}
                     {:id 4 :name "Carol" :salary 100000 :reports-to 2}
                     {:id 5 :name "Dave" :salary 150000 :reports-to 1}])

      (let [result (query db '[:find (count-distinct ?salary)
                               :where
                               [?ceo :name "CEO"]
                               [?report :reports-to+ ?ceo]
                               [?report :salary ?salary]])]
        (is (= 1 (count result)))
        (is (= 2 (ffirst result))
            "Should have 2 distinct salary levels: 100k and 150k")))))

;; =============================================================================
;; Recursive + Incremental Subscription Tests
;; =============================================================================

(deftest recursive-aggregate-subscription-test
  (testing "Recursive aggregate updates incrementally"
    (let [db (create-db)
          results (atom [])
          _ (transact! db [{:id 1 :name "CEO"}
                           {:id 2 :name "Alice" :salary 100000 :reports-to 1}])

          sub (subscribe db {:query '[:find (sum ?salary)
                                      :where
                                      [?ceo :name "CEO"]
                                      [?report :reports-to+ ?ceo]
                                      [?report :salary ?salary]]
                             :mode :incremental
                             :callback #(swap! results conj %)})]

      (Thread/sleep 200)

      ;; Initial sum should be 100k
      (is (pos? (count @results)) "Should have received callback")
      (when (seq @results)
        (let [initial-sum (-> @results last :additions vec ffirst)]
          (is (= 100000 initial-sum) "Initial sum should be 100k")))

      ;; Add another employee under Alice
      (transact! db [{:id 3 :name "Bob" :salary 80000 :reports-to 2}])
      (Thread/sleep 200)

      ;; Sum should update to 180k
      (when (> (count @results) 1)
        (let [new-sum (-> @results last :additions vec ffirst)]
          (is (= 180000 new-sum) "Sum should update to 180k")))

      (unsubscribe sub))))
