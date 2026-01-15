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
(println "\nFirst diff:")
(clojure.pprint/pprint (first @results))
(println "\nAdditions from first diff:")
(clojure.pprint/pprint (:additions (first @results)))
(println "\nFirst addition:")
(println (first (:additions (first @results))))
(println "Type:" (type (first (:additions (first @results)))))

(shutdown-agents)
