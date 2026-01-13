(ns dfdb.storage.codec
  "Order-preserving encoding/decoding for storage keys and values.

  Keys must maintain lexicographic ordering after encoding to bytes.
  This is critical for range scans to work correctly.

  Encoding strategy:
  - Type prefix byte ensures correct ordering between types
  - nil (0x00)
  - Numbers (0x01) - IEEE 754 with sign bit flip
  - Strings (0x02) - UTF-8 with 0x00 terminator
  - Keywords (0x03) - namespace + name with 0x00 terminators
  - Instants (0x04) - milliseconds as long
  - Vectors (0x05) - recursive encoding with 0x00 terminator
  - Other (0xFF) - pr-str fallback"
  (:require [clojure.edn :as edn])
  (:import [java.nio ByteBuffer]
           [java.util Date]))

(set! *warn-on-reflection* true)

;; Type tags for ordering
(def ^:const TYPE-NIL 0x00)
(def ^:const TYPE-NUMBER 0x01)
(def ^:const TYPE-STRING 0x02)
(def ^:const TYPE-KEYWORD 0x03)
(def ^:const TYPE-INSTANT 0x04)
(def ^:const TYPE-VECTOR 0x05)
(def ^:const TYPE-OTHER 0xFF)

(def ^:const TERMINATOR 0x00)

(defn- write-bytes
  "Write bytes to ByteBuffer."
  [^ByteBuffer bb ^bytes bs]
  (.put bb bs))

(defn- write-byte
  "Write single byte to ByteBuffer."
  [^ByteBuffer bb ^long b]
  (.put bb (unchecked-byte b)))

(defn- write-long
  "Write long to ByteBuffer in big-endian order."
  [^ByteBuffer bb ^long n]
  (.putLong bb n))

(defn- write-double
  "Write double to ByteBuffer with sign bit flip for ordering."
  [^ByteBuffer bb ^double d]
  (let [bits (Double/doubleToLongBits d)
        ;; Flip sign bit for correct ordering: negative < 0 < positive
        flipped (if (neg? d)
                  (bit-not bits)
                  (bit-xor bits Long/MIN_VALUE))]
    (.putLong bb flipped)))

(defn- encode-value-to-buffer
  "Encode a single value to ByteBuffer. Returns ByteBuffer for chaining."
  [^ByteBuffer bb value]
  (cond
    (nil? value)
    (write-byte bb TYPE-NIL)

    (integer? value)
    (do
      (write-byte bb TYPE-NUMBER)
      (write-double bb (double value)))

    (float? value)
    (do
      (write-byte bb TYPE-NUMBER)
      (write-double bb (double value)))

    (string? value)
    (do
      (write-byte bb TYPE-STRING)
      (write-bytes bb (.getBytes ^String value "UTF-8"))
      (write-byte bb TERMINATOR))

    (keyword? value)
    (do
      (write-byte bb TYPE-KEYWORD)
      (when-let [ns (namespace value)]
        (write-bytes bb (.getBytes ^String ns "UTF-8")))
      (write-byte bb TERMINATOR)
      (write-bytes bb (.getBytes ^String (name value) "UTF-8"))
      (write-byte bb TERMINATOR))

    (instance? Date value)
    (do
      (write-byte bb TYPE-INSTANT)
      (write-long bb (.getTime ^Date value)))

    (vector? value)
    (do
      (write-byte bb TYPE-VECTOR)
      (doseq [v value]
        (encode-value-to-buffer bb v))
      (write-byte bb TERMINATOR))

    :else
    ;; Fallback for unknown types
    (do
      (write-byte bb TYPE-OTHER)
      (let [s (pr-str value)]
        (write-bytes bb (.getBytes s "UTF-8"))
        (write-byte bb TERMINATOR))))
  bb)

(defn- estimate-size
  "Estimate byte size needed for value."
  [value]
  (cond
    (nil? value) 1
    (number? value) 9  ; type + double
    (string? value) (+ 2 (* 3 (count value)))  ; worst case UTF-8
    (keyword? value) (+ 3 (* 3 (+ (count (namespace value))
                                  (count (name value)))))
    (instance? Date value) 9  ; type + long
    (vector? value) (+ 2 (reduce + (map estimate-size value)))
    :else (+ 2 (* 3 (count (pr-str value))))))

