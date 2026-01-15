(require '[dfdb.joins-aggregates-performance-test :as ja])

(println "Running quick join/aggregate tests...")
(println)

;; Run just the key tests
(ja/test-3-way-join)
(ja/test-self-join)
(ja/test-simple-aggregation)
(ja/test-join-plus-aggregate)
(ja/test-triangle-join)

(shutdown-agents)
