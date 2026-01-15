(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.performance-test :as perf])

(defn test-scale [num-users]
  (println "\n" (apply str (repeat 60 "=")))
  (println "Testing with" num-users "users...")

  ;; Create databases
  (def sub-db (dfdb/create-db {:storage-backend :memory}))
  (def naive-db (dfdb/create-db {:storage-backend :memory}))

  ;; Generate data
  (def initial-data (perf/generate-social-network num-users 5))

  ;; Load into subscription DB and subscribe
  (dfdb/transact! sub-db initial-data)
  (def subscription-results (atom []))
  (def subscription (sub/subscribe sub-db {:query '[:find ?fof
                                                    :where [?user :friend ?friend]
                                                    [?friend :friend ?fof]]
                                           :callback (fn [diff]
                                                       (swap! subscription-results conj diff))
                                           :mode :incremental}))

  ;; Load into naive DB
  (dfdb/transact! naive-db initial-data)

  ;; Compute subscription final state
  (def sub-final (reduce
                  (fn [state diff]
                    (-> state
                        (clojure.set/union (:additions diff))
                        (clojure.set/difference (:retractions diff))))
                  #{}
                  @subscription-results))

  ;; Query naive DB
  (def naive-result (dfdb/query naive-db '[:find ?fof
                                           :where [?user :friend ?friend]
                                           [?friend :friend ?fof]]))

  (println "Subscription count:" (count sub-final))
  (println "Naive count:" (count naive-result))
  (println "Match?:" (= sub-final naive-result))

  (when-not (= sub-final naive-result)
    (println "\nSample subscription results (first 3):")
    (doseq [r (take 3 sub-final)]
      (println "  " r "(type:" (type r) ")"))

    (println "\nSample naive results (first 3):")
    (doseq [r (take 3 naive-result)]
      (println "  " r "(type:" (type r) ")"))

    (let [sub-only (clojure.set/difference sub-final naive-result)
          naive-only (clojure.set/difference naive-result sub-final)]
      (println "\nSub-only count:" (count sub-only))
      (println "Naive-only count:" (count naive-only))
      (when (seq naive-only)
        (println "\nFirst naive-only result:")
        (let [r (first naive-only)]
          (println "  Value:" r)
          (println "  Type:" (type r))
          (println "  Class:" (class r))
          (when (coll? r)
            (println "  Coll type - Set?:" (set? r) "Vector?:" (vector? r))))))))

;; Test at different scales
(test-scale 25)
(test-scale 50)
(test-scale 75)
(test-scale 100)
(test-scale 150)

(shutdown-agents)
