(ns dfdb.dd.multiset
  "Multiset implementation for differential dataflow.
  A multiset is a collection where elements can appear with multiplicity (count).")

(set! *warn-on-reflection* true)

(deftype Multiset [values]
  ;; values is a map: {value -> count}

  clojure.lang.Counted
  (count [_this]
    (reduce + 0 (vals values)))

  clojure.lang.Seqable
  (seq [_this]
    ;; Return sequence of [value count] pairs
    (seq values))

  Object
  (toString [_this]
    (str "#<Multiset " values ">")))

(defn multiset
  "Create a multiset from a collection or map."
  ([]
   (Multiset. {}))
  ([coll]
   (if (map? coll)
     ;; Already a {value -> count} map
     (Multiset. coll)
     ;; Collection - count frequencies
     (Multiset. (frequencies coll)))))

(defn add
  "Add an element to multiset with given multiplicity (default 1)."
  ([ms value]
   (add ms value 1))
  ([ms value multiplicity]
   (Multiset. (update ^clojure.lang.IPersistentMap (.values ^Multiset ms)
                      value
                      (fnil + 0)
                      ^long multiplicity))))

(defn remove-elem
  "Remove an element from multiset with given multiplicity (default 1)."
  ([ms value]
   (remove-elem ms value 1))
  ([ms value multiplicity]
   (let [new-values (update ^clojure.lang.IPersistentMap (.values ^Multiset ms)
                            value
                            (fnil - 0)
                            ^long multiplicity)
         ;; Remove entries with count <= 0
         cleaned (into {} (filter (fn [[_k v]] (pos? ^long v)) new-values))]
     (Multiset. cleaned))))

(defn get-count
  "Get multiplicity of value in multiset."
  [ms value]
  (get ^clojure.lang.IPersistentMap (.values ^Multiset ms) value 0))

(defn merge-multisets
  "Merge two multisets by adding multiplicities.
  OPTIMIZED: Uses transient map for better performance."
  [ms1 ms2]
  (let [values1 ^clojure.lang.IPersistentMap (.values ^Multiset ms1)
        values2 ^clojure.lang.IPersistentMap (.values ^Multiset ms2)]
    (Multiset.
     (persistent!
      (reduce-kv (fn [acc k v]
                   (assoc! acc k (+ (get acc k 0) ^long v)))
                 (transient values1)
                 values2)))))

(defn empty-multiset
  "Create an empty multiset."
  []
  (Multiset. {}))

(defn multiset?
  "Check if value is a multiset."
  [x]
  (instance? Multiset x))
