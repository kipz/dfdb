(ns dfdb.db
  "Core database operations."
  (:require [dfdb.storage :as storage]
            [dfdb.index :as index]))

(defrecord Database [storage  ; Storage backend
                     tx-counter  ; Atom for transaction ID counter
                     entity-id-counter])  ; Atom for entity ID counter

(defn create-db
  "Create a new empty database."
  []
  (map->Database
   {:storage (storage/create-memory-storage)
    :tx-counter (atom 0)
    :entity-id-counter (atom 0)}))

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
             (.getTime as-of-time-or-tx)
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
  ([db attr value as-of-time]
   (let [eid (index/lookup-ref (:storage db) [attr value] (.getTime as-of-time))]
     (when eid
       (entity db eid as-of-time)))))
