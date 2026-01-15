# Performance Analysis: Subscription System Bottlenecks

## Executive Summary

Analysis of performance benchmarks reveals systematic performance issues causing subscriptions to be **slower than naive queries** in several scenarios, particularly for aggregates. Three critical issues identified:

### Failing Benchmarks:
- **Complex Aggregate**: 0.6x speedup (23.35ms vs 14.54ms) - 60% SLOWER
- **Aggregation (Count)**: 0.3x speedup (6.48ms vs 1.84ms) - 70% SLOWER
- **Join + Aggregate**: 0.9x speedup (12.11ms vs 10.71ms) - 10% SLOWER
- **Filtered Aggregate**: 0.6x speedup (12.31ms vs 7.90ms) - 60% SLOWER
- **High-Churn Sessions**: 0.8x speedup (10.56ms vs 8.04ms) - 20% SLOWER
- **Micro Updates**: 0.7x speedup (4.29ms vs 2.81ms) - 30% SLOWER

---

## Problem 1: Aggregate Operator - Materializing Multisets

### Location: `src/dfdb/dd/aggregate.clj:16-22`

```clojure
(let [grouped (group-by group-fn (mapcat (fn [[value count]]
                                           (repeat count value))
                                         (seq coll)))]
      aggregates (into {}
                       (map (fn [[group-key values]]
                              [group-key (agg-fn values)])
                            grouped)))
```

### The Problem:
The aggregate operator **materializes the entire multiset by expanding it**:
- Multiset representation: `{[1 2 3] → 1000}` means value `[1 2 3]` has multiplicity 1000
- Current code: `(repeat 1000 [1 2 3])` creates 1000 copies in memory
- Then `group-by` processes all 1000 materialized items
- Then `agg-fn` aggregates all 1000 items

### Performance Impact:
- **Time Complexity**: O(sum of all multiplicities) instead of O(number of unique values)
- **Memory**: Allocates temporary vectors for all repeated values
- For `{v1 → 1000, v2 → 2000, v3 → 500}`:
  - Current: Processes 3500 items
  - Optimal: Should process 3 items with their counts

### Example from Benchmark:
```
Complex Aggregate test:
- Initial state: ~300 transactions with multiplicities
- Each update: Rematerializes ~300 × average_multiplicity items
- Aggregate functions work on materialized lists instead of frequencies
```

### Why This Matters:
Aggregates are the **worst performing** operations in benchmarks:
- Complex Aggregate: **60% slower** than naive
- Simple Count Aggregation: **70% slower** than naive
- This should be the **fastest** operation (just maintaining running totals)

---

## Problem 2: Join Operator - Linear Scan Instead of Hash Index

### Location: `src/dfdb/dd/multipattern.clj:21-26, 33-38`

```clojure
(mapcat (fn [[right-binding right-mult]]
          (when (= join-key (select-keys right-binding join-vars))
            (let [joined (merge binding right-binding)
                  combined-mult (* mult right-mult)]
              (when (not= 0 combined-mult)
                [(delta/make-delta joined combined-mult)]))))
        @right-state)
```

### The Problem:
For each incoming delta, the join operator:
1. Computes the join key from the delta
2. **Scans every entry in the entire right-state atom**
3. Manually checks if join keys match with `(= join-key (select-keys right-binding join-vars))`
4. Repeats for every delta

### Performance Impact:
- **Time Complexity**: O(|state|) per delta instead of O(1) average
- For a state with 1000 entries and 100 deltas:
  - Current: 100,000 comparisons
  - With hash index: ~100 lookups
- Gets worse as data grows

### Why No Hash Index?
The state is stored as a flat map: `{binding1 → count1, binding2 → count2, ...}`

Should be indexed as: `{join-key → {binding1 → count1, binding2 → count2}}`

### Example from Benchmark:
```
Social Network test (5000 users, avg 20 friends):
- Compilation time: 6884ms (likely building initial state)
- Update time: 267ms per update
- Linear scan through thousands of friendship bindings per delta
```

---

## Problem 3: Excessive Atom Operations (No Batching)

### Locations:
- `multipattern.clj:19` - `(swap! left-state update binding (fnil + 0) mult)`
- `multipattern.clj:31` - `(swap! right-state update binding (fnil + 0) mult)`
- `incremental-core.clj:86` - `(swap! (:accumulated state) update value (fnil + 0) mult)`

### The Problem:
**Every single delta causes a separate atom swap**:

1. Transaction produces batch of deltas (e.g., 50 datoms)
2. `process-deltas` is called with the batch
3. Inside operators, deltas are processed one-by-one:
   ```clojure
   (doseq [d deltas]
     (let [pattern-out (core/process-delta pattern-op d)]  ; swap!
       ...))
   ```
4. Each `process-delta` call does `swap!` operations

### Atom Swap Cost:
Each `swap!` involves:
1. Deref the atom
2. Compute new value (e.g., `update binding (fnil + 0) mult`)
3. Compare-and-swap (CAS) - retries on contention
4. Creates new persistent map structure

For 50 deltas with 3 operators in chain:
- Current: 150 atom operations
- Optimal: 3 atom operations (one per operator after batch processing)

### Example from Benchmark:
```
Micro Updates test:
- 50 updates of 1-5 datoms each
- Subscription: 4.29ms per update
- Naive: 2.81ms per update
- 30% slower despite should being faster!
```

The naive query doesn't use atoms - it builds result directly with persistent data structures and transients.

---

## Problem 4: Inefficient Initial State Seeding

### Location: `src/dfdb/dd/compiler.clj:398-399, 423-425, 430-433`

