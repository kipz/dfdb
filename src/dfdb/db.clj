(ns dfdb.db
  "Core database operations."
  (:require [dfdb.storage :as storage]
            [dfdb.index :as index]))

(set! *warn-on-reflection* true)

(defrecord Database [storage  ; Storage backend
                     tx-counter  ; Atom for transaction ID counter
                     entity-id-counter  ; Atom for entity ID counter
                     listeners])  ; Atom containing vector of TransactionListener implementations

(defn create-db
  "Create a new empty database.

  Options map (optional):
    :storage - Pre-configured storage backend instance
    :storage-config - Configuration map for creating storage backend
      {:type :memory}  ; default
      {:type :rocksdb :path \"/var/lib/dfdb/data\" :options {...}}
      {:type :postgres :connection-uri \"postgresql://localhost/dfdb\"}
      {:type :foundationdb :cluster-file \"/etc/fdb/fdb.cluster\"}

  Examples:
    (create-db)  ; Uses in-memory storage
    (create-db {:storage my-storage})  ; Use pre-configured storage
    (create-db {:storage-config {:type :memory}})  ; Explicit memory storage"
  ([]
   (create-db {}))
  ([{:keys [storage storage-config] :as _opts}]
   (let [storage-backend (cond
                           storage
                           storage

                           storage-config
                           (case (:type storage-config)
                             :memory
                             (storage/create-memory-storage)

                             :rocksdb
                             (let [create-fn (storage/try-require-namespace
                                              'dfdb.storage.rocksdb
                                              'dfdb.storage.rocksdb/create-rocksdb-storage
                                              {:config storage-config
                                               :backend-type :rocksdb})]
                               (create-fn storage-config))

                             ;; Future backends:
                             ;; :postgres (create-postgres-storage storage-config)
                             ;; :foundationdb (create-foundationdb-storage storage-config)
                             (throw (ex-info "Unknown storage type"
                                             {:type (:type storage-config)
                                              :available-types [:memory :rocksdb]})))

                           :else
                           (storage/create-memory-storage))]
     (map->Database
      {:storage storage-backend
       :tx-counter (atom 0)
       :entity-id-counter (atom 0)
       :listeners (atom [])}))))

(defn next-tx-id
  "Generate next transaction ID."
  [db]
  (swap! (:tx-counter db) inc))

(defn next-entity-id
  "Generate next entity ID."
  [db]
  (swap! (:entity-id-counter db) inc))

(defn current-time
  "Get current system timestamp."
  []
  (java.util.Date.))

(defn entity
  "Get entity by ID as-of now (or specific time/tx-id)."
  ([db eid]
   (entity db eid (current-time)))
  ([db eid as-of-time-or-tx]
   (let [t (if (instance? java.util.Date as-of-time-or-tx)
             (.getTime ^java.util.Date as-of-time-or-tx)
             ##Inf)  ; If tx-id, use infinity for time (get all)
         tx-id (if (integer? as-of-time-or-tx)
                 as-of-time-or-tx
                 ##Inf)  ; If time, use infinity for tx-id (get all up to time)
         attrs (index/entity-at (:storage db) eid t tx-id)]
     (when (seq attrs)
       (assoc attrs :db/id eid)))))

(defn entity-by
  "Get entity by unique attribute lookup as-of now."
  ([db attr value]
   (entity-by db attr value (current-time)))
  ([db attr value as-of-time-or-tx]
   (let [;; For lookup, always use infinity time to find the entity
         ;; Then filter by as-of when getting the entity
         eid (index/lookup-ref (:storage db) [attr value] ##Inf)]
     (when eid
       (entity db eid as-of-time-or-tx)))))

(defn add-listener
  "Add a transaction listener to database.
  The listener must implement the dfdb.transaction/TransactionListener protocol.
  Mutates the database's listeners atom and returns the database.

  Example:
    (add-listener db my-listener)"
  [db listener]
  (swap! (:listeners db) conj listener)
  db)
