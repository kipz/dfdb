# When createing clojure code:
* Avoid atoms to hold state where possible (prefer reduce and other accumulators)
* Use the clojure repl for experiments, putting files in sandbox/
* Use docs/ for markdown files you generate
* ALWAYS create tests before implementing features and bug fixes
* To run unit-tests, run: ./run-tests.sh
* To run performance/benchmark tests, run: ./run-perf-tests.sh
