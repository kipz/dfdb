(ns dfdb.transaction
  "Transaction processing and delta generation."
  (:require [dfdb.storage :as storage]
            [dfdb.index :as index]
            [dfdb.db :as db]
            [dfdb.dimensions :as dims]))

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

          ;; List/vector notation [op e a v]
          (or (vector? item) (list? item))
          (let [[op e a v] item
                [resolved-e tmap'] (resolve-entity-id db e tmap tx-time)]
            (recur (rest data)
                   (conj tuples [op resolved-e a v])
                   tmap'
                   tempid-counter))

          :else
          (throw (ex-info "Invalid transaction data item" {:item item})))))))

(defn get-current-value
  "Get current value of entity attribute as-of tx-time."
  [db e a tx-time]
  (let [entity-data (index/entity-at (:storage db) e tx-time)]
    (get entity-data a)))

(defn resolve-value
  "Resolve value if it's a lookup ref or tempid."
  [db v tempid-map tx-time]
  (cond
    (tempid? v)
    (if-let [resolved (get tempid-map v)]
      resolved
      (throw (ex-info "Unresolved tempid in value" {:tempid v})))

    (lookup-ref? v)
    (if-let [resolved (index/lookup-ref (:storage db) v tx-time)]
      resolved
      (throw (ex-info "Lookup ref not found in value" {:lookup-ref v})))

    :else
    v))

(defn generate-delta
  "Generate delta for a single operation.
  Returns delta map or nil if no change."
  [db op e a v tempid-map tx-time tx-id tx-meta time-dimensions]
  (let [v-resolved (resolve-value db v tempid-map tx-time)
        old-value (get-current-value db e a tx-time)
        operation (case op
                    :db/add :assert
                    :db/retract :retract
                    op)]  ; already normalized

    ;; Only generate delta if there's an actual change
    (when (or (= operation :retract)
              (not= old-value v-resolved))
      (merge
       {:entity e
        :attribute a
        :old-value old-value
        :new-value (if (= operation :retract) nil v-resolved)
        :operation operation
        :tx {:tx/id tx-id
             :tx/time (java.util.Date. tx-time)}}
       time-dimensions))))

(defn apply-delta
  "Apply delta to indexes. Returns seq of storage operations."
  [delta]
  (let [{:keys [entity attribute new-value old-value operation time/system tx]} delta
        tx-time (.getTime system)
        tx-id (:tx/id tx)
        ;; Extract all time dimensions from delta
        time-dims (select-keys delta (filter #(and (keyword? %)
                                                   (namespace %)
                                                   (.startsWith (namespace %) "time"))
                                             (keys delta)))
        datom (merge (index/datom entity attribute new-value tx-time tx-id operation)
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

    ;; Return result
    {:tx-id tx-id
     :tx-time (java.util.Date. tx-time)
     :deltas deltas-with-meta}))
