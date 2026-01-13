(ns dfdb.storage
  "Storage abstraction for dfdb indexes.")

(set! *warn-on-reflection* true)

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

(defprotocol StreamingStorage
  "Optional protocol for storage backends that support streaming scans."
  (scan-stream [this start-key end-key opts]
    "Scan range lazily as a stream. Returns lazy seq of [key value] pairs."))

(defprotocol StorageLifecycle
  "Optional protocol for storage backends that support lifecycle management."
  (close [this]
    "Close the storage backend and release resources.")
  (snapshot [this]
    "Create a snapshot of the current state. Returns snapshot handle.")
  (restore-snapshot [this snapshot-id]
    "Restore storage to a previous snapshot state. Returns new storage instance.")
  (compact [this]
    "Compact the storage to reclaim space and optimize performance."))

(defn try-require-namespace
  "Attempt to require a namespace and resolve a function.
  Returns the function or throws an informative error.

  Args:
    ns-sym - Namespace symbol to require
    fn-sym - Fully qualified function symbol to resolve
    context-info - Map of contextual information for error messages

  Throws:
    ExceptionInfo with actionable error message if namespace not found"
  [ns-sym fn-sym context-info]
  (try
    (require ns-sym)
    (or (resolve fn-sym)
        (throw (ex-info (str "Function not found after requiring namespace: " fn-sym)
                        (assoc context-info
                               :namespace ns-sym
                               :function fn-sym))))
    (catch java.io.FileNotFoundException e
      (throw (ex-info (str "Storage backend not available: " ns-sym)
                      (assoc context-info
                             :namespace ns-sym
                             :note "Add the required dependency to deps.edn if needed"
                             :original-error (.getMessage ^Exception e))
                      e)))
    (catch Exception e
      (throw (ex-info (str "Failed to load storage backend: " ns-sym)
                      (assoc context-info :namespace ns-sym)
                      e)))))

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

(deftype MemoryStorage [data-atom snapshots-atom]
  Storage
  (put [this key value]
    (swap! data-atom assoc key value)
    this)

  (get-value [_this key]
    (get @data-atom key))

  (scan [_this start-key end-key]
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
    this)

  StreamingStorage
  (scan-stream [_this start-key end-key _opts]
    ;; For MemoryStorage, scan-stream is the same as scan (already lazy)
    (->> @data-atom
         (filter (fn [[k _]]
                   (and (>= (compare-keys k start-key) 0)
                        (< (compare-keys k end-key) 0))))
         (sort-by first compare-keys)))

  StorageLifecycle
  (close [_this]
    nil)

  (snapshot [_this]
    (let [snapshot-id (str (java.util.UUID/randomUUID))
          snapshot-data @data-atom]
      (swap! snapshots-atom assoc snapshot-id snapshot-data)
      snapshot-id))

  (restore-snapshot [_this snapshot-id]
    (if-let [snapshot-data (get @snapshots-atom snapshot-id)]
      (MemoryStorage. (atom snapshot-data) snapshots-atom)
      (throw (ex-info "Snapshot not found" {:snapshot-id snapshot-id}))))

  (compact [_this]
    nil))

(defn create-memory-storage
  "Create a new in-memory storage backend."
  []
  (MemoryStorage. (atom (sorted-map-by compare-keys)) (atom {})))