```clojure
;; Pattern operators
(reset! state (into {} (map (fn [binding] [binding 1]) bindings)))

;; Join operators
(reset! (:left-state join0)
        (into {} (map (fn [b] [b 1]) (nth pattern-bindings 0))))
```

### The Problem:
Initial state is built by:
1. Running naive query for each pattern separately
2. Converting results to binding maps: `{binding → 1, binding → 1, ...}`
3. For 3+ pattern joins: Computing progressive join results
4. All using `into {}` which is less efficient than `persistent!` on transients

### Performance Impact:
- **Compilation time** appears in benchmarks (e.g., 6884ms for social network)
- Building large hashmaps with `into` instead of transients
- Computing intermediate join results that will be immediately used for lookups

### Example from Benchmark:
```
Social Network test:
- Compilation time: 6884.78 ms
- Building initial state for 5000 users × ~20 friends = ~100K edges
- Multiple pattern queries + join result computation
```

---

## Problem 5: No Algorithmic Optimization for Aggregates

### Conceptual Issue:

**Current Design**:
```
Raw Tuples → Materialize → Group → Aggregate
```

**Optimal Design** (for incremental aggregates):
```
Maintain: {group-key → {count, sum, min, max, ...}}

On +delta: update group-key: count++, sum+=value, etc.
On -delta: update group-key: count--, sum-=value, etc.
```

### Why This Matters:
- Count aggregate: Should be O(1) - just increment/decrement counter
- Sum aggregate: Should be O(1) - just add/subtract value
- Current: O(n) where n = total multiplicity of all values

### Evidence:
Aggregation (Count) benchmark - **70% slower than naive**:
- Naive query: Scans all records, counts - O(n)
- Subscription: Should be O(1) per update - just maintain counter
- Actual: O(n) because materializes multiset

---

## Recommendations

### Priority 1: Fix Aggregate Operator (Biggest Impact)
**Goal**: Make aggregates work with multisets directly, not materialized lists

Changes needed in `src/dfdb/dd/aggregate.clj`:
```clojure
;; Instead of:
(mapcat (fn [[value count]] (repeat count value)) (seq coll))

;; Work with frequencies:
(reduce (fn [acc [value mult]]
          (let [group-key (group-fn value)]
            (update acc group-key agg-fn value mult)))
        {}
        coll)
```

Requires new aggregate functions:
```clojure
(defn agg-count-incremental [current-count value multiplicity]
  ((fnil + 0) current-count multiplicity))

(defn agg-sum-incremental [current-sum value multiplicity]
  ((fnil + 0) current-sum (* value multiplicity)))
```

**Expected Impact**: 5-10x speedup for aggregate queries

### Priority 2: Add Hash Index to Join Operator
**Goal**: O(1) lookups instead of O(n) scans

Changes needed in `src/dfdb/dd/multipattern.clj`:
```clojure
;; State structure change:
;; From: {binding → count}
;; To:   {:index {join-key → {binding → count}}
;;        :full {binding → count}}  ; for non-join lookups if needed

;; Lookup:
(when-let [candidates (get-in @right-state [:index join-key])]
  (map (fn [[right-binding right-mult]] ...)
       candidates))
```

**Expected Impact**: 3-5x speedup for multi-pattern joins with large states

### Priority 3: Batch Atom Updates
**Goal**: One atom operation per operator per transaction, not per delta

Changes needed in all operators:
```clojure
;; Instead of:
(doseq [d deltas]
  (swap! state update ...))  ; Multiple swaps

;; Accumulate and swap once:
(let [updates (reduce (fn [acc d] ...) {} deltas)]
  (swap! state merge-with + updates))  ; Single swap
```

Use transients for accumulation:
```clojure
(let [updates (persistent!
                (reduce (fn [acc d]
                          (assoc! acc key (+ (get acc key 0) mult)))
                        (transient {})
                        deltas))]
  (swap! state merge-with + updates))
```

**Expected Impact**: 2-3x speedup for high-delta-count updates

### Priority 4: Use Transients for Initial State
**Goal**: Faster initial state building

Changes in `src/dfdb/dd/compiler.clj`:
```clojure
;; Instead of:
(into {} (map (fn [b] [b 1]) bindings))

;; Use transients:
(persistent!
  (reduce (fn [acc b] (assoc! acc b 1))
          (transient {})
          bindings))
```

**Expected Impact**: 30-50% faster compilation for large initial states

---

## Verification Plan

1. **Fix Aggregate Operator** first - test with:
   - `test-complex-aggregation`
   - `test-aggregation`
   - Should see 5-10x improvement

2. **Add Join Hash Index** - test with:
   - `test-manager-chain`
   - Social network benchmark
   - Should see 3-5x improvement for large states

3. **Batch Atom Updates** - test with:
   - `test-micro-updates`
   - All benchmarks should improve

4. **Profile** after each change to verify improvements

---

## Performance Targets

After fixes, expected benchmark results:

| Test | Current | Target | Fix |
|------|---------|--------|-----|
| Complex Aggregate | 0.6x | 3.0x+ | Priority 1 |
| Aggregation (Count) | 0.3x | 5.0x+ | Priority 1 |
| Join + Aggregate | 0.9x | 2.5x+ | Priority 1 + 2 |
| Filtered Aggregate | 0.6x | 3.0x+ | Priority 1 |
| Hierarchical Join | 1.2x | 2.0x+ | Priority 2 |
| High-Churn Sessions | 0.8x | 2.0x+ | Priority 3 |
| Micro Updates | 0.7x | 2.0x+ | Priority 3 |

All aggregate tests should be **significantly faster** than naive queries since they maintain running totals.
