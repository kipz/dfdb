(ns debug-subscription
  (:require [dfdb.core :as dfdb]
            [dfdb.subscription :as sub]
            [dfdb.query :as query]))

(comment
  ;; Minimal repro of the smoke test failure
  (def db (dfdb/create-db {:storage-backend :memory}))

  (def query-map '[:find ?price
                   :where [?product :product/price ?price]
                   [(< ?price 50)]])

  (def subscription-results (atom []))

  (sub/subscribe db {:query query-map
                     :callback (fn [diff]
                                 (println "Diff received:" diff)
                                 (swap! subscription-results conj diff))
                     :mode :incremental})

  ;; Initial data
  (dfdb/transact! db [[:db/add 1 :product/name "Widget"]
                      [:db/add 1 :product/price 25]])

  ;; More updates
  (dfdb/transact! db [[:db/add 2 :product/name "Gadget"]
                      [:db/add 2 :product/price 45]])

  (dfdb/transact! db [[:db/add 3 :product/name "Expensive"]
                      [:db/add 3 :product/price 75]])  ;; This should NOT match predicate!

  (dfdb/transact! db [[:db/add 1 :product/price 30]])  ;; Update

  ;; Compute subscription final state
  (def subscription-final
    (reduce
     (fn [state diff]
       (-> state
           (clojure.set/union (:additions diff))
           (clojure.set/difference (:retractions diff))))
     #{}
     @subscription-results))

  ;; Get naive query result
  (def naive-result (set (query/query db query-map)))

  (println "\nAll diffs:")
  (doseq [[i diff] (map-indexed vector @subscription-results)]
    (println (str "Diff " i ":") diff))

  (println "\nSubscription final:" subscription-final)
  (println "Naive result:" naive-result)
  (println "Match?" (= subscription-final naive-result))

  (when-not (= subscription-final naive-result)
    (println "\nSubscription only:" (clojure.set/difference subscription-final naive-result))
    (println "Naive only:" (clojure.set/difference naive-result subscription-final))))
