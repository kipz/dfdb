(ns dfdb.aggregate-combinations-test
  "Tests for using multiple aggregates together and complex aggregate scenarios."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Multiple Aggregates in Single Query
;; =============================================================================

(deftest all-basic-aggregates-together-test
  (testing "Use all 5 basic aggregates in one query"
    (let [db (create-db)]
      (transact! db [{:id 1 :value 10}
                     {:id 2 :value 20}
                     {:id 3 :value 30}
                     {:id 4 :value 40}])

      (let [result (query db '[:find (count ?v) (sum ?v) (avg ?v) (min ?v) (max ?v)
                               :where [?e :value ?v]])]
        (is (= 1 (count result)))
        (let [[cnt sm avg-val mn mx] (first result)]
          (is (= 4 cnt) "Count should be 4")
          (is (= 100 sm) "Sum should be 100")
          (is (= 25.0 avg-val) "Average should be 25")
          (is (= 10 mn) "Min should be 10")
          (is (= 40 mx) "Max should be 40"))))))

(deftest all-advanced-aggregates-together-test
  (testing "Use all 7 advanced aggregates in one query"
    (let [db (create-db)]
      (transact! db [{:id 1 :value 2}
                     {:id 2 :value 4}
                     {:id 3 :value 6}
                     {:id 4 :value 8}
                     {:id 5 :value 10}])

      (let [result (query db '[:find (median ?v) (variance ?v) (stddev ?v) (count-distinct ?v)
                               :where [?e :value ?v]])]
        (is (= 1 (count result)))
        (let [[med var std cnt] (first result)]
          (is (= 6.0 med) "Median of [2,4,6,8,10] should be 6")
          (is (< (Math/abs (- 8.0 var)) 0.01) "Variance should be 8")
          (is (< (Math/abs (- 2.828 std)) 0.01) "Stddev should be ~2.828")
          (is (= 5 cnt) "Count-distinct should be 5"))))))

(deftest mixed-basic-and-advanced-aggregates-test
  (testing "Mix basic and advanced aggregates"
    (let [db (create-db)]
      (transact! db [{:id 1 :price 10.0}
                     {:id 2 :price 20.0}
                     {:id 3 :price 30.0}])

      (let [result (query db '[:find (count ?p) (sum ?p) (median ?p) (stddev ?p)
                               :where [?e :price ?p]])]
        (is (= 1 (count result)))
        (let [[cnt sm med std] (first result)]
          (is (= 3 cnt))
          (is (= 60.0 sm))
          (is (= 20.0 med))
          (is (< (Math/abs (- 8.165 std)) 0.01)))))))

;; =============================================================================
;; Multiple Aggregates with Grouping
;; =============================================================================

