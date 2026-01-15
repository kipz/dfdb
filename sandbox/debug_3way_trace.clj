(ns debug-3way-trace
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

(defn test-with-tracing []
  (let [db (dfdb/create-db {:storage-backend :memory})
        initial-data (generate-multi-hop-graph 50 3 3)
        _ (dfdb/transact! db initial-data)

        query-map '[:find ?fofof
                    :where [?u :connected ?f1]
                    [?f1 :connected ?f2]
                    [?f2 :connected ?fofof]]

        ;; Track which update added each result
        addition-source (atom {})
        update-num (atom 0)
        updates (atom [])

        sub-results (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff]
                                                    (doseq [added (:additions diff)]
                                                      (swap! addition-source assoc added @update-num))
                                                    (swap! sub-results conj diff))
                                        :mode :incremental})

        ;; Run 30 random updates
        _ (dotimes [i 30]
            (swap! update-num inc)
            (let [update (generate-edge-addition 150)]
              (swap! updates conj update)
              (dfdb/transact! db update)))

        sub-final (reduce
                   (fn [state diff]
                     (-> state
                         (clojure.set/union (:additions diff))
                         (clojure.set/difference (:retractions diff))))
                   #{}
                   @sub-results)

        naive-final (set (query/query db query-map))]

    (when (not= sub-final naive-final)
      (let [spurious (clojure.set/difference sub-final naive-final)]
        (println "\nFOUND MISMATCH!")
        (println "Spurious count:" (count spurious))
        (println "Spurious results:" (vec (sort spurious)))
        (doseq [[node] (take 3 spurious)]
          (println "\nSpurious node:" node)
          (println "  Added by update #" (get @addition-source [node]))
          (when-let [idx (get @addition-source [node])]
            (when (> idx 0)
              (println "  That update was:" (get @updates (dec idx))))))))

    (sub/unsubscribe subscription)
    {:match? (= sub-final naive-final)
     :sub-count (count sub-final)
     :naive-count (count naive-final)}))

(comment
  (test-with-tracing))
