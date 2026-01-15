(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.query :as query])

(println "Testing with DETERMINISTIC data (no randomness)...")

;; Create two databases
(def sub-db (dfdb/create-db {:storage-backend :memory}))
(def naive-db (dfdb/create-db {:storage-backend :memory}))

;; Use EXACTLY the same data for both
(def initial-data
  [[:db/add 1 :user/name "Alice"]
   [:db/add 2 :user/name "Bob"]
   [:db/add 3 :user/name "Charlie"]
   [:db/add 4 :user/name "Dave"]
   [:db/add 5 :user/name "Eve"]
   [:db/add 1 :friend 2]
   [:db/add 2 :friend 1]
   [:db/add 2 :friend 3]
   [:db/add 3 :friend 2]
   [:db/add 3 :friend 4]
   [:db/add 4 :friend 3]
   [:db/add 4 :friend 5]
   [:db/add 5 :friend 4]])

(def updates
  [[[:db/add 1 :friend 3]
    [:db/add 3 :friend 1]]
   [[:db/add 2 :friend 5]
    [:db/add 5 :friend 2]]
   [[:db/add 4 :friend 1]
    [:db/add 1 :friend 4]]])

(println "Loading identical initial data into both DBs...")
(dfdb/transact! sub-db initial-data)
(dfdb/transact! naive-db initial-data)

;; Subscribe to sub-db
(def subscription-results (atom []))
(def subscription (sub/subscribe sub-db {:query '[:find ?fof
                                                  :where [?user :friend ?friend]
                                                  [?friend :friend ?fof]]
                                         :callback (fn [diff]
                                                     (swap! subscription-results conj diff))
                                         :mode :incremental}))

(println "Initial subscription result count:" (count (:additions (first @subscription-results))))

;; Apply EXACTLY the same updates to both DBs
(println "\nApplying 3 deterministic updates to both DBs...")
(doseq [[idx update] (map-indexed vector updates)]
  (println "Update" idx ":" update)
  (dfdb/transact! sub-db update)
  (dfdb/transact! naive-db update))

;; Compute subscription final state
(def sub-final (reduce
                (fn [state diff]
                  (-> state
                      (clojure.set/union (:additions diff))
                      (clojure.set/difference (:retractions diff))))
                #{}
                @subscription-results))

;; Query naive DB
(def naive-result (query/query naive-db '[:find ?fof
                                          :where [?user :friend ?friend]
                                          [?friend :friend ?fof]]))

(println "\n=== RESULTS ===")
(println "Subscription count:" (count sub-final))
(println "Naive count:" (count naive-result))

(println "\nSubscription results (sorted):")
(doseq [r (sort sub-final)]
  (println "  " r))

(println "\nNaive results (sorted):")
(doseq [r (sort naive-result)]
  (println "  " r))

(println "\nMatch?" (= sub-final naive-result))

(when-not (= sub-final naive-result)
  (println "\nDifferences:")
  (let [sub-only (clojure.set/difference sub-final naive-result)
        naive-only (clojure.set/difference naive-result sub-final)]
    (println "  Subscription-only (" (count sub-only) "):" sub-only)
    (println "  Naive-only (" (count naive-only) "):" naive-only)))

(shutdown-agents)
