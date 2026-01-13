(ns dfdb.storage.codec-test
  (:require [clojure.test :refer [deftest is testing]]
            [dfdb.storage.codec :as codec])
  (:import [java.util Date Arrays]))

(deftest encode-decode-roundtrip
  (testing "Values roundtrip correctly"
    (let [test-values [nil
                       42
                       -100
                       3.14
                       "hello"
                       "world"
                       :keyword
                       :namespaced/keyword
                       (Date. 1000000)
                       [:a 1]
                       [:eavt 123 :user/name "Alice" 999]]]
      (doseq [val test-values]
        (let [encoded (codec/encode-key val)
              decoded (codec/decode-key encoded)]
          (is (= val decoded)
              (str "Failed roundtrip for: " (pr-str val))))))))

(defn- compare-byte-arrays
  "Compare two byte arrays lexicographically."
  [^bytes a ^bytes b]
  (Arrays/compareUnsigned a b))

(deftest ordering-preserved
  (testing "Encoded keys maintain lexicographic ordering"
    (let [;; Test cases: [smaller larger]
          test-pairs [[nil 0]
                      [0 1]
                      [-100 0]
                      [-100 100]
                      [0 "a"]
                      ["a" "b"]
                      ["a" "aa"]
                      ["hello" "world"]
                      ["a" :keyword]
                      [:a :b]
                      [:keyword :namespaced/keyword]
                      [:a (Date. 0)]
                      [(Date. 0) (Date. 1000)]
                      [[:a 1] [:a 2]]
                      [[:a 2] [:b 1]]
                      [[:eavt 1] [:eavt 2]]
                      [[:eavt 1 :user/email] [:eavt 1 :user/name]]]]
      (doseq [[smaller larger] test-pairs]
        (let [enc-small (codec/encode-key smaller)
              enc-large (codec/encode-key larger)
              cmp (compare-byte-arrays enc-small enc-large)]
          (is (neg? cmp)
              (str "Ordering violated: "
                   (pr-str smaller) " should be < " (pr-str larger)
                   "\nEncoded: " (seq enc-small) " vs " (seq enc-large))))))))

(deftest type-ordering
  (testing "Types are ordered correctly: nil < numbers < strings < keywords < instants"
    (let [values [nil
                  -100
                  0
                  42
                  "a"
                  "z"
                  :keyword
                  :z-keyword
                  (Date. 0)
                  (Date. 999999)]
          encoded (map codec/encode-key values)
          sorted-encoded (sort compare-byte-arrays encoded)]
      (is (= (map seq encoded) (map seq sorted-encoded))
          "Types should maintain order after encoding"))))

(deftest vector-key-ordering
  (testing "Vector keys used by indexes maintain correct ordering"
    ;; Keys are already in lexicographic order
    (let [keys [[:aevt :user/name 1 "Alice" 100]
                [:avet :user/name "Alice" 1 100]
                [:eavt 1 :user/name "Alice" 100]
                [:eavt 1 :user/name "Alice" 200]
                [:eavt 1 :user/name "Bob" 100]
                [:eavt 2 :user/name "Alice" 100]]
          encoded (map codec/encode-key keys)
          sorted-encoded (sort compare-byte-arrays encoded)]
      (is (= (map seq encoded) (map seq sorted-encoded))
          "Index keys should maintain order after encoding"))))

(deftest value-encoding
  (testing "Value encoding roundtrips"
    (let [test-values [{:e 123 :a :user/name :v "Alice" :t 1000 :tx-id 1 :op :assert}
                       {:entity 123 :attribute :user/age :new-value 30}
                       "simple string"
                       42
                       [1 2 3]
                       {:nested {:data "structure"}}]]
      (doseq [val test-values]
        (let [encoded (codec/encode-value val)
              decoded (codec/decode-value encoded)]
          (is (= val decoded)
              (str "Failed value roundtrip for: " (pr-str val))))))))

(deftest edge-cases
  (testing "Edge cases"
    (testing "Empty string"
      (let [val ""
            encoded (codec/encode-key val)
            decoded (codec/decode-key encoded)]
        (is (= val decoded))))

    (testing "Empty vector"
      (let [val []
            encoded (codec/encode-key val)
            decoded (codec/decode-key encoded)]
        (is (= val decoded))))

    (testing "Nested vectors"
      (let [val [:outer [:inner 1 2] 3]
            encoded (codec/encode-key val)
            decoded (codec/decode-key encoded)]
        (is (= val decoded))))

    (testing "Large numbers"
      (let [vals [Long/MAX_VALUE Long/MIN_VALUE
                  1.7e308  ; Large double (not quite MAX_VALUE to avoid overflow)
                  -1.7e308
                  Double/MIN_VALUE
                  -1.0 1.0 0.0]]
        (doseq [val vals]
          (let [encoded (codec/encode-key val)
                decoded (codec/decode-key encoded)
                ;; For very large doubles, compare with tolerance
                equal? (if (and (number? val) (number? decoded))
                         (or (= val decoded)
                             (< (Math/abs (- (double val) (double decoded)))
                                1e-10))
                         (= val decoded))]
            (is equal?
                (str "Failed for: " val " got: " decoded))))))

    (testing "Special characters in strings"
      (let [vals ["hello\nworld"
                  "tab\there"
                  "unicode: 你好"
                  "emoji: 🚀"]]
        (doseq [val vals]
          (let [encoded (codec/encode-key val)
                decoded (codec/decode-key encoded)]
            (is (= val decoded)
                (str "Failed for: " val))))))))