(defn encode-key
  "Encode key vector to byte array preserving lexicographic ordering."
  ^bytes [key-vec]
  (let [size (estimate-size key-vec)
        bb (ByteBuffer/allocate size)]
    (encode-value-to-buffer bb key-vec)
    (let [pos (.position bb)
          result (byte-array pos)]
      (.rewind bb)
      (.get bb result 0 pos)
      result)))

(defn encode-value
  "Encode datom value to byte array using pr-str.
  Values don't need ordering, just serialization."
  ^bytes [value]
  (let [s (pr-str value)]
    (.getBytes s "UTF-8")))

(defn- read-byte
  "Read single byte from ByteBuffer."
  [^ByteBuffer bb]
  (when (.hasRemaining bb)
    (.get bb)))

(defn- read-long
  "Read long from ByteBuffer."
  [^ByteBuffer bb]
  (.getLong bb))

(defn- read-double
  "Read double from ByteBuffer with sign bit unflip."
  [^ByteBuffer bb]
  (let [flipped (.getLong bb)
        ;; Unflip sign bit
        bits (if (neg? flipped)
               (bit-xor flipped Long/MIN_VALUE)
               (bit-not flipped))]
    (Double/longBitsToDouble bits)))

(defn- read-until-terminator
  "Read bytes until terminator is found."
  [^ByteBuffer bb]
  (let [start (.position bb)]
    (loop [bytes []]
      (if (.hasRemaining bb)
        (let [b (.get bb)]
          (if (= b (byte TERMINATOR))
            (byte-array bytes)
            (recur (conj bytes b))))
        (byte-array bytes)))))

(defn- decode-value-from-buffer
  "Decode a single value from ByteBuffer."
  [^ByteBuffer bb]
  (when (.hasRemaining bb)
    (let [type-tag (read-byte bb)]
      (case type-tag
        0x00  ; TYPE-NIL
        nil

        0x01  ; TYPE-NUMBER
        (let [d (read-double bb)]
          ;; Return as long if integer value and within long range
          (if (and (== d (Math/floor d))
                   (>= d Long/MIN_VALUE)
                   (<= d Long/MAX_VALUE))
            (long d)
            d))

        0x02  ; TYPE-STRING
        (String. ^bytes (read-until-terminator bb) "UTF-8")

        0x03  ; TYPE-KEYWORD
        (let [ns-bytes (read-until-terminator bb)
              name-bytes (read-until-terminator bb)
              ns-str (when (pos? (alength ns-bytes))
                       (String. ns-bytes "UTF-8"))
              name-str (String. name-bytes "UTF-8")]
          (if ns-str
            (keyword ns-str name-str)
            (keyword name-str)))

        0x04  ; TYPE-INSTANT
        (Date. (read-long bb))

        0x05  ; TYPE-VECTOR
        (loop [elements []]
          (if (.hasRemaining bb)
            (let [next-byte (.get bb (.position bb))]
              (if (= next-byte (byte TERMINATOR))
                (do
                  (.get bb)  ; consume terminator
                  elements)
                (recur (conj elements (decode-value-from-buffer bb)))))
            elements))

        0xFF  ; TYPE-OTHER
        (let [s (String. ^bytes (read-until-terminator bb) "UTF-8")]
          (edn/read-string s))

        ;; Unknown type
        (throw (ex-info "Unknown type tag during decode"
                        {:type-tag type-tag
                         :position (.position bb)}))))))

(defn decode-key
  "Decode byte array back to key vector."
  [^bytes key-bytes]
  (let [bb (ByteBuffer/wrap key-bytes)]
    (decode-value-from-buffer bb)))

(defn decode-value
  "Decode byte array back to datom value."
  [^bytes value-bytes]
  (let [s (String. value-bytes "UTF-8")]
    (clojure.edn/read-string s)))
