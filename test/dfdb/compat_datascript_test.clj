(ns dfdb.compat-datascript-test
  "Compatibility tests based on DataScript test suite.
  Ensures dfdb Datalog engine is compatible with DataScript/Datomic semantics."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

;; =============================================================================
;; Based on datascript/test/datascript/test/query.cljc
;; https://github.com/tonsky/datascript/blob/master/test/datascript/test/query.cljc
;; =============================================================================

(deftest test-joins
  (testing "Basic Datalog joins (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :name "Ivan" :age 15}
                     {:db/id 2 :name "Petr" :age 37}
                     {:db/id 3 :name "Ivan" :age 37}
                     {:db/id 4 :age 15}])

      ;; Simple pattern
      (is (= (query db '[:find ?e
                         :where [?e :name _]])
             #{[1] [2] [3]}))

      ;; Two patterns with constant
      (is (= (query db '[:find ?e ?v
                         :where
                         [?e :name "Ivan"]
                         [?e :age ?v]])
             #{[1 15] [3 37]}))

      ;; Self-join
      (is (= (query db '[:find ?e1 ?e2
                         :where
                         [?e1 :name ?n]
                         [?e2 :name ?n]])
             #{[1 1] [2 2] [3 3] [1 3] [3 1]}))

      ;; Multi-way join
      (is (= (query db '[:find ?e ?e2 ?n
                         :where
                         [?e :name "Ivan"]
                         [?e :age ?a]
                         [?e2 :age ?a]
                         [?e2 :name ?n]])
             #{[1 1 "Ivan"]
               [3 3 "Ivan"]
               [3 2 "Petr"]})))))

