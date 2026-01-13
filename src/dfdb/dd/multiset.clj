(ns dfdb.dd.multiset
  "Multiset implementation for differential dataflow.
  A multiset is a collection where elements can appear with multiplicity (count).")

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
   (Multiset. (update (.values ms) value (fnil + 0) multiplicity))))

(defn remove-elem
  "Remove an element from multiset with given multiplicity (default 1)."
  ([ms value]
   (remove-elem ms value 1))
  ([ms value multiplicity]
   (let [new-values (update (.values ms) value (fnil - 0) multiplicity)
         ;; Remove entries with count <= 0
         cleaned (into {} (filter (fn [[_k v]] (pos? v)) new-values))]
     (Multiset. cleaned))))

(defn get-count
  "Get multiplicity of value in multiset."
  [ms value]
  (get (.values ms) value 0))

(defn merge-multisets
  "Merge two multisets by adding multiplicities."
  [ms1 ms2]
  (Multiset.
   (merge-with + (.values ms1) (.values ms2))))

(defn empty-multiset
  "Create an empty multiset."
  []
  (Multiset. {}))

(defn multiset?
  "Check if value is a multiset."
  [x]
  (instance? Multiset x))
