(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.performance-test :as perf])

(println "Testing with 50 users to see result format...")

;; Create databases
(def sub-db (dfdb/create-db {:storage-backend :memory}))
(def naive-db (dfdb/create-db {:storage-backend :memory}))

;; Generate data
(def initial-data (perf/generate-social-network 50 5))

;; Load into subscription DB and subscribe
(dfdb/transact! sub-db initial-data)
(def subscription-results (atom []))
(def subscription (sub/subscribe sub-db {:query '[:find ?fof
                                                  :where [?user :friend ?friend]
                                                  [?friend :friend ?fof]]
                                         :callback (fn [diff]
                                                     (swap! subscription-results conj diff))
                                         :mode :incremental}))

;; Load into naive DB
(dfdb/transact! naive-db initial-data)

;; Compute subscription final state
(def sub-final (reduce
                (fn [state diff]
                  (-> state
                      (clojure.set/union (:additions diff))
                      (clojure.set/difference (:retractions diff))))
                #{}
                @subscription-results))

;; Query naive DB
(def naive-result (dfdb/query naive-db '[:find ?fof
                                         :where [?user :friend ?friend]
                                         [?friend :friend ?fof]]))

(println "\nSubscription result count:" (count sub-final))
(println "Naive query result count:" (count naive-result))

(println "\nFirst 3 subscription results (no sort):")
(doseq [r (take 3 sub-final)]
  (println "  Value:" r)
  (println "  Type:" (type r))
  (println "  Class:" (class r))
  (when (vector? r)
    (println "  First elem:" (first r) "Type:" (type (first r))))
  (println))

(println "\nFirst 3 naive results (no sort):")
(doseq [r (take 3 naive-result)]
  (println "  Value:" r)
  (println "  Type:" (type r))
  (println "  Class:" (class r))
  (when (coll? r)
    (println "  Is set?:" (set? r))
    (println "  Is vector?:" (vector? r))
    (when (set? r)
      (println "  Set contents:" (vec r))
      (println "  Set count:" (count r))))
  (println))

(shutdown-agents)
