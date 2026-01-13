(ns dfdb.dd-operators-test
  "Tests for differential dataflow operators."
  (:require [clojure.test :refer [deftest is testing]]
            [dfdb.dd.multiset :as ms]
            [dfdb.dd.difference :as diff]
            [dfdb.dd.operator :as op]
            [dfdb.dd.aggregate :as agg]))

(defn output-as-set
  "Convert operator output to set for comparison."
  [output]
  (if (coll? output)
    (set output)
    output))

(deftest test-multiset-operations
  (testing "Multiset creation and operations"
    (let [ms1 (ms/multiset {"Alice" 2, "Bob" 1})]
      (is (= 3 (count ms1)))
      (is (= 2 (ms/get-count ms1 "Alice")))
      (is (= 1 (ms/get-count ms1 "Bob")))

      (let [ms2 (ms/add ms1 "Charlie" 1)]
        (is (= 4 (count ms2)))
        (is (= 1 (ms/get-count ms2 "Charlie"))))

      (let [ms3 (ms/remove-elem ms1 "Alice" 1)]
        (is (= 2 (count ms3)))
        (is (= 1 (ms/get-count ms3 "Alice")))))))

(deftest test-difference-operations
  (testing "Difference creation and application"
    (let [d (diff/difference {"A" 2} {"B" 1})]
      (is (= {"A" 2} (.additions d)))
      (is (= {"B" 1} (.retractions d))))

    (let [d (diff/add-element (diff/empty-difference) "X" 3)]
      (is (= {"X" 3} (.additions d))))

    (let [ms (ms/multiset {"A" 1, "B" 2})
          d (diff/difference {"C" 1} {"B" 1})
          result (diff/apply-difference ms d)]
      (is (= 1 (ms/get-count result "A")))
      (is (= 1 (ms/get-count result "B")))
      (is (= 1 (ms/get-count result "C"))))))

(deftest test-map-operator
  (testing "MapOperator transforms values"
    (let [collect (op/make-collect-operator)
          mapper (op/make-map-operator (fn [x] (* x 2)) collect)
          input (ms/multiset {1 1, 2 1, 3 1})]

      (op/input mapper input 1)
      (is (= #{2 4 6} (output-as-set (op/output collect)))))))

(deftest test-filter-operator
  (testing "FilterOperator filters by predicate"
    (let [collect (op/make-collect-operator)
          filterer (op/make-filter-operator (fn [x] (> x 2)) collect)
          input (ms/multiset {1 1, 2 1, 3 1, 4 1})]

      (op/input filterer input 1)
      (is (= #{3 4} (output-as-set (op/output collect)))))))

(deftest test-aggregate-operator
  (testing "AggregateOperator groups and aggregates"
    (let [collect (op/make-collect-operator)
          aggregator (agg/make-aggregate-operator
                      first
                      (fn [vals] (reduce + 0 (map second vals)))
                      collect)
          input (ms/multiset {["Eng" 100] 1, ["Eng" 200] 1, ["Sales" 150] 1})]

      (op/input aggregator input 1)
      (is (= #{["Eng" 300] ["Sales" 150]} (output-as-set (op/output collect)))))))

(deftest test-operator-chaining
  (testing "Operators can be chained"
    (let [collect (op/make-collect-operator)
          filterer (op/make-filter-operator (fn [x] (> x 2)) collect)  ; Filter > 2 after mapping
          mapper (op/make-map-operator (fn [x] (* x 2)) filterer)
          input (ms/multiset {1 1, 2 1, 3 1})]

      ;; Map: {1 2 3} -> {2 4 6}
      ;; Filter: {2 4 6} -> {4 6} (only values > 2)
      (op/input mapper input 1)
      (is (= #{4 6} (output-as-set (op/output collect)))))))

(deftest test-collect-operator-accumulation
  (testing "CollectOperator accumulates with negative counts (retractions)"
    (let [collect (op/make-collect-operator)]

      ;; Add A
      (op/input collect (ms/multiset {"A" 1}) 1)
      (is (= #{"A"} (output-as-set (op/output collect))))

      ;; Retract A, add B
      (op/input collect (ms/multiset {"A" -1, "B" 1}) 2)
      (let [output (op/output collect)]
        (is (= #{"B"} (output-as-set output)) "Should only have B (A cancelled out)"))

      ;; Add C (count 2)
      (op/input collect (ms/multiset {"C" 2}) 3)
      (is (= #{"B" "C"} (output-as-set (op/output collect))))
      (is (= 3 (count (op/output collect))) "C appears twice (multiplicity 2)"))))

(deftest test-incremental-update-scenario
  (testing "Full incremental update scenario (add, update, delete)"
    (let [collect (op/make-collect-operator)]

      ;; Initial: Add Alice
      (op/input collect (ms/multiset {{"name" "Alice"} 1}) 1)
      (is (= #{{"name" "Alice"}} (output-as-set (op/output collect))))

      ;; Update: Retract Alice, Add Alice Smith
      (op/input collect (ms/multiset {{"name" "Alice"} -1, {"name" "Alice Smith"} 1}) 2)
      (is (= #{{"name" "Alice Smith"}} (output-as-set (op/output collect)))
          "Should only have Alice Smith after update")

      ;; Delete: Retract Alice Smith
      (op/input collect (ms/multiset {{"name" "Alice Smith"} -1}) 3)
      (is (empty? (op/output collect))
          "Should be empty after delete"))))
