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
            transform-fn          ; Optional transformation function (fn [diff] ...)
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
        {:keys [query mode callback delivery watch-dimensions see-retroactive? transform-fn]
         :or {mode :incremental
              delivery :callback
              watch-dimensions [:time/system]
              see-retroactive? false}} config

        ;; Compile to DD graph
        ;; Pass db for recursive patterns that need access to storage
        ;; Returns nil for recursive queries (fallback to re-execution)
        dd-graph (dd/build-pipeline query db)

        ;; Initialize DD graph with existing database state
        _ (when dd-graph
            (dd/initialize-pipeline-state dd-graph db query))

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
                       :transform-fn transform-fn
                       :current-results (atom initial-results)
                       :active? (atom true)
                       :dd-graph dd-graph})]  ; Store DD graph if available

    ;; Register subscription
    (swap! subscriptions assoc sub-id subscription)

    ;; Deliver initial state
    (let [raw-initial-diff {:additions initial-results
                            :retractions #{}}
          initial-diff (if transform-fn
                         (transform-fn raw-initial-diff)
                         raw-initial-diff)]
      (case delivery
        :callback (when callback (callback initial-diff))
        :core-async (async/>!! channel initial-diff)
        :manifold nil))

    subscription))

(defn notify-subscription
  "Notify a subscription of changes using TRUE differential dataflow."
  [_db subscription deltas force-notify?]
  (when @(:active? subscription)
    (let [dd-graph (:dd-graph subscription)]

      (when-not dd-graph
        (throw (ex-info "Subscription has no DD graph - query not supported"
                        {:query (:query-form subscription)})))

      ;; TRUE DIFFERENTIAL - NO FALLBACK
      (try
        (let [old-results-raw ((:get-results dd-graph))
              old-results (if (set? old-results-raw) old-results-raw (set old-results-raw))

              _ (try
                  ((:process-deltas dd-graph) deltas)
                  (catch Exception e
                    (throw (ex-info "Error in process-deltas"
                                    {:subscription-id (:id subscription)
                                     :query (:query-form subscription)
                                     :error (.getMessage e)}
                                    e))))

              new-results-raw ((:get-results dd-graph))
              new-results (if (set? new-results-raw) new-results-raw (set new-results-raw))

              additions (clojure.set/difference new-results old-results)
              retractions (clojure.set/difference old-results new-results)
              raw-diff {:additions additions :retractions retractions}

              ;; Apply transformation if provided
              diff (if-let [transform (:transform-fn subscription)]
                     (transform raw-diff)
                     raw-diff)]

          ;; Deliver diff if non-empty OR if force-notify? is true (watched dimension changed)
          (when (or (seq (:additions diff)) (seq (:retractions diff)) force-notify?)
            (case (:delivery subscription)
              :callback (when-let [cb (:callback subscription)]
                          (cb diff))
              :core-async (when-let [ch (:channel subscription)]
                            (async/>!! ch diff))
              :manifold nil)))
        (catch Exception e
          (throw (ex-info "Error in notify-subscription"
                          {:subscription-id (:id subscription)
                           :query (:query-form subscription)
                           :error (.getMessage e)}
                          e)))))))

(defn notify-all-subscriptions
  "Notify all active subscriptions of transaction deltas."
  [db deltas]
  (doseq [[_id subscription] @subscriptions]
    (when @(:active? subscription)
      (try
        ;; Check if delta dimensions match watch-dimensions
        ;; ANY watch-dimension changed triggers notification (OR logic)
        ;; EXCLUDE :time/system as it's always present on every transaction
        (let [delta-dims (set (for [delta deltas
                                    :when (map? delta)
                                    dim-key (keys delta)
                                    :when (and (keyword? dim-key)
                                               (namespace dim-key)
                                               (.startsWith (namespace dim-key) "time")
                                               (not= dim-key :time/system))]  ; Exclude :time/system
                                dim-key))
              watch-dims (set (remove #{:time/system} (:watch-dimensions subscription)))  ; Exclude :time/system from watch list too
              ;; Notify if ANY watch-dimension is present in delta
              ;; If watch-dims is empty (only watching :time/system), always notify
              should-notify? (if (empty? watch-dims)
                               true
                               (seq (set/intersection watch-dims delta-dims)))
              ;; Force notification only if explicit watch-dimensions are specified
              force-notify? (and (not (empty? watch-dims)) should-notify?)]
          (when should-notify?
            (notify-subscription db subscription deltas force-notify?)))
        (catch Exception e
          (println "Warning: Failed to notify subscription" (:id subscription) "-" (.getMessage e)))))))

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
