(require '[dfdb.core :as dfdb])
(require '[dfdb.query :as query])

(println "Analyzing why [1] and [3] get retracted...")

;; Set up the state right before UPDATE 3
(def db (dfdb/create-db {:storage-backend :memory}))

(def before-update-3
  [[:db/add 1 :user/name "Alice"]
   [:db/add 2 :user/name "Bob"]
   [:db/add 3 :user/name "Charlie"]
   [:db/add 4 :user/name "Dave"]
   [:db/add 5 :user/name "Eve"]
   ;; Initial friendships
   [:db/add 1 :friend 2]
   [:db/add 2 :friend 1]
   [:db/add 2 :friend 3]
   [:db/add 3 :friend 2]
   [:db/add 3 :friend 4]
   [:db/add 4 :friend 3]
   [:db/add 4 :friend 5]
   [:db/add 5 :friend 4]
   ;; Update 1: 1<->3
   [:db/add 1 :friend 3]
   [:db/add 3 :friend 1]
   ;; Update 2: 2<->5
   [:db/add 2 :friend 5]
   [:db/add 5 :friend 2]])

(dfdb/transact! db before-update-3)

(println "\n=== STATE BEFORE UPDATE 3 ===")
(def before-query (query/query db '[:find ?fof
                                    :where [?user :friend ?friend]
                                    [?friend :friend ?fof]]))
(println "Query result count:" (count before-query))
(println "Results:" (sort before-query))

(println "\n=== CHECKING SPECIFIC PATHS FOR [1] ===")
(def paths-to-1 (query/query db '[:find ?user ?friend
                                  :where [?user :friend ?friend]
                                  [?friend :friend 1]]))
(println "Paths that lead to [1]:")
(doseq [[user friend] (sort paths-to-1)]
  (println "  User" user "-> Friend" friend "-> 1"))

(println "\n=== CHECKING SPECIFIC PATHS FOR [3] ===")
(def paths-to-3 (query/query db '[:find ?user ?friend
                                  :where [?user :friend ?friend]
                                  [?friend :friend 3]]))
(println "Paths that lead to [3]:")
(doseq [[user friend] (sort paths-to-3)]
  (println "  User" user "-> Friend" friend "-> 3"))

(println "\n=== APPLYING UPDATE 3: 4<->1 ===")
(dfdb/transact! db [[:db/add 4 :friend 1]
                    [:db/add 1 :friend 4]])

(def after-query (query/query db '[:find ?fof
                                   :where [?user :friend ?friend]
                                   [?friend :friend ?fof]]))
(println "Query result count:" (count after-query))
(println "Results:" (sort after-query))

(println "\n=== PATHS ADDED BY UPDATE 3 ===")
(println "New path: 4 -> 1 -> ? (friend 1's friends)")
(def friend-1-friends (query/query db '[:find ?f :where [1 :friend ?f]]))
(println "Friend 1 has friends:" (sort friend-1-friends))

(println "\nNew path: 1 -> 4 -> ? (friend 4's friends)")
(def friend-4-friends (query/query db '[:find ?f :where [4 :friend ?f]]))
(println "Friend 4 has friends:" (sort friend-4-friends))

(println "\n=== CHECKING IF [1] and [3] STILL EXIST ===")
(def paths-to-1-after (query/query db '[:find ?user ?friend
                                        :where [?user :friend ?friend]
                                        [?friend :friend 1]]))
(println "Paths that lead to [1] after update:")
(doseq [[user friend] (sort paths-to-1-after)]
  (println "  User" user "-> Friend" friend "-> 1"))

(def paths-to-3-after (query/query db '[:find ?user ?friend
                                        :where [?user :friend ?friend]
                                        [?friend :friend 3]]))
(println "\nPaths that lead to [3] after update:")
(doseq [[user friend] (sort paths-to-3-after)]
  (println "  User" user "-> Friend" friend "-> 3"))

(println "\n=== CONCLUSION ===")
(println "Should [1] still be in results? YES - multiple paths lead to it")
(println "Should [3] still be in results? YES - multiple paths lead to it")
(println "But subscription retracted them - this is the BUG!")

(shutdown-agents)
