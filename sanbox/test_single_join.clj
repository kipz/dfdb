(require '[dfdb.core :as dfdb])
(require '[dfdb.subscription :as sub])
(require '[dfdb.query :as query])

(defn time-fn [f]
  (let [start (System/nanoTime) result (f) end (System/nanoTime)]
    [result (- end start)]))

(defn mean [coll]
  (if (empty? coll) 0 (/ (reduce + coll) (count coll))))

(println "Testing 3-Way Join Performance")
(println (apply str (repeat 60 "=")))

;; Generate simple chain: A→B→C→D
(def data
  (concat
   (for [i (range 1 51)] [:db/add i :user/name (str "User-" i)])
   (for [i (range 1 50)] [:db/add i :conn (inc i)])  ; 1→2, 2→3, ... 50→51
    ;; Add some branches
   (for [i (range 1 25)] [:db/add i :conn (+ i 25)])))  ; 1→26, 2→27, etc

(def sub-db (dfdb/create-db {:storage-backend :memory}))
(def naive-db (dfdb/create-db {:storage-backend :memory}))

(dfdb/transact! sub-db data)
(dfdb/transact! naive-db data)

(println "Data loaded: 50 nodes with connections")

;; Subscribe
(def results (atom []))
(println "\nCreating subscription for 3-way join...")
(def [sub comp-time] (time-fn #(sub/subscribe sub-db {:query '[:find ?c
                                                               :where [?a :conn ?b]
                                                               [?b :conn ?c]
                                                               [?c :conn ?d]]
                                                      :callback (fn [diff] (swap! results conj diff))
                                                      :mode :incremental})))
(println "Compilation:" (format "%.0fms" (/ comp-time 1e6)))

;; Run 20 updates
(def updates (for [i (range 20)]
               (let [from (inc (rand-int 45))
                     to (+ from 1 (rand-int 5))]
                 [[:db/add from :conn to]])))

(println "\nRunning 20 updates...")
(println "Subscriptions:")
(def sub-times (doall (for [u updates]
                        (let [[_ t] (time-fn #(dfdb/transact! sub-db u))]
                          t))))

(println "Naive re-execution:")
(def naive-times (doall (for [u updates]
                          (do (dfdb/transact! naive-db u)
                              (let [[_ t] (time-fn #(query/query naive-db '[:find ?c
                                                                            :where [?a :conn ?b]
                                                                            [?b :conn ?c]
                                                                            [?c :conn ?d]]))]
                                t)))))

(def sub-mean (mean sub-times))
(def naive-mean (mean naive-times))
(def speedup (/ naive-mean sub-mean))

(def sub-final (reduce (fn [s d] (-> s
                                     (clojure.set/union (:additions d))
                                     (clojure.set/difference (:retractions d))))
                       #{}
                       @results))
(def naive-final (query/query naive-db '[:find ?c
                                         :where [?a :conn ?b]
                                         [?b :conn ?c]
                                         [?c :conn ?d]]))

(println "\n=== RESULTS ===")
(println "Subscription avg:" (format "%.2fms" (/ sub-mean 1e6)))
(println "Naive avg:" (format "%.2fms" (/ naive-mean 1e6)))
(println "Speedup:" (format "%.1fx" (double speedup)))
(println "Match:" (= sub-final (set naive-final)))
(println "  Sub count:" (count sub-final))
(println "  Naive count:" (count naive-final))

(when (> (double speedup) 1.0)
  (println "\n✅ SUBSCRIPTIONS WIN!"))
(when (<= (double speedup) 1.0)
  (println "\n⚠️  NAIVE QUERIES WIN"))

(shutdown-agents)
