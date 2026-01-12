(ns dfdb.storage
  "Storage abstraction for dfdb indexes.")

(defprotocol Storage
  "Abstraction over persistent storage for indexes.
  Keys are vectors representing index coordinates.
  Values are arbitrary EDN data."
  (put [this key value]
    "Store a value at key. Returns updated storage.")
  (get-value [this key]
    "Retrieve value at key. Returns nil if not found.")
  (scan [this start-key end-key]
    "Scan range [start-key, end-key). Returns seq of [key value] pairs in sorted order.")
  (delete [this key]
    "Delete value at key. Returns updated storage.")
  (batch-write [this ops]
    "Apply multiple operations atomically.
    ops is seq of [:put key value] or [:delete key]"))

(defn compare-values
  "Compare two values of potentially different types.
  Order: nil < numbers < strings < keywords < other"
  [v1 v2]
  (cond
    (= v1 v2) 0
    (nil? v1) -1
    (nil? v2) 1
    (and (number? v1) (number? v2)) (compare v1 v2)
    (number? v1) -1
    (number? v2) 1
    (and (string? v1) (string? v2)) (compare v1 v2)
    (string? v1) -1
    (string? v2) 1
    (and (keyword? v1) (keyword? v2)) (compare v1 v2)
    (keyword? v1) -1
    (keyword? v2) 1
    (and (inst? v1) (inst? v2)) (compare (.getTime ^java.util.Date v1)
                                         (.getTime ^java.util.Date v2))
    (inst? v1) -1
    (inst? v2) 1
    :else (compare (str v1) (str v2))))

(defn compare-keys
  "Compare two index keys lexicographically.
  Keys are vectors of potentially heterogeneous values."
  [k1 k2]
  (if (= k1 k2)
    0
    (let [len1 (count k1)
          len2 (count k2)
          min-len (min len1 len2)]
      (loop [i 0]
        (if (>= i min-len)
          (compare len1 len2)  ; shorter vector comes first
          (let [cmp (compare-values (nth k1 i) (nth k2 i))]
            (if (zero? cmp)
              (recur (inc i))
              cmp)))))))

(deftype MemoryStorage [data-atom]
  Storage
  (put [this key value]
    (swap! data-atom assoc key value)
    this)

  (get-value [this key]
    (get @data-atom key))

  (scan [this start-key end-key]
    (->> @data-atom
         (filter (fn [[k _]]
                   (and (>= (compare-keys k start-key) 0)
                        (< (compare-keys k end-key) 0))))
         (sort-by first compare-keys)))

  (delete [this key]
    (swap! data-atom dissoc key)
    this)

  (batch-write [this ops]
    (swap! data-atom
           (fn [data]
             (reduce (fn [d op]
                       (case (first op)
                         :put (assoc d (second op) (nth op 2))
                         :delete (dissoc d (second op))))
                     data
                     ops)))
    this))

(defn create-memory-storage
  "Create a new in-memory storage backend."
  []
  (MemoryStorage. (atom (sorted-map-by compare-keys))))
