(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.query :as query])

(println "Tracing subscription delta processing...")

(def db (dfdb/create-db {:storage-backend :memory}))

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

(dfdb/transact! db initial-data)

;; Subscribe with detailed logging
(def subscription-results (atom []))
(def subscription (sub/subscribe db {:query '[:find ?fof
                                              :where [?user :friend ?friend]
                                              [?friend :friend ?fof]]
                                     :callback (fn [diff]
                                                 (println "\n[CALLBACK] Received diff:")
                                                 (println "  Additions:" (count (:additions diff)))
                                                 (println "  Retractions:" (count (:retractions diff)))
                                                 (when (seq (:additions diff))
                                                   (println "  First 5 additions:" (take 5 (:additions diff))))
                                                 (when (seq (:retractions diff))
                                                   (println "  First 5 retractions:" (take 5 (:retractions diff))))
                                                 (swap! subscription-results conj diff))
                                     :mode :incremental}))

(println "\nInitial subscription delivered" (count (:additions (first @subscription-results))) "results")
(println "Initial results:" (sort (:additions (first @subscription-results))))

;; Apply update 1
(println "\n" (apply str (repeat 60 "=")))
(println "UPDATE 1: Adding 1<->3 friendship")
(println (apply str (repeat 60 "=")))
(dfdb/transact! db [[:db/add 1 :friend 3]
                    [:db/add 3 :friend 1]])

;; Apply update 2
(println "\n" (apply str (repeat 60 "=")))
(println "UPDATE 2: Adding 2<->5 friendship")
(println (apply str (repeat 60 "=")))
(dfdb/transact! db [[:db/add 2 :friend 5]
                    [:db/add 5 :friend 2]])

;; Apply update 3
(println "\n" (apply str (repeat 60 "=")))
(println "UPDATE 3: Adding 4<->1 friendship")
(println (apply str (repeat 60 "=")))
(dfdb/transact! db [[:db/add 4 :friend 1]
                    [:db/add 1 :friend 4]])

;; Compute final subscription state
(def sub-final (reduce
                (fn [state diff]
                  (-> state
                      (clojure.set/union (:additions diff))
                      (clojure.set/difference (:retractions diff))))
                #{}
                @subscription-results))

(println "\n" (apply str (repeat 60 "=")))
(println "FINAL SUBSCRIPTION STATE")
(println (apply str (repeat 60 "=")))
(println "Count:" (count sub-final))
(println "Results:" (sort sub-final))

;; Query naive DB
(def naive-result (query/query db '[:find ?fof
                                    :where [?user :friend ?friend]
                                    [?friend :friend ?fof]]))

(println "\n" (apply str (repeat 60 "=")))
(println "NAIVE QUERY RESULT")
(println (apply str (repeat 60 "=")))
(println "Count:" (count naive-result))
(println "Results:" (sort naive-result))

(println "\n=== COMPARISON ===")
(println "Match?" (= sub-final naive-result))
(when-not (= sub-final naive-result)
  (println "Subscription missing:" (clojure.set/difference naive-result sub-final)))

(shutdown-agents)
