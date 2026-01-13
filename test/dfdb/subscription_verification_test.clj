(ns dfdb.subscription-verification-test
  "Manual verification tests for subscriptions without Thread/sleep."
  (:require [clojure.test :refer :all]
            [dfdb.core :refer :all]))

(deftest test-basic-updates-verified
  (testing "Basic subscription with add/update/delete (verified manually)"
    (let [db (create-db)
          updates (atom [])]

      (subscribe db {:query '[:find ?name :where [?e :user/name ?name]]
                     :callback (fn [diff] (swap! updates conj diff))})

      ;; Initial
      (is (= {:additions #{} :retractions #{}} (first @updates)))

      ;; Add Alice
      (transact! db [{:user/name "Alice"}])
      (is (= {:additions #{["Alice"]} :retractions #{}} (nth @updates 1)))

      ;; Add Bob
      (transact! db [{:user/name "Bob"}])
      (is (= {:additions #{["Bob"]} :retractions #{}} (nth @updates 2)))

      ;; Update Alice
      (transact! db [[:db/add 1 :user/name "Alice Smith"]])
      (is (= {:additions #{["Alice Smith"]} :retractions #{["Alice"]}} (nth @updates 3))))))

(deftest test-filtered-subscription-verified
  (testing "Subscription with predicate filter"
    (let [db (create-db)
          updates (atom [])]

      (subscribe db {:query '[:find ?order-id ?total
                              :where
                              [?order :order/id ?order-id]
                              [?order :order/total ?total]
                              [(> ?total 1000)]]
                     :callback (fn [diff] (swap! updates conj diff))})

      ;; Below threshold - no notification
      (transact! db [{:order/id "ORD-1" :order/total 50}])
      (is (= 1 (count @updates)) "No update for below threshold")

      ;; Above threshold - notified
      (transact! db [{:order/id "ORD-2" :order/total 5000}])
      (is (= {:additions #{["ORD-2" 5000]} :retractions #{}} (nth @updates 1)))

      ;; Update to drop below - retracted
      (transact! db [[:db/add [:order/id "ORD-2"] :order/total 500]])
      (is (= {:additions #{} :retractions #{["ORD-2" 5000]}} (nth @updates 2))))))

(deftest test-multi-pattern-join-verified
  (testing "Multi-pattern subscription with join"
    (let [db (create-db)
          updates (atom [])]

      (subscribe db {:query '[:find ?name ?age
                              :where
                              [?e :user/name ?name]
                              [?e :user/age ?age]]
                     :callback (fn [diff] (swap! updates conj diff))})

      ;; Add name only - no complete join yet
      (transact! db [{:user/name "Alice"}])

      ;; Add age - join completes
      (transact! db [[:db/add 1 :user/age 30]])
      (is (contains? (:additions (last @updates)) ["Alice" 30])
          "Join emitted when both patterns satisfied"))))

(deftest test-aggregation-subscription-verified
  (testing "Aggregation subscription shows diff"
    (let [db (create-db)
          updates (atom [])]

      (subscribe db {:query '[:find ?customer (sum ?total)
                              :where
                              [?order :order/customer ?customer]
                              [?order :order/total ?total]]
                     :callback (fn [diff] (swap! updates conj diff))})

      ;; First order
      (transact! db [{:order/customer 1 :order/total 100}])
      (is (= {:additions #{[1 100]} :retractions #{}} (nth @updates 1)))

      ;; Second order - aggregate updates
      (transact! db [{:order/customer 1 :order/total 50}])
      (is (= {:additions #{[1 150]} :retractions #{[1 100]}} (nth @updates 2))
          "Old aggregate retracted, new aggregate added"))))

(deftest test-recursive-subscription-verified
  (testing "Recursive query subscription"
    (let [db (create-db)
          updates (atom [])]

      ;; Initial hierarchy
      (transact! db [{:emp/id 1 :emp/name "CEO"}
                     {:emp/id 2 :emp/name "VP" :emp/reports-to 1}])

      (subscribe db {:query '[:find ?name
                              :where
                              [?ceo :emp/name "CEO"]
                              [?report :emp/reports-to+ ?ceo]
                              [?report :emp/name ?name]]
                     :callback (fn [diff] (swap! updates conj diff))})

      (is (contains? (:additions (first @updates)) ["VP"]) "Initial state includes VP")

      ;; Add new employee
      (transact! db [{:emp/id 3 :emp/name "IC" :emp/reports-to 2}])
      (is (contains? (:additions (nth @updates 1)) ["IC"]) "New report added"))))
