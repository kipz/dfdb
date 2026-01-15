(require '[dfdb.performance-test :as perf])

(println "Running Scenario 1: Social Network - Friend Recommendations")
(println "=" (apply str (repeat 70 "=")))

(perf/scenario-1-social-network)

(shutdown-agents)
