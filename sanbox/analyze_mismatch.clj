(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.query :as query])
(require '[dfdb.performance-test :as perf])

(println "Analyzing mismatch with 200 users...")

(def num-users 200)
(def num-updates 20)

;; Create DB
(def db (dfdb/create-db {:storage-backend :memory}))

;; Generate and load initial data
(def initial-data (perf/generate-social-network num-users 8))
(dfdb/transact! db initial-data)

;; Subscribe
(def subscription-results (atom []))
(def subscription (sub/subscribe db {:query '[:find ?fof
                                              :where [?user :friend ?friend]
                                              [?friend :friend ?fof]]
                                     :callback (fn [diff]
                                                 (swap! subscription-results conj diff))
                                     :mode :incremental}))

;; Generate updates
(def updates (doall (repeatedly num-updates #(perf/generate-friendship-update num-users))))

;; Apply updates to subscription DB
(doseq [update updates]
  (dfdb/transact! db update))

;; Create fresh naive DB with same data
(def naive-db (dfdb/create-db {:storage-backend :memory}))
(dfdb/transact! naive-db initial-data)
(doseq [update updates]
  (dfdb/transact! naive-db update))

;; Get results
(def query-map '[:find ?fof
                 :where [?user :friend ?friend]
                 [?friend :friend ?fof]])
(def final-naive-results (query/query naive-db query-map))

(def subscription-final-state
  (reduce
   (fn [state diff]
     (-> state
         (clojure.set/union (:additions diff))
         (clojure.set/difference (:retractions diff))))
   #{}
   @subscription-results))

;; Analyze differences
(def naive-set (set final-naive-results))
(def sub-only (clojure.set/difference subscription-final-state naive-set))
(def naive-only (clojure.set/difference naive-set subscription-final-state))

(println "\nResults:")
(println "  Subscription:" (count subscription-final-state))
(println "  Naive:" (count naive-set))

(println "\nMismatch:")
(println "  Subscription-only:" (count sub-only))
(println "  Naive-only:" (count naive-only))

(println "\nNaive-only results (showing all):")
(doseq [r (sort-by first naive-only)]
  (println "  " r))

(when (seq sub-only)
  (println "\nSubscription-only results:")
  (doseq [r (sort-by first sub-only)]
    (println "  " r)))

;; Check if naive results have duplicates
(println "\nChecking for duplicates in naive results...")
(println "  Naive result count:" (count final-naive-results))
(println "  Naive unique count:" (count naive-set))
(if (= (count final-naive-results) (count naive-set))
  (println "  No duplicates")
  (println "  DUPLICATES FOUND!" (- (count final-naive-results) (count naive-set)) "duplicates"))

(shutdown-agents)
