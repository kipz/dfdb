(require '[dfdb.joins-aggregates-performance-test :as ja])

(ja/run-all-join-aggregate-tests)

(shutdown-agents)
