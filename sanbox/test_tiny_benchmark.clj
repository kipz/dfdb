(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.performance-test :as perf])

(println "Creating databases...")
(def sub-db (dfdb/create-db {:storage-backend :memory}))
(def naive-db (dfdb/create-db {:storage-backend :memory}))

(println "Generating data for 10 users...")
(def initial-data (perf/generate-social-network 10 3))
(println "Generated" (count initial-data) "transactions")

(println "\nLoading into subscription DB...")
(dfdb/transact! sub-db initial-data)

(def subscription-results (atom []))
(def subscription (sub/subscribe sub-db {:query '[:find ?fof
                                                  :where [?user :friend ?friend]
                                                  [?friend :friend ?fof]]
                                         :callback (fn [diff]
                                                     (swap! subscription-results conj diff))
                                         :mode :incremental}))

(println "Initial subscription result count:" (count (:additions (first @subscription-results))))

(println "\nApplying 5 updates to both DBs...")
(def updates (repeatedly 5 #(perf/generate-friendship-update 10)))

(doseq [[idx update] (map-indexed vector updates)]
  (println "Update" idx ":" update)
  (dfdb/transact! sub-db update)
  (dfdb/transact! naive-db (if (zero? idx)
                             (concat initial-data update)
                             update)))

(println "\nComputing final subscription state...")
(def sub-final (reduce
                (fn [state diff]
                  (-> state
                      (clojure.set/union (:additions diff))
                      (clojure.set/difference (:retractions diff))))
                #{}
                @subscription-results))

(println "Subscription final count:" (count sub-final))
(println "Subscription results:" (sort-by first sub-final))

(println "\nQuerying naive DB...")
(def naive-result (dfdb/query naive-db '[:find ?fof
                                         :where [?user :friend ?friend]
                                         [?friend :friend ?fof]]))
(println "Naive query count:" (count naive-result))
(println "Naive results:" (sort-by first naive-result))

(println "\nDo they match?" (= sub-final naive-result))
(when-not (= sub-final naive-result)
  (println "Subscription only:" (clojure.set/difference sub-final naive-result))
  (println "Naive only:" (clojure.set/difference naive-result sub-final)))

(shutdown-agents)
