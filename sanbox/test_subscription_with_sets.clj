(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.query :as query])

(println "Testing subscription with multi-valued set attributes...")

(def db (dfdb/create-db {:storage-backend :memory}))

;; Add multiple friends to entity 1 in ONE transaction (creates a set)
(println "Adding initial data with multiple friends per entity...")
(dfdb/transact! db [[:db/add 1 :friend 2]
                    [:db/add 1 :friend 3]  ; Entity 1 now has #{2, 3}
                    [:db/add 2 :friend 1]
                    [:db/add 3 :friend 1]])

(println "\nSubscribing to populated DB...")
(def subscription-results (atom []))
(def subscription (sub/subscribe db {:query '[:find ?fof
                                              :where [?user :friend ?friend]
                                              [?friend :friend ?fof]]
                                     :callback (fn [diff]
                                                 (swap! subscription-results conj diff))
                                     :mode :incremental}))

(println "Initial subscription result:" (sort (:additions (first @subscription-results))))

;; Now add another friend to entity 1 (updating the set)
(println "\nAdding another friend to entity 1 (set update)...")
(def tx-result (dfdb/transact! db [[:db/add 1 :friend 4]]))

(println "Transaction delta:")
(doseq [d (:deltas tx-result)]
  (println "  Old:" (:old-value d))
  (println "  New:" (:new-value d)))

(println "\nSubscription diff received:")
(def last-diff (last @subscription-results))
(println "  Additions:" (:additions last-diff))
(println "  Retractions:" (:retractions last-diff))

(def sub-final (reduce
                (fn [state diff]
                  (-> state
                      (clojure.set/union (:additions diff))
                      (clojure.set/difference (:retractions diff))))
                #{}
                @subscription-results))

(def naive-result (query/query db '[:find ?fof
                                    :where [?user :friend ?friend]
                                    [?friend :friend ?fof]]))

(println "\n=== FINAL COMPARISON ===")
(println "Subscription:" (sort sub-final))
(println "Naive query:" (sort naive-result))
(println "Match?" (= sub-final naive-result))

(when-not (= sub-final naive-result)
  (println "\nDifference:")
  (println "  Naive has but subscription missing:" (clojure.set/difference naive-result sub-final)))

(shutdown-agents)
