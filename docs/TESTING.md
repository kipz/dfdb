# DFDB Testing Guide

## Test Organization

Tests are organized into two separate directories:

### Unit Tests (`test/`)

**Purpose:** Fast tests for correctness and functionality
**Location:** `test/dfdb/`
**Run time:** Seconds to minutes

**Includes:**
- Basic CRUD operations
- Query functionality
- Transaction processing
- Subscription correctness
- Index operations
- Temporal queries
- Integration tests

**Run with:**
```bash
# All unit tests
clojure -M:test -m cognitect.test-runner

# Or explicitly
clojure -M:unit-test -m cognitect.test-runner

# Specific namespace
clojure -M:test -m cognitect.test-runner -n dfdb.query-test

# Specific test
clojure -M:test -m cognitect.test-runner -v dfdb.query-test/test-basic-pattern
```

### Performance Tests (`perf/`)

**Purpose:** Benchmark subscriptions vs naive queries
**Location:** `perf/dfdb/`
**Run time:** Minutes to hours (includes JVM warmup, large datasets)

**Includes:**
- Performance benchmarks (subscriptions vs naive)
- Join performance tests (2-way, 3-way, 4-way)
- Aggregate performance tests
- Scale variation tests
- Memory profiling tests

**Run with:**
```bash
# All performance tests
clojure -M:perf-test -m cognitect.test-runner -d perf

# Specific performance namespace
clojure -M:perf-test -m cognitect.test-runner -d perf -n dfdb.performance-test

# Via custom scripts (recommended for better output)
clojure -M run_scenario_1.clj
clojure -M run_all_scenarios.clj
clojure -M run_join_aggregate_tests.clj
```

---

## Aliases Explained

### `:test` or `:unit-test`
- Adds `test/` directory to classpath
- Includes test-runner dependency
- Use for normal development and CI

### `:perf-test`
- Adds `perf/` directory to classpath
- Includes test-runner + profiling tools (Criterium, clj-async-profiler)
- JVM tuning: 4GB heap, G1GC
- Use for performance benchmarking

### `:perf`
- Profiling tools only (no test-runner)
- For use in REPL when profiling code
- Example: `clojure -M:perf:repl`

### `:repl`
- nREPL server dependencies
- For interactive development
- Example: `clojure -M:repl -m nrepl.cmdline`

### `:dev`
- Development tools (tools.namespace for reloading)
- Extra dev utilities

---

## Why This Separation?

### Performance Tests Should Not Run in CI (by default)

**Reasons:**
1. **Long running** - can take 10+ minutes
2. **Resource intensive** - need 4GB heap
3. **Non-deterministic** - some timing variance
4. **Different purpose** - benchmarking, not correctness

### Unit Tests Should Be Fast

**Goals:**
1. **Quick feedback** - run on every commit
2. **Deterministic** - same result every time
3. **Correctness focused** - not performance
4. **CI friendly** - fast enough for PR checks

---

## Running Tests

### Development Workflow

```bash
# Quick check before commit
clojure -M:test -m cognitect.test-runner

# Run specific test you're working on
clojure -M:test -m cognitect.test-runner -n dfdb.query-test

# Performance benchmarking (when needed)
clojure -M:perf-test -m cognitect.test-runner -d perf
```

### CI/CD

```yaml
# .github/workflows/test.yml or similar

unit-tests:
  run: clojure -M:test -m cognitect.test-runner

performance-tests:
  # Run nightly or on-demand, not on every PR
  run: clojure -M:perf-test -m cognitect.test-runner -d perf
```

### Via REPL

```clojure
;; Start REPL with test paths
clojure -M:test:repl -m nrepl.cmdline

;; Load and run unit tests
(require '[dfdb.query-test :as qt])
(clojure.test/run-tests 'dfdb.query-test)

;; For performance tests
clojure -M:perf-test:repl -m nrepl.cmdline

(require '[dfdb.performance-test :as perf])
(perf/scenario-1-social-network)
```

---

## Performance Test Details

### Available Scenarios

**In `performance_test.clj`:**
1. `scenario-1-social-network` - Friend-of-friend (2-way join)
2. `scenario-2-ecommerce-inventory` - Predicates + filters
3. `scenario-3-analytics-aggregation` - Aggregation with joins
4. `scenario-4-high-churn-sessions` - High-frequency updates
5. `scenario-1-scale-small` - 100 users
6. `scenario-1-scale-large` - 5,000 users
7. `scenario-batch-micro-updates` - Small batches
8. `scenario-batch-bulk-updates` - Large batches

