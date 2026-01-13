(ns dfdb.integration.basic-crud-rocksdb-test
  "Run basic-crud tests with RocksDB backend to verify compatibility."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dfdb.db :as db]
            [dfdb.transaction :as tx]
            [dfdb.storage :as storage]
            [dfdb.storage.rocksdb :as rocksdb])
  (:import [java.io File]))

(def ^:dynamic *test-db-path* nil)
(def ^:dynamic *test-db* nil)

(defn temp-db-path []
  (let [tmp-dir (System/getProperty "java.io.tmpdir")
        db-name (str "dfdb-crud-test-" (System/currentTimeMillis) "-" (rand-int 10000))]
    (str tmp-dir File/separator db-name)))

(defn with-rocksdb-database [f]
  (let [path (temp-db-path)]
    (binding [*test-db-path* path
              *test-db* (db/create-db {:storage-config {:type :rocksdb :path path}})]
      (try
        (f)
        (finally
          (try (storage/close (:storage *test-db*)) (catch Exception _))
          (try (rocksdb/destroy-rocksdb-storage path) (catch Exception _)))))))

(use-fixtures :each with-rocksdb-database)

;; =============================================================================
;; Tests copied from basic_crud_test.clj but using RocksDB backend
;; =============================================================================

(deftest test-create-single-entity
  (testing "Create a single entity with map notation"
    (let [result (tx/transact! *test-db*
                               [{:user/name "Alice"
                                 :user/email "alice@example.com"
                                 :user/age 30}])]
      (is (some? (:tx-id result)))
      (is (some? (:tx-time result)))
      (is (seq (:deltas result)))

      ;; Get entity ID from first delta
      (let [eid (-> result :deltas first :entity)]
        (is (some? eid))

        ;; Retrieve and verify entity
        (let [entity (db/entity *test-db* eid)]
          (is (= eid (:db/id entity)))
          (is (= "Alice" (:user/name entity)))
          (is (= "alice@example.com" (:user/email entity)))
          (is (= 30 (:user/age entity))))))))

(deftest test-create-multiple-entities
  (testing "Create multiple entities in one transaction"
    (let [result (tx/transact! *test-db*
                               [{:user/name "Alice" :user/email "alice@example.com"}
                                {:user/name "Bob" :user/email "bob@example.com"}
                                {:user/name "Charlie" :user/email "charlie@example.com"}])]
      (is (= 3 (count (distinct (map :entity (:deltas result))))))

      ;; Verify each entity was created
      (let [alice (db/entity-by *test-db* :user/email "alice@example.com")
            bob (db/entity-by *test-db* :user/email "bob@example.com")
            charlie (db/entity-by *test-db* :user/email "charlie@example.com")]
        (is (= "Alice" (:user/name alice)))
        (is (= "Bob" (:user/name bob)))
        (is (= "Charlie" (:user/name charlie)))))))

(deftest test-update-entity
  (testing "Update existing entity attributes"
    ;; Create entity
    (let [result1 (tx/transact! *test-db*
                                [{:user/name "Alice"
                                  :user/email "alice@example.com"
                                  :user/age 30}])
          eid (-> result1 :deltas first :entity)]

      ;; Update using entity ID
      (tx/transact! *test-db*
                    [[:db/add eid :user/age 31]
                     [:db/add eid :user/city "New York"]])

      ;; Verify updates
      (let [entity (db/entity *test-db* eid)]
        (is (= "Alice" (:user/name entity)))
        (is (= 31 (:user/age entity)))
        (is (= "New York" (:user/city entity)))))))

(deftest test-delete-attribute
  (testing "Retract attribute from entity"
    ;; Create entity
    (let [result (tx/transact! *test-db*
                               [{:user/name "Alice"
                                 :user/email "alice@example.com"
                                 :user/age 30
                                 :user/city "Boston"}])
          eid (-> result :deltas first :entity)]

      ;; Delete age attribute
      (tx/transact! *test-db*
                    [[:db/retract eid :user/age 30]])

      ;; Verify attribute is gone but others remain
      (let [entity (db/entity *test-db* eid)]
        (is (= "Alice" (:user/name entity)))
        (is (nil? (:user/age entity)))
        (is (= "Boston" (:user/city entity)))))))

(deftest test-lookup-ref
  (testing "Use lookup refs to reference entities"
    ;; Create two entities, one referencing the other
    (tx/transact! *test-db*
                  [{:user/name "Alice"
                    :user/email "alice@example.com"}])

    (tx/transact! *test-db*
                  [{:user/name "Bob"
                    :user/email "bob@example.com"
                    :user/manager [:user/email "alice@example.com"]}])

    ;; Verify reference was resolved
    (let [bob (db/entity-by *test-db* :user/email "bob@example.com")
          alice (db/entity-by *test-db* :user/email "alice@example.com")]
      (is (= (:db/id alice) (:user/manager bob))))))

(deftest test-tempids
  (testing "Temporary IDs in transaction"
    (let [result (tx/transact! *test-db*
                               [{:db/id -1
                                 :user/name "Alice"
                                 :user/email "alice@example.com"}
                                {:db/id -2
                                 :user/name "Bob"
                                 :user/email "bob@example.com"
                                 :user/manager -1}])]  ; Reference to tempid -1

      ;; Both entities should be created
      (is (= 2 (count (distinct (map :entity (:deltas result))))))

      ;; Verify manager reference was resolved
      (let [bob (db/entity-by *test-db* :user/email "bob@example.com")
            alice (db/entity-by *test-db* :user/email "alice@example.com")]
        (is (= (:db/id alice) (:user/manager bob)))))))

(deftest test-batch-operations-rocksdb
  (testing "Batch of 1000 operations with RocksDB"
    (let [users (for [i (range 1000)]
                  {:user/name (str "User-" i)
                   :user/email (str "user" i "@example.com")})]
      (tx/transact! *test-db* users))

    ;; Verify random samples
    (is (= "User-0" (:user/name (db/entity-by *test-db* :user/email "user0@example.com"))))
    (is (= "User-500" (:user/name (db/entity-by *test-db* :user/email "user500@example.com"))))
    (is (= "User-999" (:user/name (db/entity-by *test-db* :user/email "user999@example.com"))))))
