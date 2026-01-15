(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.query :as query])  ;; Using query directly like benchmark
(require '[dfdb.performance-test :as perf])

(println "Replicating benchmark flow with 200 users...")

(def num-users 200)
(def num-updates 20)

;; Create DB
(def db (dfdb/create-db {:storage-backend :memory}))

;; Generate and load initial data
(def initial-data-fn #(perf/generate-social-network num-users 8))
(def initial-data (initial-data-fn))
(dfdb/transact! db initial-data)
(println "Loaded" (count initial-data) "transactions")

;; Subscribe
(def subscription-results (atom []))
(def subscription (sub/subscribe db {:query '[:find ?fof
                                              :where [?user :friend ?friend]
                                              [?friend :friend ?fof]]
                                     :callback (fn [diff]
                                                 (swap! subscription-results conj diff))
                                     :mode :incremental}))

(println "Subscription initial count:" (count (:additions (first @subscription-results))))

;; Generate updates
(def updates (doall (repeatedly num-updates #(perf/generate-friendship-update num-users))))

;; Apply updates to subscription DB
(doseq [update updates]
  (dfdb/transact! db update))

(println "Applied" num-updates "updates to subscription DB")

;; Create fresh naive DB
(def naive-db (dfdb/create-db {:storage-backend :memory}))
(dfdb/transact! naive-db initial-data)

;; Apply same updates to naive DB
(doseq [update updates]
  (dfdb/transact! naive-db update))

(println "Applied" num-updates "updates to naive DB")

;; Query naive DB using query/query directly (like benchmark does)
(def query-map '[:find ?fof
                 :where [?user :friend ?friend]
                 [?friend :friend ?fof]])
(def final-naive-results (query/query naive-db query-map))

(println "\nNaive query result:")
(println "  Count:" (count final-naive-results))
(println "  Type:" (type final-naive-results))
(println "  First 3 results:")
(doseq [r (take 3 final-naive-results)]
  (println "    " r "(type:" (type r) ")"))

;; Compute subscription final state
(def subscription-final-state
  (reduce
   (fn [state diff]
     (-> state
         (clojure.set/union (:additions diff))
         (clojure.set/difference (:retractions diff))))
   #{}
   @subscription-results))

(println "\nSubscription final state:")
(println "  Count:" (count subscription-final-state))
(println "  Type:" (type subscription-final-state))
(println "  First 3 results:")
(doseq [r (take 3 subscription-final-state)]
  (println "    " r "(type:" (type r) ")"))

(def naive-set (set final-naive-results))
(println "\nDo they match?" (= subscription-final-state naive-set))

(when-not (= subscription-final-state naive-set)
  (println "\nMismatch details:")
  (println "  Subscription only count:" (count (clojure.set/difference subscription-final-state naive-set)))
  (println "  Naive only count:" (count (clojure.set/difference naive-set subscription-final-state))))

(shutdown-agents)