**In `joins_aggregates_performance_test.clj`:**
1. `test-3-way-join` - 3-pattern join
2. `test-4-way-join` - 4-pattern join
3. `test-star-schema-join` - Multi-table join
4. `test-self-join` - Mutual relationships
5. `test-simple-aggregation` - Count/sum
6. `test-join-plus-aggregate` - Join then aggregate
7. `test-multi-join-aggregate` - Complex aggregation
8. `test-manager-chain` - Hierarchical queries
9. `test-complex-aggregation` - Multiple aggregates
10. `test-triangle-join` - Transitive closure
11. `test-aggregate-with-filter` - Filtered aggregation

### Running Individual Scenarios

```bash
# Via custom scripts (best output formatting)
clojure run_scenario_1.clj
clojure run_all_scenarios.clj

# Via REPL
clj-nrepl-eval -p <port> "(require '[dfdb.performance-test :as perf] :reload)"
clj-nrepl-eval -p <port> "(perf/scenario-1-social-network)"
```

---

## Performance Testing Best Practices

### 1. Warm Up the JVM

All performance tests include 2 warmup iterations to avoid JIT compilation skew.

### 2. Use Sufficient Scale

Small datasets (<50 entities) may show noise. Use:
- Small: 100-200 entities (quick tests)
- Medium: 500-1,000 entities (realistic)
- Large: 5,000+ entities (stress tests)

### 3. Run Multiple Times

Performance can vary ±10%. For critical measurements:
```bash
for i in {1..5}; do clojure run_scenario_1.clj; done
```

### 4. Profile When Needed

```bash
# Start with profiler
clojure -M:perf:repl -m nrepl.cmdline

# In REPL
(require '[clj-async-profiler.core :as prof])
(prof/profile (perf/scenario-1-social-network))
(prof/serve-files 8080)
# Open http://localhost:8080 for flamegraph
```

---

## Expected Performance Characteristics

### Subscriptions Win (1.5-3x faster)

- Large data (>500 entities)
- Multi-pattern joins (2+ patterns)
- High cardinality (large result sets)
- Many updates (>1,000)

### Naive Queries Win (1.3-7x faster)

- Small data (<200 entities)
- Simple queries (1-2 patterns + predicates)
- Few updates (<100)
- Small result sets

### Break-Even

- Medium data (200-500 entities)
- Moderate complexity (2 patterns)
- Moderate updates (100-1,000)

---

## Interpreting Results

### Metrics Reported

- **Compilation time:** One-time cost for subscriptions
- **Mean latency:** Average time per update/query
- **P50, P95, P99:** Percentile latencies
- **Throughput:** Updates or queries per second
- **Memory delta:** Heap growth during benchmark
- **Speedup:** Naive latency / Subscription latency
- **Results match:** Correctness validation

### What "Good" Looks Like

**For subscriptions to win:**
- Speedup > 1.5x
- Memory delta reasonable (<500MB)
- Results match: true
- Compilation time < query_cost × num_updates

**For correctness:**
- Results match: **MUST be true**
- Any mismatch indicates a bug

---

## Troubleshooting

### Performance Tests Fail with OOM

Increase heap size in deps.edn:
```clojure
:jvm-opts ["-Xmx8g" "-Xms8g" ...]  ; Increase from 4g to 8g
```

### Compilation Takes Forever

4-way+ joins can take 20+ seconds to compile. This is expected. Reduce scale or number of patterns if needed.

### Results Don't Match

This indicates a bug! Check:
1. Multi-valued attribute handling
2. DD pipeline initialization
3. Transaction delta generation

### Tests Are Slow

Performance tests are meant to be slow! For quick checks:
- Use smaller datasets
- Reduce num-updates
- Run single scenarios instead of full suite

---

## File Structure

```
dfdb/
├── src/dfdb/           # Production code
├── test/dfdb/          # Unit tests (fast, CI-friendly)
│   ├── query_test.clj
│   ├── subscription_verification_test.clj
│   └── ...
├── perf/dfdb/          # Performance tests (slow, benchmarking)
│   ├── performance_test.clj
│   ├── joins_aggregates_performance_test.clj
│   └── performance_smoke_test.clj
├── deps.edn            # Dependency configuration
├── run_*.clj           # Convenience scripts for perf tests
└── *.md                # Documentation and reports
```

---

## Summary

✅ **Unit tests:** `test/` directory, run with `-M:test`
✅ **Performance tests:** `perf/` directory, run with `-M:perf-test`
✅ **Separated:** Performance tests don't run with unit tests
✅ **Configured:** Proper JVM tuning for performance tests
✅ **Documented:** Clear guidance on when to run what

**Run unit tests frequently. Run performance tests when benchmarking or validating optimizations.**
