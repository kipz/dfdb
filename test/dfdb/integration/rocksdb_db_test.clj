(ns dfdb.integration.rocksdb-db-test
  "Integration tests for dfdb with RocksDB backend."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dfdb.transaction :as tx]
            [dfdb.db :as db]
            [dfdb.storage :as storage]
            [dfdb.storage.rocksdb :as rocksdb])
  (:import [java.io File]))

(def ^:dynamic *test-db-path* nil)
(def ^:dynamic *test-db* nil)

(defn temp-db-path
  "Create a temporary path for test database."
  []
  (let [tmp-dir (System/getProperty "java.io.tmpdir")
        db-name (str "dfdb-integration-test-" (System/currentTimeMillis) "-" (rand-int 10000))]
    (str tmp-dir File/separator db-name)))

(defn with-rocksdb-database
  "Fixture to create and cleanup RocksDB-backed database for each test."
  [f]
  (let [path (temp-db-path)]
    (binding [*test-db-path* path
              *test-db* (db/create-db {:storage-config {:type :rocksdb :path path}})]
      (try
        (f)
        (finally
          ;; Close storage
          (try
            (storage/close (:storage *test-db*))
            (catch Exception _e
              nil))
          ;; Clean up test database
          (try
            (rocksdb/destroy-rocksdb-storage path)
            (catch Exception _e
              nil)))))))

(use-fixtures :each with-rocksdb-database)

(deftest basic-transaction
  (testing "Basic transaction with RocksDB backend"
    (let [result (tx/transact! *test-db*
                               [{:user/name "Alice"
                                 :user/email "alice@example.com"}])]
      (is (some? (:tx-id result)))
      (is (some? (:tx-time result)))
      (is (seq (:deltas result)))

      ;; Check we can retrieve the entity
      (let [entities (db/entity-by *test-db* :user/email "alice@example.com")]
        (is (= "Alice" (:user/name entities)))
        (is (= "alice@example.com" (:user/email entities)))))))

(deftest multiple-transactions
  (testing "Multiple transactions persist correctly"
    ;; First transaction
    (tx/transact! *test-db*
                  [{:user/name "Alice"
                    :user/email "alice@example.com"}])

    ;; Second transaction
    (tx/transact! *test-db*
                  [{:user/name "Bob"
                    :user/email "bob@example.com"}])

    ;; Third transaction
    (tx/transact! *test-db*
                  [{:user/name "Charlie"
                    :user/email "charlie@example.com"}])

    ;; Verify all entities exist
    (let [alice (db/entity-by *test-db* :user/email "alice@example.com")
          bob (db/entity-by *test-db* :user/email "bob@example.com")
          charlie (db/entity-by *test-db* :user/email "charlie@example.com")]
      (is (= "Alice" (:user/name alice)))
      (is (= "Bob" (:user/name bob)))
      (is (= "Charlie" (:user/name charlie))))))

(deftest entity-updates
  (testing "Entity updates work correctly"
    ;; Create entity
    (let [result1 (tx/transact! *test-db*
                                [{:user/name "Alice"
                                  :user/email "alice@example.com"
                                  :user/age 30}])
          ;; Get entity ID from deltas
          entity-id (->> (:deltas result1)
                         (map :entity)
                         first)]

      ;; Update entity
      (tx/transact! *test-db*
                    [[:db/add entity-id :user/age 31]
                     [:db/add entity-id :user/city "New York"]])

      ;; Verify updates
      (let [entity (db/entity *test-db* entity-id)]
        (is (= "Alice" (:user/name entity)))
        (is (= "alice@example.com" (:user/email entity)))
        (is (= 31 (:user/age entity)))
        (is (= "New York" (:user/city entity)))))))

(deftest lookup-refs
  (testing "Lookup refs work with RocksDB"
    ;; Create Alice first
    (tx/transact! *test-db*
                  [{:user/name "Alice"
                    :user/email "alice@example.com"}])

    ;; Create Bob with reference to Alice
    (tx/transact! *test-db*
                  [{:user/name "Bob"
                    :user/email "bob@example.com"
                    :user/manager [:user/email "alice@example.com"]}])

    ;; Verify manager reference was resolved
    (let [bob (db/entity-by *test-db* :user/email "bob@example.com")
          alice (db/entity-by *test-db* :user/email "alice@example.com")]
      (is (= (:db/id alice) (:user/manager bob))))))

(deftest batch-operations
  (testing "Large batch of operations"
    ;; Create 100 users in one transaction
    (let [users (for [i (range 100)]
                  {:user/name (str "User-" i)
                   :user/email (str "user" i "@example.com")
                   :user/age (+ 20 (mod i 50))})]
      (tx/transact! *test-db* users))

    ;; Verify all users exist
    (let [user-50 (db/entity-by *test-db* :user/email "user50@example.com")]
      (is (= "User-50" (:user/name user-50)))
      (is (= 20 (:user/age user-50)))  ; 20 + (50 mod 50) = 20

      (let [user-99 (db/entity-by *test-db* :user/email "user99@example.com")]
        (is (= "User-99" (:user/name user-99)))
        (is (= 69 (:user/age user-99)))))))  ; 20 + (99 mod 50) = 69

(deftest persistence-across-reopens
  (testing "Data persists when database is reopened"
    (let [path *test-db-path*]
      ;; Create some data
      (tx/transact! *test-db*
                    [{:user/name "Persistent User"
                      :user/email "persistent@example.com"}])

      ;; Close the database
      (storage/close (:storage *test-db*))

      ;; Reopen with new database instance
      (let [db2 (db/create-db {:storage-config {:type :rocksdb :path path}})]
        (try
          ;; Verify data is still there
          (let [user (db/entity-by db2 :user/email "persistent@example.com")]
            (is (= "Persistent User" (:user/name user))))
          (finally
            (storage/close (:storage db2))))))))

(deftest entity-history
  (testing "Entity history is maintained"
    ;; Create entity
    (let [result (tx/transact! *test-db*
                               [{:user/name "Alice"
                                 :user/email "alice@example.com"
                                 :user/age 30}])
          entity-id (->> (:deltas result)
                         (map :entity)
                         first)]

      ;; Update multiple times
      (tx/transact! *test-db* [[:db/add entity-id :user/age 31]])
      (Thread/sleep 10)
      (tx/transact! *test-db* [[:db/add entity-id :user/age 32]])
      (Thread/sleep 10)

      ;; Current state should be 32
      (let [entity (db/entity *test-db* entity-id)]
        (is (= 32 (:user/age entity)))))))
