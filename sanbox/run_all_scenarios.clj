; Run with: clojure -M:perf-test run_all_scenarios.clj
(require '[dfdb.performance-test :as perf])

(println "Running All Performance Scenarios")
(println (apply str (repeat 80 "=")))
(println)

(perf/scenario-1-social-network)
(println)

(perf/scenario-2-ecommerce-inventory)
(println)

(perf/scenario-3-analytics-aggregation)
(println)

(perf/scenario-4-high-churn-sessions)
(println)

(println)
(println (apply str (repeat 80 "=")))
(println "ALL SCENARIOS COMPLETE")
(println (apply str (repeat 80 "=")))

(shutdown-agents)
