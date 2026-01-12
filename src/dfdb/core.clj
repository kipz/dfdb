(ns dfdb.core
  "Public API for dfdb."
  (:require [dfdb.db :as db]
            [dfdb.transaction :as tx]
            [dfdb.query :as q]))

;; Re-export public functions
(def create-db db/create-db)
(def transact! tx/transact!)
(def entity db/entity)
(def entity-by db/entity-by)
(def current-time db/current-time)
(def query q/query)

;; Helpers for tests
(defn entity-id-by
  "Get entity ID by unique attribute lookup."
  [db attr value]
  (:db/id (entity-by db attr value)))

(defn contains-delta?
  "Check if transaction result contains a delta matching the given pattern."
  [tx-result pattern]
  (some (fn [delta]
          (every? (fn [[k v]]
                    (= v (get delta k)))
                  pattern))
        (:deltas tx-result)))
