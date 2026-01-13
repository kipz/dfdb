(ns dfdb.subscription
  "Subscription system for incremental query updates."
  (:require [clojure.set :as set]
            [dfdb.query :as query]
            [clojure.core.async :as async]
            [dfdb.dd.full-pipeline :as dd]
            [dfdb.transaction :as tx]
            [dfdb.db :as db]))

(defrecord Subscription
           [id                    ; Unique subscription ID
            query-form            ; Original query
            mode                  ; :batch or :incremental
            callback              ; Callback function (fn [diff] ...)
            delivery              ; :callback, :core-async, :manifold
            channel               ; core.async channel (if delivery = :core-async)
            watch-dimensions      ; Which time dimensions trigger updates
            see-retroactive?      ; Whether to see historical updates
            current-results       ; Atom holding current result set
            active?])             ; Atom - is subscription active?

(def ^:private subscriptions
  "Registry of active subscriptions."
  (atom {}))

(def ^:private subscription-counter
  "Counter for generating subscription IDs."
  (atom 0))

(defn create-subscription
  "Create a new subscription."
  [db config]
  (let [sub-id (swap! subscription-counter inc)
        {:keys [query mode callback delivery watch-dimensions see-retroactive?]
         :or {mode :incremental
              delivery :callback
              watch-dimensions [:time/system]
              see-retroactive? false}} config

        ;; Compile to DD graph
        ;; Pass db for recursive patterns that need access to storage
        ;; Returns nil for recursive queries (fallback to re-execution)
        dd-graph (dd/build-pipeline query db)

        ;; Initial query execution
        initial-results (query/query db query)

        ;; Create channel if needed
        channel (when (= delivery :core-async)
                  (async/chan 100))

        subscription (map->Subscription
                      {:id sub-id
                       :query-form query
                       :mode mode
                       :callback callback
                       :delivery delivery
                       :channel channel
                       :watch-dimensions watch-dimensions
                       :see-retroactive? see-retroactive?
                       :current-results (atom initial-results)
                       :active? (atom true)
                       :dd-graph dd-graph})]  ; Store DD graph if available

    ;; Register subscription
    (swap! subscriptions assoc sub-id subscription)

    ;; Deliver initial state
    (let [initial-diff {:additions initial-results
                        :retractions #{}}]
      (case delivery
        :callback (when callback (callback initial-diff))
        :core-async (async/>!! channel initial-diff)
        :manifold nil))

    subscription))

(defn notify-subscription
  "Notify a subscription of changes using TRUE differential dataflow."
  [_db subscription deltas]
  (when @(:active? subscription)
    (let [dd-graph (:dd-graph subscription)]

      (when-not dd-graph
        (throw (ex-info "Subscription has no DD graph - query not supported"
                        {:query (:query-form subscription)})))

      ;; TRUE DIFFERENTIAL - NO FALLBACK
      (let [old-results-raw ((:get-results dd-graph))
            old-results (if (set? old-results-raw) old-results-raw (set old-results-raw))

            _ ((:process-deltas dd-graph) deltas)

            new-results-raw ((:get-results dd-graph))
            new-results (if (set? new-results-raw) new-results-raw (set new-results-raw))

            additions (clojure.set/difference new-results old-results)
            retractions (clojure.set/difference old-results new-results)
            diff {:additions additions :retractions retractions}]

        ;; Deliver diff if non-empty
        (when (or (seq additions) (seq retractions))
          (case (:delivery subscription)
            :callback (when-let [cb (:callback subscription)]
                        (cb diff))
            :core-async (when-let [ch (:channel subscription)]
                          (async/>!! ch diff))
            :manifold nil))))))

(defn notify-all-subscriptions
  "Notify all active subscriptions of transaction deltas."
  [db deltas]
  (doseq [[_id subscription] @subscriptions]
    (when @(:active? subscription)
      (try
        ;; Check if any delta dimensions match watch-dimensions
        (let [delta-dims (set (mapcat (fn [delta]
                                        (when (map? delta)  ; Ensure delta is a map
                                          (filter #(and (keyword? %)
                                                        (namespace %)
                                                        (.startsWith (namespace %) "time"))
                                                  (keys delta))))
                                      deltas))
              watch-dims (set (:watch-dimensions subscription))
              should-notify? (seq (set/intersection delta-dims watch-dims))]
          (when should-notify?
            (try
              (notify-subscription db subscription deltas)
              (catch Exception e
                (println "Warning: Failed to notify subscription" (:id subscription) "-" (.getMessage e))
                ;; Continue with other subscriptions
                nil))))
        (catch Exception e
          (println "Warning: Failed to process subscription" (:id subscription) "-" (.getMessage e)))))))

(defrecord SubscriptionNotifier []
  tx/TransactionListener
  (notify-transaction [_this db deltas]
    (notify-all-subscriptions db deltas)))

(def ^:private global-subscription-notifier
  "Global notifier instance for managing subscriptions."
  (atom nil))

(defn- ensure-notifier-registered!
  "Ensure subscription notifier is registered with database.
  Mutates the database's listeners atom if needed. Returns the database."
  [db]
  (let [notifier (or @global-subscription-notifier
                     (let [n (->SubscriptionNotifier)]
                       (reset! global-subscription-notifier n)
                       n))]
    (when-not (some #(= notifier %) @(:listeners db))
      (db/add-listener db notifier))
    db))

(defn subscribe
  "Subscribe to a query for incremental updates.
  Automatically registers subscription notifier with database on first call.
  Returns subscription handle."
  [db config]
  (ensure-notifier-registered! db)
  (create-subscription db config))

(defn unsubscribe
  "Unsubscribe and clean up resources."
  [subscription]
  (when subscription
    (reset! (:active? subscription) false)
    (swap! subscriptions dissoc (:id subscription))
    (when (:channel subscription)
      (async/close! (:channel subscription)))))
