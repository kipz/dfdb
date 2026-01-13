(ns dfdb.dd.timestamp
  "Multi-dimensional timestamps for differential dataflow.
  Timestamps form a partial order (lattice).")

(deftype Timestamp [dimensions]
  ;; dimensions is a map: {:time/system t1, :time/shipped t2, ...}

  Comparable
  (compareTo [this other]
    ;; Partial order: this <= other iff all common dimensions satisfy di <= dj
    ;; Returns: -1 (this < other), 0 (equal), 1 (this > other), nil (incomparable)
    (let [this-dims (.dimensions this)
          other-dims (.dimensions (cast Timestamp other))
          common-dims (set (keys this-dims))]
      (cond
        ;; Equal if same dimensions and values
        (= this-dims other-dims) 0

        ;; Check if this <= other for all common dimensions
        (every? (fn [dim]
                  (let [t1 (get this-dims dim)
                        t2 (get other-dims dim)]
                    (and t1 t2 (<= t1 t2))))
                common-dims)
        -1  ; this <= other

        ;; Check if other <= this for all common dimensions
        (every? (fn [dim]
                  (let [t1 (get this-dims dim)
                        t2 (get other-dims dim)]
                    (and t1 t2 (<= t2 t1))))
                common-dims)
        1  ; this >= other

        ;; Otherwise incomparable
        :else
        (throw (IllegalArgumentException. "Incomparable timestamps")))))

  Object
  (toString [_this]
    (str "#<Timestamp " dimensions ">")))

(defn timestamp
  "Create a timestamp from dimension map."
  [dimensions]
  (Timestamp. dimensions))

(defn timestamp<=
  "Check if t1 <= t2 in the partial order.
  Returns true if all common dimensions satisfy di <= dj."
  [t1 t2]
  (let [dims1 (.dimensions t1)
        dims2 (.dimensions t2)]
    (every? (fn [[dim val1]]
              (if-let [val2 (get dims2 dim)]
                (<= val1 val2)
                true))  ; Dimension in t1 but not t2 - allow
            dims1)))

(defn advance-timestamp
  "Advance timestamp to next logical time for given dimension."
  [ts dimension]
  (Timestamp.
   (update (.dimensions ts) dimension inc)))

(defn merge-timestamps
  "Compute least upper bound (LUB) of two timestamps.
  For each dimension, take the maximum value."
  [t1 t2]
  (Timestamp.
   (merge-with max (.dimensions t1) (.dimensions t2))))

(defn timestamp-from-tx
  "Create timestamp from transaction metadata."
  [tx-id tx-time time-dimensions]
  (Timestamp.
   (assoc time-dimensions
          :time/system (.getTime tx-time)
          :tx-id tx-id)))
