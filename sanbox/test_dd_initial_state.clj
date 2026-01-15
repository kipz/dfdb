(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.query :as query])

(println "Testing if DD pipeline knows about initial state...")

;; Scenario 1: Subscribe BEFORE adding any data
(println "\n=== SCENARIO 1: Subscribe to empty DB ===")
(def db1 (dfdb/create-db {:storage-backend :memory}))
(def results1 (atom []))
(def sub1 (sub/subscribe db1 {:query '[:find ?fof
                                       :where [?user :friend ?friend]
                                       [?friend :friend ?fof]]
                              :callback (fn [diff] (swap! results1 conj diff))
                              :mode :incremental}))

(println "Initial result:" (:additions (first @results1)))

(println "\nAdding data AFTER subscription...")
(dfdb/transact! db1 [[:db/add 1 :friend 2]
                     [:db/add 2 :friend 1]
                     [:db/add 2 :friend 3]
                     [:db/add 3 :friend 2]])

(def final1 (reduce (fn [state diff]
                      (-> state
                          (clojure.set/union (:additions diff))
                          (clojure.set/difference (:retractions diff))))
                    #{}
                    @results1))

(println "Final subscription state:" (sort final1))
(def naive1 (query/query db1 '[:find ?fof
                               :where [?user :friend ?friend]
                               [?friend :friend ?fof]]))
(println "Naive query:" (sort naive1))
(println "Match?" (= final1 naive1))

;; Scenario 2: Subscribe AFTER adding data (like our benchmark)
(println "\n=== SCENARIO 2: Subscribe to populated DB ===")
(def db2 (dfdb/create-db {:storage-backend :memory}))

(println "Adding data BEFORE subscription...")
(dfdb/transact! db2 [[:db/add 1 :friend 2]
                     [:db/add 2 :friend 1]
                     [:db/add 2 :friend 3]
                     [:db/add 3 :friend 2]])

(def results2 (atom []))
(def sub2 (sub/subscribe db2 {:query '[:find ?fof
                                       :where [?user :friend ?friend]
                                       [?friend :friend ?fof]]
                              :callback (fn [diff] (swap! results2 conj diff))
                              :mode :incremental}))

(println "Initial subscription result:" (sort (:additions (first @results2))))

(println "\nAdding update...")
(dfdb/transact! db2 [[:db/add 3 :friend 4]
                     [:db/add 4 :friend 3]])

(def final2 (reduce (fn [state diff]
                      (-> state
                          (clojure.set/union (:additions diff))
                          (clojure.set/difference (:retractions diff))))
                    #{}
                    @results2))

(println "Final subscription state:" (sort final2))
(def naive2 (query/query db2 '[:find ?fof
                               :where [?user :friend ?friend]
                               [?friend :friend ?fof]]))
(println "Naive query:" (sort naive2))
(println "Match?" (= final2 naive2))

(println "\n=== KEY QUESTION ===")
(println "Does the DD pipeline know about data added BEFORE subscription?")

(shutdown-agents)
