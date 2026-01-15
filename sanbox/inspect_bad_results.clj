(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.query :as query])
(require '[dfdb.performance-test :as perf])

(println "Inspecting bad results...")

(def num-users 200)
(def num-updates 20)

(def db (dfdb/create-db {:storage-backend :memory}))
(def initial-data (perf/generate-social-network num-users 8))
(dfdb/transact! db initial-data)

(def subscription-results (atom []))
(def subscription (sub/subscribe db {:query '[:find ?fof
                                              :where [?user :friend ?friend]
                                              [?friend :friend ?fof]]
                                     :callback (fn [diff]
                                                 (swap! subscription-results conj diff))
                                     :mode :incremental}))

(def updates (doall (repeatedly num-updates #(perf/generate-friendship-update num-users))))
(doseq [update updates] (dfdb/transact! db update))

(def naive-db (dfdb/create-db {:storage-backend :memory}))
(dfdb/transact! naive-db initial-data)
(doseq [update updates] (dfdb/transact! naive-db update))

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

(def naive-set (set final-naive-results))
(def naive-only (clojure.set/difference naive-set subscription-final-state))

(println "Naive-only count:" (count naive-only))
(println "\nFirst 10 naive-only results:")
(doseq [r (take 10 naive-only)]
  (println "\nValue:" r)
  (println "Type:" (type r))
  (println "Class:" (class r))
  (when (set? r)
    (println "IT'S A SET!")
    (println "Set count:" (count r))
    (println "Set contents:" (vec r)))
  (when (vector? r)
    (println "It's a vector (correct)")
    (println "First elem:" (first r))))

(shutdown-agents)
