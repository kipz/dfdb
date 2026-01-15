(ns dfdb.advanced-aggregates-test
  "Tests for advanced aggregate functions: median, variance, stddev, count-distinct, collect, sample, rand"
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Count-Distinct Tests
;; =============================================================================

(deftest count-distinct-basic-test
  (testing "Count distinct values in simple query"
    (let [db (create-db)]
      (transact! db [{:id 1 :color "red"}
                     {:id 2 :color "blue"}
                     {:id 3 :color "red"}
                     {:id 4 :color "green"}
                     {:id 5 :color "blue"}])

      (is (= #{[3]}
             (query db '[:find (count-distinct ?color)
                         :where [?e :color ?color]]))
          "Should count 3 distinct colors: red, blue, green"))))

(deftest count-distinct-with-grouping-test
  (testing "Count distinct values with grouping"
    (let [db (create-db)]
      (transact! db [{:category "A" :item "x"}
                     {:category "A" :item "y"}
                     {:category "A" :item "x"}
                     {:category "B" :item "x"}
                     {:category "B" :item "z"}
                     {:category "B" :item "z"}])

      (is (= #{["A" 2] ["B" 2]}
             (set (query db '[:find ?cat (count-distinct ?item)
                              :where
                              [?e :category ?cat]
                              [?e :item ?item]])))
          "Category A has 2 distinct items (x, y), B has 2 (x, z)"))))

(deftest count-distinct-incremental-test
  (testing "Count-distinct updates incrementally"
    (let [db (create-db)
          results (atom [])
          sub (subscribe db {:query '[:find (count-distinct ?color)
                                      :where [?e :color ?color]]
                             :callback #(swap! results conj %)})]

      ;; Initial state: 0 distinct colors
      (is (empty? (:additions (first @results))))

      ;; Add first color: red
      (transact! db [{:id 1 :color "red"}])
      (Thread/sleep 50)
      (is (= [[1]] (-> @results last :additions vec))
          "Should have 1 distinct color")

      ;; Add duplicate: red again
      (transact! db [{:id 2 :color "red"}])
      (Thread/sleep 50)
      ;; Count should still be 1 (red is not new)

      ;; Add new color: blue
      (transact! db [{:id 3 :color "blue"}])
      (Thread/sleep 50)
      (is (= [[2]] (-> @results last :additions vec))
          "Should have 2 distinct colors")

      (unsubscribe sub))))

;; =============================================================================
;; Variance and Standard Deviation Tests
;; =============================================================================

(deftest variance-basic-test
  (testing "Calculate variance of values"
    (let [db (create-db)]
      ;; Values: 2, 4, 6, 8 -> mean = 5, variance = 5
      (transact! db [{:id 1 :value 2}
                     {:id 2 :value 4}
                     {:id 3 :value 6}
                     {:id 4 :value 8}])

      (let [result (query db '[:find (variance ?value)
                               :where [?e :value ?value]])]
        (is (= 1 (count result)))
        (is (< (Math/abs (- 5.0 (ffirst result))) 0.01)
            "Variance should be 5.0")))))

(deftest stddev-basic-test
  (testing "Calculate standard deviation of values"
    (let [db (create-db)]
      ;; Values: 2, 4, 6, 8 -> stddev = sqrt(5) ≈ 2.236
      (transact! db [{:id 1 :value 2}
                     {:id 2 :value 4}
                     {:id 3 :value 6}
                     {:id 4 :value 8}])

      (let [result (query db '[:find (stddev ?value)
                               :where [?e :value ?value]])]
        (is (= 1 (count result)))
        (is (< (Math/abs (- 2.236 (ffirst result))) 0.01)
            "Stddev should be approximately 2.236")))))

(deftest variance-with-grouping-test
  (testing "Calculate variance per group"
    (let [db (create-db)]
      (transact! db [{:group "A" :value 2}
                     {:group "A" :value 4}
                     {:group "A" :value 6}
                     {:group "B" :value 10}
                     {:group "B" :value 20}])

      (let [result (set (query db '[:find ?group (variance ?value)
                                    :where
                                    [?e :group ?group]
                                    [?e :value ?value]]))]
        (is (= 2 (count result)))
        ;; Group A: values 2,4,6 -> variance ≈ 2.67
        ;; Group B: values 10,20 -> variance = 25
        (is (some (fn [[g v]] (and (= g "A") (< (Math/abs (- 2.67 v)) 0.1))) result))
        (is (some (fn [[g v]] (and (= g "B") (< (Math/abs (- 25.0 v)) 0.1))) result))))))