(deftest test-q-with-constants
  (testing "Queries with constant values (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:name "Ivan" :age 15}
                     {:name "Petr" :age 37}
                     {:name "Ivan" :age 37}])

      ;; Constant in second position
      (is (= (query db '[:find ?e
                         :where [?e :name "Ivan"]])
             #{[1] [3]}))

      ;; Constant in third position
      (is (= (query db '[:find ?e
                         :where [?e :age 37]])
             #{[2] [3]}))

      ;; Multiple constants
      (is (= (query db '[:find ?e
                         :where
                         [?e :name "Ivan"]
                         [?e :age 37]])
             #{[3]})))))

(deftest test-q-many-cardinality
  (testing "Multi-valued attributes (DataScript cardinality many)"
    (let [db (create-db)]
      ;; In dfdb, vectors are automatically multi-valued
      (transact! db [{:db/id 1 :name "Ivan" :aka ["ivolga" "pi"]}
                     {:db/id 2 :name "Petr" :aka ["porosenok" "pi"]}])

      ;; Query finds both entities with shared aka value
      (let [result (query db '[:find ?n1 ?n2
                               :where
                               [?e1 :aka ?x]
                               [?e2 :aka ?x]
                               [?e1 :name ?n1]
                               [?e2 :name ?n2]])]
        ;; Should find cross-product of entities with same aka
        (is (>= (count result) 2))))))  ; At least self-matches

(deftest test-predicates
  (testing "Predicate functions (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:name "Ivan" :age 15}
                     {:name "Petr" :age 37}
                     {:name "Oleg" :age 42}])

      ;; Greater than
      (is (= (query db '[:find ?name
                         :where
                         [?e :name ?name]
                         [?e :age ?age]
                         [(> ?age 30)]])
             #{["Petr"] ["Oleg"]}))

      ;; Less than or equal
      (is (= (query db '[:find ?name
                         :where
                         [?e :name ?name]
                         [?e :age ?age]
                         [(<= ?age 37)]])
             #{["Ivan"] ["Petr"]}))

      ;; Equality
      (is (= (query db '[:find ?name
                         :where
                         [?e :name ?name]
                         [?e :age ?age]
                         [(= ?age 37)]])
             #{["Petr"]})))))

(deftest test-aggregates
  (testing "Aggregate functions (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:name "Ivan" :age 15}
                     {:name "Petr" :age 37}
                     {:name "Oleg" :age 42}])

      ;; Count
      (is (= (query db '[:find (count ?e)
                         :where [?e :name _]])
             #{[3]}))

      ;; Sum
      (is (= (query db '[:find (sum ?age)
                         :where [?e :age ?age]])
             #{[94]}))

      ;; Min
      (is (= (query db '[:find (min ?age)
                         :where [?e :age ?age]])
             #{[15]}))

      ;; Max
      (is (= (query db '[:find (max ?age)
                         :where [?e :age ?age]])
             #{[42]}))

      ;; Avg
      (let [result (query db '[:find (avg ?age)
                               :where [?e :age ?age]])]
        (is (= 1 (count result)))
        (is (< 31.0 (ffirst result) 32.0))))))  ; ~31.33

(deftest test-aggregates-with-grouping
  (testing "Aggregates with grouping (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:dept "Engineering" :name "Alice" :salary 100000}
                     {:dept "Engineering" :name "Bob" :salary 120000}
                     {:dept "Sales" :name "Charlie" :salary 80000}
                     {:dept "Sales" :name "Dave" :salary 90000}])

      ;; Count by group
      (is (= (query db '[:find ?dept (count ?e)
                         :where
                         [?e :dept ?dept]])
             #{["Engineering" 2] ["Sales" 2]}))

      ;; Sum by group
      (is (= (query db '[:find ?dept (sum ?salary)
                         :where
                         [?e :dept ?dept]
                         [?e :salary ?salary]])
             #{["Engineering" 220000] ["Sales" 170000]}))

      ;; Avg by group
      (is (= (query db '[:find ?dept (avg ?salary)
                         :where
                         [?e :dept ?dept]
                         [?e :salary ?salary]])
             #{["Engineering" 110000.0] ["Sales" 85000.0]})))))

(deftest test-not-clause
  (testing "NOT clause (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:name "Ivan" :age 15}
                     {:name "Petr" :age 37}
                     {:name "Oleg"}])  ; No age

      ;; Find entities without age
      (is (= (query db '[:find ?name
                         :where
                         [?e :name ?name]
                         (not [?e :age _])])
             #{["Oleg"]}))

      ;; NOT with specific value
      (is (= (query db '[:find ?name
                         :where
                         [?e :name ?name]
                         (not [?e :age 37])])
             #{["Ivan"] ["Oleg"]})))))

(deftest test-not-join
  (testing "NOT clause with joins (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :name "Ivan" :age 15}
                     {:db/id 2 :name "Petr" :age 37}
                     {:db/id 3 :name "Oleg" :age 42}
                     {:order/user 1 :order/total 100}
                     {:order/user 2 :order/total 200}])

      ;; Find users without orders
      (is (= (query db '[:find ?name
                         :where
                         [?e :name ?name]
                         (not [?order :order/user ?e])])
             #{["Oleg"]})))))

(deftest test-expression-bindings
  (testing "Expression bindings (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:name "Ivan" :age 15}
                     {:name "Petr" :age 37}])

      ;; Bind result of computation
      (is (= (query db '[:find ?name ?adult
                         :where
                         [?e :name ?name]
                         [?e :age ?age]
                         [(>= ?age 18) ?adult]])
             #{["Ivan" false] ["Petr" true]}))

      ;; Arithmetic binding
      (is (= (query db '[:find ?name ?age-next-year
                         :where
                         [?e :name ?name]
                         [?e :age ?age]
                         [(+ ?age 1) ?age-next-year]])
             #{["Ivan" 16] ["Petr" 38]})))))

(deftest test-wildcards
  (testing "Wildcard _ (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:name "Ivan" :age 15 :email "ivan@mail.ru"}
                     {:name "Petr" :age 37}])

      ;; Wildcard matches anything
      (is (= (query db '[:find ?name
                         :where
                         [?e :name ?name]
                         [?e :age _]])  ; Has age (any value)
             #{["Ivan"] ["Petr"]}))

      ;; Find entities with email (any value)
      (is (= (query db '[:find ?name
                         :where
                         [?e :name ?name]
                         [?e :email _]])
             #{["Ivan"]})))))

(deftest test-constants-in-all-positions
  (testing "Constants in all pattern positions (DataScript compatible)"
    (let [db (create-db)]
      (transact! db [{:db/id 1 :name "Ivan" :age 15}
                     {:db/id 2 :name "Petr" :age 37}])

      ;; Constant entity
      (is (= (query db '[:find ?name
                         :where [1 :name ?name]])
             #{["Ivan"]}))

      ;; Constant attribute (already tested)
      (is (= (query db '[:find ?e
                         :where [?e :age _]])
             #{[1] [2]}))

      ;; Constant value
      (is (= (query db '[:find ?e
                         :where [?e :name "Petr"]])
             #{[2]}))

      ;; All constants (verification)
      (is (= (query db '[:find 1
                         :where [1 :name "Ivan"]])
             #{[1]})))))

;; Sources:
;; - [DataScript GitHub Repository](https://github.com/tonsky/datascript)
;; - [DataScript Query Tests](https://github.com/tonsky/datascript/blob/master/test/datascript/test/query.cljc)
;; - [Datalevin GitHub Repository](https://github.com/juji-io/datalevin)
;; - [Datalevin Query Documentation](https://github.com/juji-io/datalevin/blob/master/doc/query.md)
