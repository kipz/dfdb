(ns dfdb.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [dfdb.storage :as storage]))

(deftest memory-storage-basic-ops
  (testing "Basic storage operations"
    (let [s (storage/create-memory-storage)]
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
      (is (nil? (storage/get-value s [:test 1]))))))

(deftest memory-storage-batch-write
  (testing "Batch write operations"
    (let [s (storage/create-memory-storage)
          ops [[:put [:a 1] {:val 1}]
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
      (is (= {:val 4} (storage/get-value s [:a 4]))))))

(deftest memory-storage-key-ordering
  (testing "Keys are sorted correctly"
    (let [s (storage/create-memory-storage)]
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
        (is (= [[:z 3] {:val "z3"}] (nth results 3)))))))

(deftest memory-storage-snapshot
  (testing "Snapshot functionality"
    (let [s (storage/create-memory-storage)]
      ;; Add some data
      (storage/put s [:key 1] {:val "original"})
      (storage/put s [:key 2] {:val "data"})

      ;; Create snapshot
      (let [snapshot-id (storage/snapshot s)]
        (is (string? snapshot-id))
        (is (not (empty? snapshot-id)))

        ;; Modify data after snapshot
        (storage/put s [:key 1] {:val "modified"})
        (storage/put s [:key 3] {:val "new"})

        ;; Verify current state
        (is (= {:val "modified"} (storage/get-value s [:key 1])))
        (is (= {:val "new"} (storage/get-value s [:key 3])))

        ;; Restore from snapshot
        (let [restored (storage/restore-snapshot s snapshot-id)]
          ;; Restored storage should have old data
          (is (= {:val "original"} (storage/get-value restored [:key 1])))
          (is (= {:val "data"} (storage/get-value restored [:key 2])))
          (is (nil? (storage/get-value restored [:key 3]))))))))

(deftest memory-storage-close
  (testing "Close operation"
    (let [s (storage/create-memory-storage)]
      (storage/put s [:test 1] {:val 1})
      ;; Close should not throw
      (is (nil? (storage/close s))))))

(deftest memory-storage-compact
  (testing "Compact operation"
    (let [s (storage/create-memory-storage)]
      (storage/put s [:test 1] {:val 1})
      ;; Compact should not throw (no-op for memory storage)
      (is (nil? (storage/compact s))))))

(deftest memory-storage-scan-stream
  (testing "Streaming scan"
    (let [s (storage/create-memory-storage)]
      (storage/put s [:a 1] {:val 1})
      (storage/put s [:a 2] {:val 2})
      (storage/put s [:a 3] {:val 3})

      ;; Streaming scan should return lazy seq
      (let [stream (storage/scan-stream s [:a] [:b] {})]
        (is (seq? stream))
        (is (= 3 (count stream)))
        (is (= [[:a 1] {:val 1}] (first stream)))))))
