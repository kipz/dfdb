(ns debug-3way-retractions
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

(defn test-retractions []
  (let [db (dfdb/create-db {:storage-backend :memory})
        initial-data (generate-multi-hop-graph 50 3 3)
        _ (dfdb/transact! db initial-data)

        query-map '[:find ?fofof
                    :where [?u :connected ?f1]
                    [?f1 :connected ?f2]
                    [?f2 :connected ?fofof]]

        retraction-count (atom 0)
        addition-count (atom 0)

        sub-results (atom [])
        subscription (sub/subscribe db {:query query-map
                                        :callback (fn [diff]
                                                    (swap! addition-count + (count (:additions diff)))
                                                    (swap! retraction-count + (count (:retractions diff)))
                                                    (swap! sub-results conj diff))
                                        :mode :incremental})

        ;; Run updates
        _ (dotimes [i 30]
            (dfdb/transact! db (generate-edge-addition 150)))

        sub-final (reduce
                   (fn [state diff]
                     (-> state
                         (clojure.set/union (:additions diff))
                         (clojure.set/difference (:retractions diff))))
                   #{}
                   @sub-results)

        naive-final (set (query/query db query-map))]

    (println "Additions:" @addition-count "Retractions:" @retraction-count)
    (println "Final: Sub =" (count sub-final) "Naive =" (count naive-final))
    (println "Match?" (= sub-final naive-final))

    (when (> @retraction-count 0)
      (println "\n✓ Retractions ARE happening!"))
    (when (= @retraction-count 0)
      (println "\n✗ NO RETRACTIONS - This might be the bug!"))

    (sub/unsubscribe subscription)
    {:match? (= sub-final naive-final)
     :additions @addition-count
     :retractions @retraction-count}))

(comment
  (test-retractions))
