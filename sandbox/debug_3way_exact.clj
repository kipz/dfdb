(ns debug-3way-exact
  (:require [dfdb.core :as dfdb]
            [dfdb.query :as query]
            [dfdb.subscription :as sub]))

(defn generate-multi-hop-graph [num-nodes branching-factor depth]
  (let [edges (atom [])]
    (doseq [layer (range depth)]
      (let [layer-start (* layer num-nodes)
            next-layer-start (* (inc layer) num-nodes)]
        (doseq [from-offset (range num-nodes)]
          (let [from (+ layer-start from-offset)]
            (doseq [to-offset (range branching-factor)]
              (let [to (+ next-layer-start (mod (+ from-offset to-offset) num-nodes))]
                (swap! edges conj [:db/add from :connected to])))))))
    @edges))

(defn generate-edge-addition [max-node]
  (let [from (inc (rand-int max-node))
        to (inc (rand-int max-node))]
    (if (= from to)
      (generate-edge-addition max-node)
      [[:db/add from :connected to]])))

(defn test-3way-exact []
  (let [db (dfdb/create-db {:storage-backend :memory})

        ;; Exact same parameters as test
        initial-data (generate-multi-hop-graph 50 3 3)

        _ (dfdb/transact! db initial-data)

        query-map '[:find ?fofof
                    :where [?u :connected ?f1]
                    [?f1 :connected ?f2]
                    [?f2 :connected ?fofof]]

        naive-initial (set (query/query db query-map))

        ;; Test subscription
        sub-results (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff] (swap! sub-results conj diff))
                                        :mode :incremental})

        _ (println "Initial: Naive =" (count naive-initial) "Sub =" (count (:additions (first @sub-results))))

        ;; Run 30 random updates like the test
        _ (dotimes [i 30]
            (let [update (generate-edge-addition 150)]
              (dfdb/transact! db update)))

        ;; Compute subscription final
        sub-final (reduce
                   (fn [state diff]
                     (-> state
                         (clojure.set/union (:additions diff))
                         (clojure.set/difference (:retractions diff))))
                   #{}
                   @sub-results)

        naive-final (set (query/query db query-map))]

    (println "Final: Subscription =" (count sub-final) "Naive =" (count naive-final))
    (println "Match?" (= sub-final naive-final))
    (when (not= sub-final naive-final)
      (let [sub-only (clojure.set/difference sub-final naive-final)
            naive-only (clojure.set/difference naive-final sub-final)]
        (println "Subscription has" (count sub-only) "extra:" sub-only)
        (println "Subscription missing" (count naive-only) ":" naive-only)

        ;; Check if extra results exist in database
        (doseq [[node] sub-only]
          (let [paths (query/query db '[:find ?u ?f1 ?f2
                                        :where [?u :connected ?f1]
                                        [?f1 :connected ?f2]
                                        [?f2 :connected ?fofof]]
                                   {:fofof node})]
            (println "  Paths to" node ":" (count paths) (if (empty? paths) "SPURIOUS!" "valid"))))))

    (sub/unsubscribe subscription)
    {:sub-count (count sub-final) :naive-count (count naive-final) :match? (= sub-final naive-final)}))

(comment
  (test-3way-exact))