(deftest multiple-aggregates-per-group-test
  (testing "Multiple aggregates per group"
    (let [db (create-db)]
      (transact! db [{:category "A" :value 10}
                     {:category "A" :value 20}
                     {:category "A" :value 30}
                     {:category "B" :value 100}
                     {:category "B" :value 200}])

      (let [result (set (query db '[:find ?cat (count ?v) (sum ?v) (avg ?v) (median ?v)
                                    :where
                                    [?e :category ?cat]
                                    [?e :value ?v]]))]
        (is (= 2 (count result)))

        ;; Category A: count=3, sum=60, avg=20, median=20
        (let [cat-a (first (filter #(= "A" (first %)) result))
              [_ cnt sm avg-val med] cat-a]
          (is (= 3 cnt))
          (is (= 60 sm))
          (is (= 20.0 avg-val))
          (is (= 20.0 med)))

        ;; Category B: count=2, sum=300, avg=150, median=150
        (let [cat-b (first (filter #(= "B" (first %)) result))
              [_ cnt sm avg-val med] cat-b]
          (is (= 2 cnt))
          (is (= 300 sm))
          (is (= 150.0 avg-val))
          (is (= 150.0 med)))))))

(deftest advanced-aggregates-per-group-test
  (testing "Advanced aggregates with grouping"
    (let [db (create-db)]
      (transact! db [{:team "Alpha" :score 85}
                     {:team "Alpha" :score 90}
                     {:team "Alpha" :score 95}
                     {:team "Beta" :score 70}
                     {:team "Beta" :score 75}
                     {:team "Beta" :score 70}])

      (let [result (set (query db '[:find ?team (median ?score) (variance ?score) (count-distinct ?score)
                                    :where
                                    [?e :team ?team]
                                    [?e :score ?score]]))]
        (is (= 2 (count result)))

        ;; Alpha: median=90, variance≈16.67, count-distinct=3
        (let [alpha (first (filter #(= "Alpha" (first %)) result))
              [_ med var cd] alpha]
          (is (= 90.0 med))
          (is (< (Math/abs (- 16.67 var)) 0.1))
          (is (= 3 cd)))

        ;; Beta: median=70, variance≈5.56, count-distinct=2
        (let [beta (first (filter #(= "Beta" (first %)) result))
              [_ med var cd] beta]
          (is (= 70.0 med))
          (is (< (Math/abs (- 5.56 var)) 0.1))
          (is (= 2 cd)))))))

;; =============================================================================
;; Aggregates with Different Variables
;; =============================================================================

(deftest aggregates-on-different-variables-test
  (testing "Apply different aggregates to different variables"
    (let [db (create-db)]
      (transact! db [{:product "A" :quantity 5 :price 10.0}
                     {:product "B" :quantity 3 :price 20.0}
                     {:product "C" :quantity 8 :price 15.0}])

      (let [result (query db '[:find (sum ?qty) (avg ?price) (max ?qty) (min ?price)
                               :where
                               [?e :quantity ?qty]
                               [?e :price ?price]])]
        (is (= 1 (count result)))
        (let [[sum-qty avg-price max-qty min-price] (first result)]
          (is (= 16 sum-qty) "Total quantity")
          (is (= 15.0 avg-price) "Average price")
          (is (= 8 max-qty) "Max quantity")
          (is (= 10.0 min-price) "Min price"))))))

(deftest aggregates-different-vars-with-grouping-test
  (testing "Multiple aggregates on different variables with grouping"
    (let [db (create-db)]
      (transact! db [{:category "Electronics" :orders 10 :revenue 1000}
                     {:category "Electronics" :orders 5 :revenue 500}
                     {:category "Books" :orders 20 :revenue 200}
                     {:category "Books" :orders 15 :revenue 150}])

      (let [result (set (query db '[:find ?cat (sum ?orders) (avg ?revenue) (median ?orders)
                                    :where
                                    [?e :category ?cat]
                                    [?e :orders ?orders]
                                    [?e :revenue ?revenue]]))]
        (is (= 2 (count result)))

        ;; Electronics: sum(orders)=15, avg(revenue)=750, median(orders)=7.5
        (let [elec (first (filter #(= "Electronics" (first %)) result))
              [_ sum-orders avg-rev med-orders] elec]
          (is (= 15 sum-orders))
          (is (= 750.0 avg-rev))
          (is (= 7.5 med-orders)))

        ;; Books: sum(orders)=35, avg(revenue)=175, median(orders)=17.5
        (let [books (first (filter #(= "Books" (first %)) result))
              [_ sum-orders avg-rev med-orders] books]
          (is (= 35 sum-orders))
          (is (= 175.0 avg-rev))
          (is (= 17.5 med-orders)))))))

;; =============================================================================
;; Collection Aggregates Combined
;; =============================================================================

(deftest collect-with-other-aggregates-test
  (testing "Collect combined with statistical aggregates"
    (let [db (create-db)]
      (transact! db [{:user "alice" :score 85}
                     {:user "alice" :score 90}
                     {:user "alice" :score 95}])

      (let [result (query db '[:find ?user (count ?score) (avg ?score) (collect ?score)
                               :where
                               [?e :user ?user]
                               [?e :score ?score]])]
        (is (= 1 (count result)))
        (let [[user cnt avg-score collected] (first result)]
          (is (= "alice" user))
          (is (= 3 cnt))
          (is (= 90.0 avg-score))
          (is (= #{85 90 95} (set collected))))))))

(deftest sample-with-statistics-test
  (testing "Sample combined with count and median"
    (let [db (create-db)]
      (transact! db (for [i (range 20)]
                      {:id i :value (* i 10)}))

      (let [result (query db '[:find (count ?v) (median ?v) (sample 5 ?v)
                               :where [?e :value ?v]])]
        (is (= 1 (count result)))
        (let [[cnt med sampled] (first result)]
          (is (= 20 cnt))
          (is (= 95.0 med)) ; median of 0,10,20,...,190
          (is (vector? sampled))
          (is (<= (count sampled) 5))
          (is (every? #(and (>= % 0) (< % 200)) sampled)))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest empty-aggregates-test
  (testing "Aggregates on empty result set"
    (let [db (create-db)]
      (let [result (query db '[:find (count ?v) (sum ?v) (median ?v) (count-distinct ?v)
                               :where [?e :nonexistent ?v]])]
        ;; Empty result set should return single row with aggregate defaults
        (is (= 1 (count result)))
        (let [[cnt sm med cd] (first result)]
          (is (= 0 cnt))
          (is (= 0 sm))
          (is (nil? med))
          (is (= 0 cd)))))))

(deftest single-value-aggregates-test
  (testing "Aggregates on single value"
    (let [db (create-db)]
      (transact! db [{:id 1 :value 42}])

      (let [result (query db '[:find (count ?v) (median ?v) (variance ?v) (stddev ?v)
                               :where [?e :value ?v]])]
        (is (= 1 (count result)))
        (let [[cnt med var std] (first result)]
          (is (= 1 cnt))
          (is (= 42.0 med))
          (is (= 0.0 var) "Variance of single value is 0")
          (is (= 0.0 std) "Stddev of single value is 0"))))))

(deftest duplicate-values-aggregates-test
  (testing "Aggregates handle duplicate values correctly"
    (let [db (create-db)]
      ;; Same value multiple times
      (transact! db [{:id 1 :value 5}
                     {:id 2 :value 5}
                     {:id 3 :value 5}
                     {:id 4 :value 5}])

      (let [result (query db '[:find (count ?v) (count-distinct ?v) (median ?v) (variance ?v)
                               :where [?e :value ?v]])]
        (is (= 1 (count result)))
        (let [[cnt cd med var] (first result)]
          (is (= 4 cnt) "Count should be 4")
          (is (= 1 cd) "Count-distinct should be 1")
          (is (= 5.0 med) "Median should be 5")
          (is (= 0.0 var) "Variance should be 0"))))))

;; =============================================================================
;; Incremental Subscriptions with Multiple Aggregates
;; =============================================================================

(deftest multiple-aggregates-subscription-test
  (testing "Multiple aggregates update incrementally together"
    (let [db (create-db)
          results (atom [])
          sub (subscribe db {:query '[:find (count ?v) (sum ?v) (median ?v) (count-distinct ?v)
                                      :where [?e :value ?v]]
                             :mode :incremental
                             :callback #(swap! results conj %)})]

      (Thread/sleep 50)

      ;; Add first value
      (transact! db [{:id 1 :value 10}])
      (Thread/sleep 50)
      (let [[cnt sm med cd] (-> @results last :additions vec first)]
        (is (= 1 cnt))
        (is (= 10 sm))
        (is (= 10.0 med))
        (is (= 1 cd)))

      ;; Add second value (same)
      (transact! db [{:id 2 :value 10}])
      (Thread/sleep 50)
      (let [[cnt sm med cd] (-> @results last :additions vec first)]
        (is (= 2 cnt))
        (is (= 20 sm))
        (is (= 10.0 med))
        (is (= 1 cd) "Count-distinct should still be 1"))

      ;; Add different value
      (transact! db [{:id 3 :value 30}])
      (Thread/sleep 50)
      (let [[cnt sm med cd] (-> @results last :additions vec first)]
        (is (= 3 cnt))
        (is (= 50 sm))
        (is (= 10.0 med)) ; median of [10,10,30] = 10
        (is (= 2 cd) "Count-distinct should now be 2"))

      (unsubscribe sub))))

(deftest grouped-multiple-aggregates-subscription-test
  (testing "Multiple aggregates per group update incrementally"
    (let [db (create-db)
          results (atom [])
          _ (transact! db [{:cat "A" :val 10}
                           {:cat "B" :val 100}])

          sub (subscribe db {:query '[:find ?cat (count ?v) (sum ?v) (median ?v)
                                      :where
                                      [?e :cat ?cat]
                                      [?e :val ?v]]
                             :mode :incremental
                             :callback #(swap! results conj %)})]

      (Thread/sleep 200)

      ;; Initial: A has [10], B has [100]
      (is (pos? (count @results)) "Should have received callback")
      (let [initial-results (-> @results last :additions set)]
        (is (= 2 (count initial-results)) "Should have 2 groups")
        (is (contains? initial-results ["A" 1 10 10.0]))
        (is (contains? initial-results ["B" 1 100 100.0])))

      ;; Add more values to category A
      (transact! db [{:id 3 :cat "A" :val 20}
                     {:id 4 :cat "A" :val 30}])
      (Thread/sleep 100)

      ;; Category A should update: count=3, sum=60, median=20
      (let [latest-results (-> @results last :additions set)]
        (is (some #(and (= "A" (first %))
                        (= 3 (second %))
                        (= 60 (nth % 2))
                        (= 20.0 (nth % 3)))
                  latest-results)))

      (unsubscribe sub))))

;; =============================================================================
;; Aggregates with Joins
;; =============================================================================

(deftest aggregates-with-join-test
  (testing "Multiple aggregates after join"
    (let [db (create-db)]
      (transact! db [{:order/id "O1" :order/customer "C1" :order/total 100}
                     {:order/id "O2" :order/customer "C1" :order/total 200}
                     {:order/id "O3" :order/customer "C2" :order/total 150}
                     {:customer/id "C1" :customer/name "Alice"}
                     {:customer/id "C2" :customer/name "Bob"}])

      (let [result (set (query db '[:find ?name (count ?total) (sum ?total) (avg ?total) (median ?total)
                                    :where
                                    [?order :order/customer ?cid]
                                    [?order :order/total ?total]
                                    [?customer :customer/id ?cid]
                                    [?customer :customer/name ?name]]))]
        (is (= 2 (count result)))

        ;; Alice: 2 orders, sum=300, avg=150, median=150
        (let [alice (first (filter #(= "Alice" (first %)) result))
              [_ cnt sm avg-val med] alice]
          (is (= 2 cnt))
          (is (= 300 sm))
          (is (= 150.0 avg-val))
          (is (= 150.0 med)))

        ;; Bob: 1 order, sum=150, avg=150, median=150
        (let [bob (first (filter #(= "Bob" (first %)) result))
              [_ cnt sm avg-val med] bob]
          (is (= 1 cnt))
          (is (= 150 sm))
          (is (= 150.0 avg-val))
          (is (= 150.0 med)))))))

;; =============================================================================
;; Stress Tests
;; =============================================================================

(deftest many-groups-many-aggregates-test
  (testing "Many groups with many aggregates each"
    (let [db (create-db)]
      ;; Create 10 groups with 5 values each
      (transact! db (for [g (range 10)
                          v (range 5)]
                      {:group g :value (+ (* g 10) v)}))

      ;; Apply 6 aggregates per group
      (let [result (query db '[:find ?g (count ?v) (sum ?v) (avg ?v) (median ?v) (variance ?v) (count-distinct ?v)
                               :where
                               [?e :group ?g]
                               [?e :value ?v]])]
        (is (= 10 (count result)) "Should have 10 groups")

        ;; Check first group (g=0, values: 0,1,2,3,4)
        (let [g0 (first (filter #(= 0 (first %)) result))
              [_ cnt sm avg-val med var cd] g0]
          (is (= 5 cnt))
          (is (= 10 sm))
          (is (= 2.0 avg-val))
          (is (= 2.0 med))
          (is (= 5 cd)))))))

(deftest large-dataset-aggregates-test
  (testing "Aggregates on larger dataset (100 values)"
    (let [db (create-db)]
      (transact! db (for [i (range 100)]
                      {:id i :value i}))

      (let [result (query db '[:find (count ?v) (sum ?v) (median ?v) (count-distinct ?v)
                               :where [?e :value ?v]])]
        (is (= 1 (count result)))
        (let [[cnt sm med cd] (first result)]
          (is (= 100 cnt))
          (is (= 4950 sm)) ; sum of 0..99
          (is (= 49.5 med)) ; median of 0..99
          (is (= 100 cd)))))))

;; =============================================================================
;; Collect + Sample Combined
;; =============================================================================

(deftest collect-and-sample-test
  (testing "Collect all and sample subset in same query"
    (let [db (create-db)]
      (transact! db (for [i (range 10)]
                      {:id i :value i}))

      (let [result (query db '[:find (collect ?v) (sample 5 ?v) (count ?v)
                               :where [?e :value ?v]])]
        (is (= 1 (count result)))
        (let [[collected sampled cnt] (first result)]
          (is (= 10 (count collected)) "Collect should have all 10 values")
          (is (<= (count sampled) 5) "Sample should have at most 5")
          (is (= 10 cnt))
          ;; All sampled values should be in collected
          (is (every? (set collected) sampled)))))))

;; =============================================================================
;; Aggregate Result Consistency
;; =============================================================================

(deftest aggregate-consistency-test
  (testing "Count and count-distinct consistency"
    (let [db (create-db)]
      (transact! db [{:id 1 :value "a"}
                     {:id 2 :value "b"}
                     {:id 3 :value "a"}])

      (let [result (query db '[:find (count ?v) (count-distinct ?v)
                               :where [?e :value ?v]])]
        (let [[cnt cd] (first result)]
          (is (= 3 cnt) "Count includes duplicates")
          (is (= 2 cd) "Count-distinct excludes duplicates")
          (is (> cnt cd) "Count should be >= count-distinct"))))))

(deftest sum-count-avg-consistency-test
  (testing "Sum, count, and avg are consistent"
    (let [db (create-db)]
      (transact! db [{:id 1 :value 10}
                     {:id 2 :value 20}
                     {:id 3 :value 30}])

      (let [result (query db '[:find (sum ?v) (count ?v) (avg ?v)
                               :where [?e :value ?v]])]
        (let [[sm cnt avg-val] (first result)]
          (is (= 60 sm))
          (is (= 3 cnt))
          (is (= 20.0 avg-val))
          ;; Verify: avg = sum / count
          (is (= avg-val (/ sm (double cnt)))))))))
