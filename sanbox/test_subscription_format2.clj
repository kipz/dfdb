(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])

(def db (dfdb/create-db {:storage-backend :memory}))

(def results (atom []))
(def subscription (sub/subscribe db {:query '[:find ?fof
                                              :where [?user :friend ?friend]
                                              [?friend :friend ?fof]]
                                     :callback (fn [diff]
                                                 (swap! results conj diff))
                                     :mode :incremental}))

(dfdb/transact! db [[:db/add 1 :user/name "Alice"]
                    [:db/add 2 :user/name "Bob"]
                    [:db/add 3 :user/name "Charlie"]
                    [:db/add 1 :friend 2]
                    [:db/add 2 :friend 1]
                    [:db/add 2 :friend 3]
                    [:db/add 3 :friend 2]])

(println "Number of diffs received:" (count @results))
(doseq [[idx diff] (map-indexed vector @results)]
  (println "\nDiff" idx ":")
  (println "  Additions:" (:additions diff))
  (println "  Addition count:" (count (:additions diff)))
  (when (seq (:additions diff))
    (println "  First addition:" (first (:additions diff)))))

(println "\n\nComputing final state...")
(def final-state
  (reduce
   (fn [state diff]
     (-> state
         (clojure.set/union (:additions diff))
         (clojure.set/difference (:retractions diff))))
   #{}
   @results))

(println "Final state:" final-state)
(println "Final state count:" (count final-state))

(println "\n\nNaive query result:")
(def naive-result (dfdb/query db '[:find ?fof
                                   :where [?user :friend ?friend]
                                   [?friend :friend ?fof]]))
(println naive-result)
(println "Naive count:" (count naive-result))

(println "\n\nDo they match?" (= final-state naive-result))

(shutdown-agents)