(deftest variance-incremental-test
  (testing "Variance updates incrementally using Welford's algorithm"
    (let [db (create-db)
          results (atom [])
          sub (subscribe db {:query '[:find (variance ?value)
                                      :where [?e :value ?value]]
                             :callback #(swap! results conj %)})]

      ;; Add first value: 5
      (transact! db [{:id 1 :value 5}])
      (Thread/sleep 50)
      (is (< (Math/abs (-> @results last :additions ffirst)) 0.01)
          "Variance of single value should be 0")

      ;; Add second value: 15 -> variance should update
      (transact! db [{:id 2 :value 15}])
      (Thread/sleep 50)
      (let [var-val (-> @results last :additions ffirst)]
        (is (< (Math/abs (- 25.0 var-val)) 0.1)
            "Variance of [5, 15] should be 25"))

      (unsubscribe sub))))

;; =============================================================================
;; Median Tests
;; =============================================================================

(deftest median-odd-count-test
  (testing "Calculate median with odd number of values"
    (let [db (create-db)]
      ;; Values: 1, 3, 5, 7, 9 -> median = 5
      (transact! db [{:id 1 :value 1}
                     {:id 2 :value 3}
                     {:id 3 :value 5}
                     {:id 4 :value 7}
                     {:id 5 :value 9}])

      (is (= #{[5.0]}
             (query db '[:find (median ?value)
                         :where [?e :value ?value]]))
          "Median of [1,3,5,7,9] should be 5"))))

(deftest median-even-count-test
  (testing "Calculate median with even number of values"
    (let [db (create-db)]
      ;; Values: 2, 4, 6, 8 -> median = (4+6)/2 = 5
      (transact! db [{:id 1 :value 2}
                     {:id 2 :value 4}
                     {:id 3 :value 6}
                     {:id 4 :value 8}])

      (is (= #{[5.0]}
             (query db '[:find (median ?value)
                         :where [?e :value ?value]]))
          "Median of [2,4,6,8] should be 5"))))

(deftest median-with-grouping-test
  (testing "Calculate median per group"
    (let [db (create-db)]
      (transact! db [{:group "A" :value 1}
                     {:group "A" :value 3}
                     {:group "A" :value 5}
                     {:group "B" :value 10}
                     {:group "B" :value 20}
                     {:group "B" :value 30}])

      (is (= #{["A" 3.0] ["B" 20.0]}
             (set (query db '[:find ?group (median ?value)
                              :where
                              [?e :group ?group]
                              [?e :value ?value]])))
          "Group A median should be 3, Group B median should be 20"))))

