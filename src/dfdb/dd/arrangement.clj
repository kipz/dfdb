(ns dfdb.dd.arrangement
  "Arrangements are indexed representations of collections for efficient joins.")

(set! *warn-on-reflection* true)

(defprotocol Arrangement
  "Arrangement maintains an indexed view of a collection."
  (insert [this key value timestamp multiplicity]
    "Insert a value with multiplicity at key and timestamp.")
  (lookup [this key]
    "Lookup all values for key. Returns seq of [value timestamp multiplicity].")
  (remove-key [this key]
    "Remove all values for key.")
  (compact [this]
    "Compact arrangement by removing zero-multiplicity entries."))

(deftype HashArrangement [index-atom]
  ;; index-atom: {key -> {value -> {timestamp -> multiplicity}}}

  Arrangement
  (insert [_this key value timestamp multiplicity]
    (swap! index-atom
           (fn [index]
             (update-in index [key value timestamp]
                        (fnil + 0) multiplicity))))

  (lookup [_this key]
    (when-let [values (get @index-atom key)]
      (for [[value ts-map] values
            [timestamp multiplicity] ts-map
            :when (pos? multiplicity)]
        [value timestamp multiplicity])))

  (remove-key [_this key]
    (swap! index-atom dissoc key))

  (compact [_this]
    (swap! index-atom
           (fn [index]
             (into {}
                   (keep (fn [[key values]]
                           (let [compacted (into {}
                                                 (keep (fn [[value ts-map]]
                                                         (let [filtered (into {}
                                                                              (filter (fn [[_ts mult]]
                                                                                        (pos? mult))
                                                                                      ts-map))]
                                                           (when (seq filtered)
                                                             [value filtered])))
                                                       values))]
                             (when (seq compacted)
                               [key compacted])))
                         index)))))

  Object
  (toString [_this]
    (str "#<Arrangement " (count @index-atom) " keys>")))

(defn create-arrangement
  "Create a new arrangement indexed by key-fn."
  []
  (HashArrangement. (atom {})))

(defn arrangement-from-collection
  "Build arrangement from collection, extracting keys with key-fn."
  [coll key-fn timestamp]
  (let [arr (create-arrangement)]
    (doseq [[value multiplicity] (seq coll)]
      (let [key (key-fn value)]
        (insert arr key value timestamp multiplicity)))
    arr))
