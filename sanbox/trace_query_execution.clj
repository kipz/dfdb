(require '[dfdb.core :as dfdb])
(require '[dfdb.query :as query])
(require '[dfdb.performance-test :as perf])

;; Create a small test case
(def db (dfdb/create-db {:storage-backend :memory}))

;; Add just a few friendships
(dfdb/transact! db [[:db/add 1 :user/name "Alice"]
                    [:db/add 2 :user/name "Bob"]
                    [:db/add 3 :user/name "Charlie"]
                    [:db/add 4 :user/name "Dave"]
                    [:db/add 1 :friend 2]
                    [:db/add 2 :friend 1]
                    [:db/add 2 :friend 3]
                    [:db/add 3 :friend 2]
                    [:db/add 3 :friend 4]
                    [:db/add 4 :friend 3]])

;; Now manually walk through what the query does
(println "Testing pattern matching...")

;; First pattern: [?user :friend ?friend]
(println "\n1. Matching first pattern [?user :friend ?friend] with empty bindings:")
(def pattern1-results (query/match-pattern db ['?user :friend '?friend] {} nil))
(println "   Count:" (count pattern1-results))
(println "   First 5 results:")
(doseq [r (take 5 pattern1-results)]
  (println "     " r)
  (println "       ?friend value:" (get r '?friend) "Type:" (type (get r '?friend))))

;; Second pattern: [?friend :friend ?fof]
(println "\n2. Matching second pattern [?friend :friend ?fof] with empty bindings:")
(def pattern2-results (query/match-pattern db ['?friend :friend '?fof] {} nil))
(println "   Count:" (count pattern2-results))
(println "   First 5 results:")
(doseq [r (take 5 pattern2-results)]
  (println "     " r)
  (println "       ?fof value:" (get r '?fof) "Type:" (type (get r '?fof))))

;; Hash join
(println "\n3. Joining the two result sets:")
(def joined (query/join-bindings pattern1-results pattern2-results))
(println "   Count:" (count joined))
(println "   First 5 joined results:")
(doseq [r (take 5 joined)]
  (println "     " r)
  (println "       ?fof value:" (get r '?fof) "Type:" (type (get r '?fof))))

;; Project to [?fof]
(println "\n4. Projecting to [?fof]:")
(def projected (query/project-bindings joined ['?fof]))
(println "   Count:" (count projected))
(println "   Results:")
(doseq [r projected]
  (println "     " r)
  (when (vector? r)
    (println "       First elem:" (first r) "Type:" (type (first r)))))

;; Compare with full query
(println "\n5. Full query execution:")
(def full-result (query/query db '[:find ?fof
                                   :where [?user :friend ?friend]
                                   [?friend :friend ?fof]]))
(println "   Count:" (count full-result))
(println "   Results:")
(doseq [r full-result]
  (println "     " r)
  (when (vector? r)
    (println "       First elem:" (first r) "Type:" (type (first r)))))

(shutdown-agents)
