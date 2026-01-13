(ns dfdb.storage.rocksdb-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dfdb.storage :as storage]
            [dfdb.storage.rocksdb :as rocksdb])
  (:import [java.io File]))

(def ^:dynamic *test-db-path* nil)

(defn temp-db-path
  "Create a temporary path for test database."
  []
  (let [tmp-dir (System/getProperty "java.io.tmpdir")
        db-name (str "dfdb-rocksdb-test-" (System/currentTimeMillis) "-" (rand-int 10000))]
    (str tmp-dir File/separator db-name)))

(defn with-test-storage
  "Fixture to create and cleanup RocksDB storage for each test."
  [f]
  (let [path (temp-db-path)]
    (binding [*test-db-path* path]
      (try
        (f)
        (finally
          ;; Clean up test database
          (try
            (rocksdb/destroy-rocksdb-storage path)
            (catch Exception _e
              nil)))))))

(use-fixtures :each with-test-storage)

(deftest rocksdb-storage-basic-ops
  (testing "Basic storage operations"
    (let [s (rocksdb/create-rocksdb-storage {:path *test-db-path*})]
      (try
        ;; Put and get
        (storage/put s [:test 1] {:value "hello"})
        (is (= {:value "hello"} (storage/get-value s [:test 1])))

        ;; Get non-existent key
        (is (nil? (storage/get-value s [:test 999])))

        ;; Scan range
        (storage/put s [:test 2] {:value "world"})
        (storage/put s [:test 3] {:value "!"})
        (let [results (storage/scan s [:test 1] [:test 3])]
          (is (= 2 (count results)))
          (is (= [[:test 1] {:value "hello"}] (first results)))
          (is (= [[:test 2] {:value "world"}] (second results))))

        ;; Delete
        (storage/delete s [:test 1])
        (is (nil? (storage/get-value s [:test 1])))
        (finally
          (storage/close s))))))

(deftest rocksdb-storage-batch-write
  (testing "Batch write operations"
    (let [s (rocksdb/create-rocksdb-storage {:path *test-db-path*})]
      (try
        (let [ops [[:put [:a 1] {:val 1}]
                   [:put [:a 2] {:val 2}]
                   [:put [:a 3] {:val 3}]]]
          (storage/batch-write s ops)
          (is (= {:val 1} (storage/get-value s [:a 1])))
          (is (= {:val 2} (storage/get-value s [:a 2])))
          (is (= {:val 3} (storage/get-value s [:a 3])))

          ;; Batch with delete
          (storage/batch-write s [[:delete [:a 2]]
                                  [:put [:a 4] {:val 4}]])
          (is (nil? (storage/get-value s [:a 2])))
          (is (= {:val 4} (storage/get-value s [:a 4]))))
        (finally
          (storage/close s))))))

(deftest rocksdb-storage-key-ordering
  (testing "Keys are sorted correctly"
    (let [s (rocksdb/create-rocksdb-storage {:path *test-db-path*})]
      (try
        ;; Insert in random order
        (storage/put s [:z 3] {:val "z3"})
        (storage/put s [:a 1] {:val "a1"})
        (storage/put s [:m 2] {:val "m2"})
        (storage/put s [:a 2] {:val "a2"})

        ;; Scan all - should be in lexicographic order
        (let [results (storage/scan s [:a] [:zzzz])]
          (is (= 4 (count results)))
          (is (= [[:a 1] {:val "a1"}] (first results)))
          (is (= [[:a 2] {:val "a2"}] (second results)))
          (is (= [[:m 2] {:val "m2"}] (nth results 2)))
          (is (= [[:z 3] {:val "z3"}] (nth results 3))))
        (finally
          (storage/close s))))))