(deftest median-incremental-test
  (testing "Median updates incrementally"
    (let [db (create-db)
          results (atom [])
          sub (subscribe db {:query '[:find (median ?value)
                                      :where [?e :value ?value]]
                             :callback #(swap! results conj %)})]

      ;; Add first value: 5
      (transact! db [{:id 1 :value 5}])
      (Thread/sleep 50)
      (is (= 5.0 (-> @results last :additions ffirst))
          "Median of [5] should be 5")

      ;; Add second value: 3 -> median becomes 4
      (transact! db [{:id 2 :value 3}])
      (Thread/sleep 50)
      (is (= 4.0 (-> @results last :additions ffirst))
          "Median of [3,5] should be 4")

      ;; Add third value: 7 -> median becomes 5
      (transact! db [{:id 3 :value 7}])
      (Thread/sleep 50)
      (is (= 5.0 (-> @results last :additions ffirst))
          "Median of [3,5,7] should be 5")

      (unsubscribe sub))))

;; =============================================================================
;; Collect Tests
;; =============================================================================

(deftest collect-basic-test
  (testing "Collect all values into a vector"
    (let [db (create-db)]
      (transact! db [{:id 1 :tag "clojure"}
                     {:id 2 :tag "datalog"}
                     {:id 3 :tag "database"}])

      (let [result (query db '[:find (collect ?tag)
                               :where [?e :tag ?tag]])]
        (is (= 1 (count result)))
        (let [collected (ffirst result)]
          (is (vector? collected) "Result should be a vector")
          (is (= 3 (count collected)) "Should collect 3 tags")
          (is (= #{"clojure" "datalog" "database"} (set collected))
              "Should contain all three tags"))))))

(deftest collect-with-grouping-test
  (testing "Collect values per group"
    (let [db (create-db)]
      (transact! db [{:user "alice" :tag "clojure"}
                     {:user "alice" :tag "datalog"}
                     {:user "bob" :tag "rust"}
                     {:user "bob" :tag "systems"}])

      (let [result (into {} (query db '[:find ?user (collect ?tag)
                                        :where
                                        [?e :user ?user]
                                        [?e :tag ?tag]]))]
        (is (= 2 (count result)))
        (is (= #{"clojure" "datalog"} (set (get result "alice"))))
        (is (= #{"rust" "systems"} (set (get result "bob"))))))))

(deftest collect-incremental-test
  (testing "Collect updates incrementally"
    (let [db (create-db)
          results (atom [])
          sub (subscribe db {:query '[:find (collect ?tag)
                                      :where [?e :tag ?tag]]
                             :callback #(swap! results conj %)})]

      ;; Add first tag
      (transact! db [{:id 1 :tag "clojure"}])
      (Thread/sleep 50)
      (is (= ["clojure"] (-> @results last :additions ffirst))
          "Should collect first tag")

      ;; Add second tag
      (transact! db [{:id 2 :tag "datalog"}])
      (Thread/sleep 50)
      (let [collected (-> @results last :additions ffirst)]
        (is (= 2 (count collected)))
        (is (= #{"clojure" "datalog"} (set collected))))

      (unsubscribe sub))))

;; =============================================================================
;; Sample Tests
;; =============================================================================

(deftest sample-basic-test
  (testing "Sample k elements from collection"
    (let [db (create-db)]
      ;; Add 10 values
      (transact! db (for [i (range 10)]
                      {:id i :value i}))

      (let [result (query db '[:find (sample 5 ?value)
                               :where [?e :value ?value]])]
        (is (= 1 (count result)))
        (let [sampled (ffirst result)]
          (is (vector? sampled) "Result should be a vector")
          (is (<= (count sampled) 5) "Should sample at most 5 elements")
          (is (every? #(< % 10) sampled) "All sampled values should be < 10"))))))

(deftest sample-fewer-than-k-test
  (testing "Sample when collection has fewer than k elements"
    (let [db (create-db)]
      (transact! db [{:id 1 :value 1}
                     {:id 2 :value 2}
                     {:id 3 :value 3}])

      (let [result (query db '[:find (sample 10 ?value)
                               :where [?e :value ?value]])]
        (is (= 1 (count result)))
        (let [sampled (ffirst result)]
          (is (= 3 (count sampled)) "Should return all 3 elements when requesting 10"))))))

;; =============================================================================
;; Rand Tests
;; =============================================================================

(deftest rand-basic-test
  (testing "Select one random element"
    (let [db (create-db)]
      (transact! db [{:id 1 :value "a"}
                     {:id 2 :value "b"}
                     {:id 3 :value "c"}])

      (let [result (query db '[:find (rand ?value)
                               :where [?e :value ?value]])]
        (is (= 1 (count result)))
        (let [random-val (ffirst result)]
          (is (contains? #{"a" "b" "c"} random-val)
              "Should return one of the three values"))))))

(deftest rand-with-grouping-test
  (testing "Select random element per group"
    (let [db (create-db)]
      (transact! db [{:group "A" :value 1}
                     {:group "A" :value 2}
                     {:group "B" :value 10}
                     {:group "B" :value 20}])

      (let [result (into {} (query db '[:find ?group (rand ?value)
                                        :where
                                        [?e :group ?group]
                                        [?e :value ?value]]))]
        (is (= 2 (count result)))
        (is (contains? #{1 2} (get result "A")))
        (is (contains? #{10 20} (get result "B")))))))

;; =============================================================================
;; Combined Aggregates Test
;; =============================================================================

(deftest multiple-advanced-aggregates-test
  (testing "Use multiple advanced aggregates in one query"
    (let [db (create-db)]
      (transact! db [{:id 1 :value 2}
                     {:id 2 :value 4}
                     {:id 3 :value 6}
                     {:id 4 :value 8}])

      (let [result (query db '[:find (median ?value) (variance ?value) (stddev ?value) (count-distinct ?value)
                               :where [?e :value ?value]])]
        (is (= 1 (count result)))
        (let [[med var std cnt] (first result)]
          (is (< (Math/abs (- 5.0 med)) 0.01) "Median should be 5")
          (is (< (Math/abs (- 5.0 var)) 0.01) "Variance should be 5")
          (is (< (Math/abs (- 2.236 std)) 0.01) "Stddev should be ~2.236")
          (is (= 4 cnt) "Count-distinct should be 4"))))))
