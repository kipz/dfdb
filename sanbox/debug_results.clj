(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.performance-test :as perf])

(println "Testing with 100 users to see if results differ...")

;; Create databases
(def sub-db (dfdb/create-db {:storage-backend :memory}))
(def naive-db (dfdb/create-db {:storage-backend :memory}))

;; Generate data
(def initial-data (perf/generate-social-network 100 5))
(println "Generated" (count initial-data) "transactions")

;; Load into subscription DB and subscribe
(dfdb/transact! sub-db initial-data)
(def subscription-results (atom []))
(def subscription (sub/subscribe sub-db {:query '[:find ?fof
                                                  :where [?user :friend ?friend]
                                                  [?friend :friend ?fof]]
                                         :callback (fn [diff]
                                                     (swap! subscription-results conj diff))
                                         :mode :incremental}))

(println "Subscription initial result count:" (count (:additions (first @subscription-results))))

;; Load into naive DB
(dfdb/transact! naive-db initial-data)

;; Apply 10 updates to both
(println "\nApplying 10 updates...")
(dotimes [i 10]
  (let [update (perf/generate-friendship-update 100)]
    (dfdb/transact! sub-db update)
    (dfdb/transact! naive-db update)))

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

(println "\nFirst 5 subscription results:")
(doseq [r (take 5 (sort sub-final))]
  (println "  " r "(type:" (type r) ", class:" (class r) ")"))

(println "\nFirst 5 naive results:")
(doseq [r (take 5 (sort naive-result))]
  (println "  " r "(type:" (type r) ", class:" (class r) ")"))

(println "\nDo they match?" (= sub-final naive-result))

(when-not (= sub-final naive-result)
  (println "\nSubscription only (first 5):")
  (doseq [r (take 5 (sort (clojure.set/difference sub-final naive-result)))]
    (println "  " r))

  (println "\nNaive only (first 5):")
  (doseq [r (take 5 (sort (clojure.set/difference naive-result sub-final)))]
    (println "  " r)))

(shutdown-agents)