(deftest rocksdb-storage-snapshot
  (testing "Snapshot functionality"
    (let [s (rocksdb/create-rocksdb-storage {:path *test-db-path*})]
      (try
        ;; Add some data
        (storage/put s [:key 1] {:val "original"})
        (storage/put s [:key 2] {:val "data"})

        ;; Create snapshot
        (let [snapshot-handle (storage/snapshot s)]
          (is (some? snapshot-handle))

          ;; Modify data after snapshot
          (storage/put s [:key 1] {:val "modified"})
          (storage/put s [:key 3] {:val "new"})

          ;; Verify current state (snapshot handle is opaque in this implementation)
          (is (= {:val "modified"} (storage/get-value s [:key 1])))
          (is (= {:val "new"} (storage/get-value s [:key 3]))))
        (finally
          (storage/close s))))))

(deftest rocksdb-storage-close
  (testing "Close operation"
    (let [s (rocksdb/create-rocksdb-storage {:path *test-db-path*})]
      (storage/put s [:test 1] {:val 1})
      ;; Close should not throw
      (is (nil? (storage/close s)))

      ;; Operations after close should throw
      (is (thrown? Exception (storage/put s [:test 2] {:val 2}))))))

(deftest rocksdb-storage-compact
  (testing "Compact operation"
    (let [s (rocksdb/create-rocksdb-storage {:path *test-db-path*})]
      (try
        (storage/put s [:test 1] {:val 1})
        ;; Compact should not throw
        (is (nil? (storage/compact s)))
        ;; Data should still be accessible
        (is (= {:val 1} (storage/get-value s [:test 1])))
        (finally
          (storage/close s))))))

(deftest rocksdb-storage-scan-stream
  (testing "Streaming scan"
    (let [s (rocksdb/create-rocksdb-storage {:path *test-db-path*})]
      (try
        (storage/put s [:a 1] {:val 1})
        (storage/put s [:a 2] {:val 2})
        (storage/put s [:a 3] {:val 3})

        ;; Streaming scan should return lazy seq
        (let [stream (storage/scan-stream s [:a] [:b] {})]
          (is (seq? stream))
          (is (= 3 (count stream)))
          (is (= [[:a 1] {:val 1}] (first stream))))
        (finally
          (storage/close s))))))

(deftest rocksdb-persistence
  (testing "Data persists across open/close"
    (let [path *test-db-path*]
      ;; Create DB, write data, close
      (let [s1 (rocksdb/create-rocksdb-storage {:path path})]
        (storage/put s1 [:persistent 1] {:msg "I should persist"})
        (storage/put s1 [:persistent 2] {:msg "Me too"})
        (storage/close s1))

      ;; Reopen and verify data is still there
      (let [s2 (rocksdb/create-rocksdb-storage {:path path})]
        (try
          (is (= {:msg "I should persist"} (storage/get-value s2 [:persistent 1])))
          (is (= {:msg "Me too"} (storage/get-value s2 [:persistent 2])))
          (finally
            (storage/close s2)))))))

(deftest rocksdb-index-keys
  (testing "Index key patterns work correctly"
    (let [s (rocksdb/create-rocksdb-storage {:path *test-db-path*})]
      (try
        ;; Simulate index keys like dfdb uses
        (storage/put s [:eavt 123 :user/name "Alice" 1] {:e 123 :a :user/name :v "Alice"})
        (storage/put s [:eavt 123 :user/email "alice@ex.com" 1] {:e 123 :a :user/email :v "alice@ex.com"})
        (storage/put s [:eavt 456 :user/name "Bob" 2] {:e 456 :a :user/name :v "Bob"})

        ;; Scan specific entity
        (let [results (storage/scan s [:eavt 123] [:eavt 124])]
          (is (= 2 (count results)))
          (is (every? #(= 123 (second (first %))) results)))

        ;; Scan all eavt
        (let [results (storage/scan s [:eavt] [:eavt "z"])]
          (is (= 3 (count results))))
        (finally
          (storage/close s))))))

(deftest rocksdb-compression-options
  (testing "Different compression options"
    (doseq [compression [:none :snappy :lz4 :zstd]]
      (let [path (temp-db-path)
            s (rocksdb/create-rocksdb-storage {:path path :compression compression})]
        (try
          (storage/put s [:test 1] {:val "compressed?"})
          (is (= {:val "compressed?"} (storage/get-value s [:test 1])))
          (finally
            (storage/close s)
            (rocksdb/destroy-rocksdb-storage path)))))))
