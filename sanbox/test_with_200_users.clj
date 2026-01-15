(require '[dfdb.core :as dfdb])
(require '[dfdb.query :as query])
(require '[dfdb.performance-test :as perf])

(def db (dfdb/create-db {:storage-backend :memory}))
(def initial-data (perf/generate-social-network 200 8))
(dfdb/transact! db initial-data)

(println "Testing with 200 users...")

;; First pattern
(println "\n1. Matching first pattern...")
(def pattern1-results (query/match-pattern db ['?user :friend '?friend] {} nil))
(println "   Count:" (count pattern1-results))
(println "   Sample (first 3):")
(doseq [r (take 3 pattern1-results)]
  (println "     " r))

;; Second pattern
(println "\n2. Matching second pattern...")
(def pattern2-results (query/match-pattern db ['?friend :friend '?fof] {} nil))
(println "   Count:" (count pattern2-results))
(println "   Sample (first 3):")
(doseq [r (take 3 pattern2-results)]
  (println "     " r))

;; Join
(println "\n3. Joining...")
(def joined (query/join-bindings pattern1-results pattern2-results))
(println "   Count:" (count joined))
(println "   Checking for sets in ?fof values...")
(def bad-bindings (filter #(set? (get % '?fof)) joined))
(if (empty? bad-bindings)
  (println "   All ?fof values are correct (not sets)")
  (do
    (println "   FOUND" (count bad-bindings) "BINDINGS WITH SETS!")
    (println "   First 3 bad bindings:")
    (doseq [b (take 3 bad-bindings)]
      (println "     " b)
      (println "       ?fof =" (get b '?fof) "(type:" (type (get b '?fof)) ")"))))

;; Project
(println "\n4. Projecting...")
(def projected (query/project-bindings joined ['?fof]))
(println "   Count:" (count projected))
(def bad-projected (filter #(set? (first %)) projected))
(if (empty? bad-projected)
  (println "   All projected values are correct")
  (do
    (println "   FOUND" (count bad-projected) "BAD RESULTS!")
    (println "   First 3:")
    (doseq [r (take 3 bad-projected)]
      (println "     " r))))

(shutdown-agents)
