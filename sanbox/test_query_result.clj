(require '[dfdb.core :as dfdb])

(def db (dfdb/create-db {:storage-backend :memory}))
(dfdb/transact! db [[:db/add 1 :user/name "Alice"]
                    [:db/add 2 :user/name "Bob"]
                    [:db/add 3 :user/name "Charlie"]
                    [:db/add 1 :friend 2]
                    [:db/add 2 :friend 1]
                    [:db/add 2 :friend 3]
                    [:db/add 3 :friend 2]])

(def result (dfdb/query db '[:find ?fof
                             :where [?user :friend ?friend]
                             [?friend :friend ?fof]]))

(println "Result:" result)
(println "Result type:" (type result))
(println "Count:" (count result))
(println "First element:" (first result))
(println "First element type:" (type (first result)))

(shutdown-agents)
