(require '[dfdb.query :as q])

(def bindings1 #{{:?user 1 :?friend 2}
                 {:?user 2 :?friend 1}
                 {:?user 2 :?friend 3}})

(def bindings2 #{{:?friend 1 :?fof 2}
                 {:?friend 2 :?fof 1}
                 {:?friend 2 :?fof 3}
                 {:?friend 3 :?fof 2}})

(println "Bindings 1:" bindings1)
(println "Bindings 2:" bindings2)
(println "\nJoining...")
(def result (q/join-bindings bindings1 bindings2))
(println "Result:" result)
(println "Result count:" (count result))

(shutdown-agents)
