(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])

(def db (dfdb/create-db {:storage-backend :memory}))

(dfdb/transact! db [[:db/add 1 :friend 2]
                    [:db/add 1 :friend 3]
                    [:db/add 2 :friend 1]
                    [:db/add 3 :friend 1]])

(def callback-count (atom 0))
(def subscription-results (atom []))
(def subscription (sub/subscribe db {:query '[:find ?fof
                                              :where [?user :friend ?friend]
                                              [?friend :friend ?fof]]
                                     :callback (fn [diff]
                                                 (swap! callback-count inc)
                                                 (println "\n[CALLBACK #" @callback-count "]")
                                                 (println "  Additions:" (count (:additions diff)) "→" (sort (:additions diff)))
                                                 (println "  Retractions:" (count (:retractions diff)) "→" (sort (:retractions diff)))
                                                 (swap! subscription-results conj diff))
                                     :mode :incremental}))

(println "Callback count after subscription:" @callback-count)

(println "\n" (apply str (repeat 60 "=")))
(println "TRANSACTION: Adding friend 4 to entity 1")
(println (apply str (repeat 60 "=")))
(dfdb/transact! db [[:db/add 1 :friend 4]])

(println "\nCallback count after transaction:" @callback-count)
(println "Total diffs received:" (count @subscription-results))

(println "\nAll diffs:")
(doseq [[idx diff] (map-indexed vector @subscription-results)]
  (println "Diff" idx "- Additions:" (count (:additions diff)) "Retractions:" (count (:retractions diff))))

(shutdown-agents)
