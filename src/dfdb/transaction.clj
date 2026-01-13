(ns dfdb.transaction
  "Transaction processing and delta generation."
  (:require [dfdb.storage :as storage]
            [dfdb.index :as index]
            [dfdb.db :as db]
            [dfdb.dimensions :as dims]))

(defprotocol TransactionListener
  "Protocol for receiving notifications after successful transactions.
  Listeners are registered on the Database and called after each transaction commits."
  (notify-transaction [this db deltas]
    "Called after a successful transaction with the database and deltas.

    Args:
      db - The database instance
      deltas - Sequence of delta maps with :op, :e, :a, :v, :tx, :dimensions"))

(defn tempid?
  "Check if value is a temporary ID (negative number)."
  [v]
  (and (integer? v) (neg? v)))

(defn lookup-ref?
  "Check if value is a lookup ref [attr value]."
  [v]
  (and (vector? v) (= 2 (count v)) (keyword? (first v))))

(defn resolve-entity-id
  "Resolve entity ID from tempid, lookup ref, or actual ID.
  Returns [resolved-id tempid-map] where tempid-map tracks tempid->id mappings."
  [db eid tempid-map tx-time]
  (cond
    ;; Temporary ID - generate or reuse from tempid-map
    (tempid? eid)
    (if-let [resolved (get tempid-map eid)]
      [resolved tempid-map]
      (let [new-id (db/next-entity-id db)]
        [new-id (assoc tempid-map eid new-id)]))

    ;; Lookup ref - resolve to entity ID
    (lookup-ref? eid)
    (if-let [resolved (index/lookup-ref (:storage db) eid tx-time)]
      [resolved tempid-map]
      (throw (ex-info "Lookup ref not found" {:lookup-ref eid})))

    ;; Already an entity ID (integer or string)
    (or (integer? eid) (string? eid))
    (do
      ;; Update entity counter if explicit integer ID is used
      (when (integer? eid)
        (let [current @(:entity-id-counter db)]
          (when (>= eid current)
            (reset! (:entity-id-counter db) (inc eid)))))
      [eid tempid-map])

    :else
    (throw (ex-info "Invalid entity ID - must be integer, string, tempid, or lookup ref"
                    {:eid eid :type (type eid)}))))

(defn expand-map-notation
  "Expand map notation {:key val ...} to list of [op e a v] tuples.
  Returns [tuples tempid-map tempid-counter]."
  [db m tempid-map tempid-counter tx-time]
  (let [[eid tempid-counter'] (if (:db/id m)
                                [(:db/id m) tempid-counter]
                                [(- tempid-counter) (inc tempid-counter)])  ; generate unique negative tempid
        [resolved-eid tempid-map'] (resolve-entity-id db eid tempid-map tx-time)
        attrs (dissoc m :db/id)]
    [(for [[a v] attrs]
       [:db/add resolved-eid a v])
     tempid-map'
     tempid-counter']))

(defn parse-tx-data
  "Parse transaction data into normalized [op e a v] tuples.
  Returns [tuples tempid-map]."
  [db tx-data tempid-map tx-time]
  (loop [data tx-data
         tuples []
         tmap tempid-map
         tempid-counter 1]  ; start at 1, so first auto-tempid is -1
    (if (empty? data)
      [tuples tmap]
      (let [item (first data)]
        (cond
          ;; Map notation
          (map? item)
          (let [[expanded-tuples tmap' counter'] (expand-map-notation db item tmap tempid-counter tx-time)]
            (recur (rest data)
                   (concat tuples expanded-tuples)
                   tmap'
                   counter'))

          ;; List/vector notation [op e a v] or [op e a k v] for :db/assoc
          (or (vector? item) (list? item))
          (let [op (first item)
                e (second item)
                a (nth item 2)
                ;; For :db/assoc, v is [k val] where k and val are separate
                v (if (= :db/assoc op)
                    [(nth item 3) (nth item 4)]  ; [key value]
                    (nth item 3))  ; regular value
                [resolved-e tmap'] (resolve-entity-id db e tmap tx-time)]
            (recur (rest data)
                   (conj tuples [op resolved-e a v])
                   tmap'
                   tempid-counter))

          :else
          (throw (ex-info "Invalid transaction data item" {:item item})))))))

(defn get-current-value
  "Get current value of entity attribute as-of tx-time.
  For cardinality-many (set values), returns the set.
  For cardinality-one, returns single value."
  [db e a tx-time]
  (let [entity-data (index/entity-at (:storage db) e tx-time)]
    (get entity-data a)))

(defn eav-exists?
  "Check if a specific EAV triple currently exists."
  [db e a v tx-time]
  (let [start-key [:eavt e a v]
        end-key [:eavt e a (index/successor-value v)]
        datoms (index/scan-eavt (:storage db) start-key end-key)
        ;; Get latest datom for this specific EAV
        latest (first (sort index/datom-comparator (map second datoms)))]
    (and latest (= :assert (:op latest)))))

(defn resolve-value
  "Resolve value if it's a lookup ref.
  NOTE: Tempids only apply to entity IDs, not attribute values.
  Negative numbers in values are legitimate data."
  [db v tempid-map tx-time]
  (cond
    (lookup-ref? v)
    (if-let [resolved (index/lookup-ref (:storage db) v tx-time)]
      resolved
      (throw (ex-info "Lookup ref not found in value" {:lookup-ref v})))

    :else
    v))

(defn generate-delta
  "Generate delta for a single operation.
  Returns delta map or nil if no change.

  Supports:
  - :db/add - Add value (or merge into set/map if value is set/map)
  - :db/retract - Remove value
  - :db/assoc - Merge into map (v should be [key value])
  - :db/conj - Append to vector"
  [db op e a v tempid-map tx-time tx-id _tx-meta time-dimensions]
  (let [current-value (get-current-value db e a tx-time)

        ;; Handle different operations
        [new-value operation] (case op
                                :db/add
                                (let [v-resolved (resolve-value db v tempid-map tx-time)]
                                  (cond
                                    ;; New value is a set - merge with existing set
                                    (set? v-resolved)
                                    [(if (set? current-value)
                                       (clojure.set/union current-value v-resolved)
                                       v-resolved)
                                     :assert]

                                    ;; New value is a map - merge with existing map
                                    (map? v-resolved)
                                    [(if (map? current-value)
                                       (merge current-value v-resolved)
                                       v-resolved)
                                     :assert]

                                    ;; New value is a vector - replace (don't append whole vector)
                                    (vector? v-resolved)
                                    [v-resolved :assert]

                                    ;; Current value is a set - add scalar to it
                                    (set? current-value)
                                    [(conj current-value v-resolved) :assert]

                                    ;; Current value is a vector - append scalar to it
                                    (vector? current-value)
                                    [(conj current-value v-resolved) :assert]

                                    ;; Regular add
                                    :else
                                    [v-resolved :assert]))

                                :db/retract
                                (let [v-resolved (resolve-value db v tempid-map tx-time)]
                                  (cond
                                    ;; Retracting from a set
                                    (set? current-value)
                                    [(disj current-value v-resolved) :retract]

                                    ;; Regular retract
                                    :else
                                    [nil :retract]))

                                :db/assoc
                                ;; v is [key value] pair for map assoc
                                (let [[k val] v
                                      val-resolved (resolve-value db val tempid-map tx-time)
                                      updated-map (assoc (or current-value {}) k val-resolved)]
                                  [updated-map :assert])

                                :db/conj
                                ;; Append to vector
                                (let [v-resolved (resolve-value db v tempid-map tx-time)
                                      updated-vec (conj (or current-value []) v-resolved)]
                                  [updated-vec :assert])

                                ;; Default: normalize to :assert/:retract
                                (case op
                                  :db/add [(resolve-value db v tempid-map tx-time) :assert]
                                  :db/retract [(resolve-value db v tempid-map tx-time) :retract]))]

    ;; Generate delta if:
    ;; 1. Value changed, OR
    ;; 2. This is a temporal transaction (has time-dimensions) - new temporal version
    (when (or (not= current-value new-value)
              (and (seq time-dimensions) (not= :retract operation)))
      (merge
       {:entity e
        :attribute a
        :old-value current-value
        :new-value new-value
        :index-value new-value
        :operation operation
        :tx {:tx/id tx-id
             :tx/time (java.util.Date. tx-time)}}
       time-dimensions))))

(defn apply-delta
  "Apply delta to indexes. Returns seq of storage operations."
  [delta]
  (let [{:keys [entity attribute index-value _old-value operation time/system tx]} delta
        ;; Use index-value if present (for retracts), otherwise fall back to new-value
        value-for-index (or index-value (:new-value delta))
        tx-time (.getTime system)
        tx-id (:tx/id tx)
        ;; Extract all time dimensions from delta
        time-dims (select-keys delta (filter #(and (keyword? %)
                                                   (namespace %)
                                                   (.startsWith (namespace %) "time"))
                                             (keys delta)))
        datom (merge (index/datom entity attribute value-for-index tx-time tx-id operation)
                     time-dims)]
    (index/index-datom datom)))

(defn transact!
  "Transact data into database.
  tx-data-or-map can be:
  - Vector of tx-data (maps or tuples)
  - Map with :tx-data and optional :tx-meta, :time-dimensions

  Returns map with :tx-id, :tx-time, :deltas"
  [db tx-data-or-map]
  (let [tx-id (db/next-tx-id db)
        tx-time (System/currentTimeMillis)

        ;; Parse input format
        tx-data (if (map? tx-data-or-map)
                  (:tx-data tx-data-or-map)
                  tx-data-or-map)
        tx-meta (when (map? tx-data-or-map)
                  (:tx-meta tx-data-or-map))
        user-time-dims (when (map? tx-data-or-map)
                         (:time-dimensions tx-data-or-map))

        ;; Parse tx-data first to get entity IDs
        [tuples tempid-map] (parse-tx-data db tx-data {} tx-time)

        ;; Get first entity ID for constraint validation (if updating single entity)
        first-entity (when (seq tuples) (second (first tuples)))

        ;; Validate and enrich time dimensions with entity context
        time-dimensions (dims/enrich-time-dimensions db (or user-time-dims {}) tx-time first-entity)

        ;; Generate deltas
        deltas (->> tuples
                    (map (fn [[op e a v]]
                           (generate-delta db op e a v tempid-map tx-time tx-id tx-meta time-dimensions)))
                    (filter some?)
                    (doall))

        ;; Merge tx-meta into deltas
        deltas-with-meta (if tx-meta
                           (map (fn [delta]
                                  (update-in delta [:tx] merge tx-meta))
                                deltas)
                           deltas)

        ;; Apply all deltas to indexes
        storage-ops (->> deltas-with-meta
                         (mapcat apply-delta)
                         (filter some?)
                         (doall))]

    ;; Write to storage
    (storage/batch-write (:storage db) storage-ops)

    ;; Notify transaction listeners
    (doseq [listener @(:listeners db)]
      (try
        (notify-transaction listener db deltas-with-meta)
        (catch Exception e
          ;; Log but don't fail transaction if listener fails
          (println "Warning: Transaction listener failed:" (.getMessage e)))))

    ;; Return result
    {:tx-id tx-id
     :tx-time (java.util.Date. tx-time)
     :deltas deltas-with-meta}))
