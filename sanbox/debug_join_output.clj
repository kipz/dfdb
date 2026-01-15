(require '[dfdb.query :as q])

;; Simulate what the pattern matching returns
(def pattern1-results
  #{{:?user 1, :?friend 2}
    {:?user 1, :?friend 3}
    {:?user 2, :?friend 1}
    {:?user 2, :?friend 3}
    {:?user 3, :?friend 1}
    {:?user 3, :?friend 2}})

(def pattern2-results
  #{{:?friend 1, :?fof 2}
    {:?friend 1, :?fof 3}
    {:?friend 2, :?fof 1}
    {:?friend 2, :?fof 3}
    {:?friend 3, :?fof 1}
    {:?friend 3, :?fof 2}})

(println "Pattern 1 results:")
(doseq [r pattern1-results]
  (println "  " r))

(println "\nPattern 2 results:")
(doseq [r pattern2-results]
  (println "  " r))

(println "\nJoining...")
(def joined (q/join-bindings pattern1-results pattern2-results))

(println "Joined result count:" (count joined))
(println "\nFirst 10 joined bindings:")
(doseq [b (take 10 joined)]
  (println "  " b)
  (println "     ?fof value:" (:?fof b) "Type:" (type (:?fof b))))

(println "\nProjecting to [?fof]...")
(def projected (set (map (fn [bindings]
                           (vec (map #(get bindings %) ['?fof])))
                         joined)))

(println "Projected result count:" (count projected))
(println "\nFirst 10 projected results:")
(doseq [r (take 10 projected)]
  (println "  " r)
  (println "     First elem:" (first r) "Type:" (type (first r))))

(shutdown-agents)
