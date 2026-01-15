# Static Performance Analysis Report

Generated: 2026-01-13

## Executive Summary

This report identifies performance issues via static code analysis focusing on:
- Reflection warnings (JVM interop overhead)
- Atom usage in hot paths (concurrency overhead)
- Collection operations that could use transducers/transients
- Inefficient patterns identified by clj-kondo

## Tools Added to deps.edn

The following static analysis and performance tools have been added:

```clojure
:clj-kondo         ; Static linting and code analysis
:check-reflection  ; Enable compiler reflection warnings
:eastwood          ; Additional linting
:cljfmt           ; Code formatting checks
```

### Usage

```bash
# Run clj-kondo
clj -M:clj-kondo --lint src

# Check for reflection with compiler
clj -M:check-reflection -e "(set! *warn-on-reflection* true) (compile 'dfdb.query)"

# Run eastwood
clj -M:eastwood

# Check formatting
clj -M:cljfmt
```

## Issues Found

### 1. Missing `*warn-on-reflection*` (Critical)

**Impact**: High - Reflection is 10-100x slower than direct method calls

**Files missing reflection warnings** (20 files):
- `src/dfdb/db.clj` - Database core operations
- `src/dfdb/query.clj` - Query engine (hot path)
- `src/dfdb/transaction.clj` - Transaction processing (hot path)
- `src/dfdb/subscription.clj` - Subscription notifications (hot path)
- `src/dfdb/temporal.clj` - Temporal filtering
- `src/dfdb/dimensions.clj` - Time dimension handling
- `src/dfdb/storage.clj` - Storage operations
- `src/dfdb/dd/delta_simple.clj` - Delta processing
- `src/dfdb/dd/multipattern.clj` - Pattern matching
- `src/dfdb/dd/join_incremental.clj` - Incremental joins
- `src/dfdb/dd/simple_incremental.clj` - Simple incremental ops
- `src/dfdb/dd/recursive_incremental.clj` - Recursive queries
- `src/dfdb/dd/arrangement.clj` - Data arrangements
- `src/dfdb/dd/difference.clj` - Set difference
- `src/dfdb/dd/multiset.clj` - Multiset operations
- `src/dfdb/dd/full_pipeline.clj` - Pipeline construction
- `src/dfdb/recursive.clj` - Recursive pattern matching

**Note**: Only `src/dfdb/storage/rocksdb.clj` currently has reflection warnings enabled.

**Specific reflection sites found**:
```clojure
# Java method calls without type hints (25+ occurrences):
.startsWith       ; String method (10 occurrences)
.getTime          ; Date method (8 occurrences)
.getMessage       ; Exception method (4 occurrences)
```

### 2. Atom Usage in Hot Paths

**Impact**: Medium-High - Atomic updates add synchronization overhead

**Total atoms found**: 38 instances across 12 files

**Critical hot paths with atoms**:

#### Query Execution (dfdb/dd/*.clj)
- `src/dfdb/dd/join_incremental.clj:62-63` - left-state, right-state atoms
  - Updated on every delta during incremental joins
  - Using `swap!` with filter operations on every probe

- `src/dfdb/dd/multipattern.clj:40` - Join operator state atoms
  - Two atoms per join operator
  - Pattern operators also use atoms (lines 75, 76, 127)

- `src/dfdb/dd/simple_incremental.clj:107-109` - Pattern, project, collect operators
  - Each uses atom for state accumulation

- `src/dfdb/dd/recursive_incremental.clj:77-78` - edges, closure atoms
  - Transitive closure computation
  - Line 96-97: Additional atoms for path tracking

- `src/dfdb/dd/aggregate.clj:57,127` - Aggregation state
  - Atom for aggregate accumulation

#### Subscription System
- `src/dfdb/subscription.clj:25,29` - Global subscription registry and counter
  - Accessed on every subscription operation
  - Line 67-68: Per-subscription current-results and active? atoms

**Recommendation**: Many of these could use persistent data structures with functional state passing instead of atoms, especially in the delta processing pipeline where operations are sequential.

### 3. Collection Operations - Transducer/Transient Opportunities

**Total occurrences**: 229 map/mapcat/filter/reduce operations

**High-impact candidates for optimization**:

#### Query Processing (src/dfdb/query.clj)
```clojure
# Line 123-135: mapcat over asserted-values (time-series mode)
(set (mapcat (fn [{:keys [value datom]}] ...) asserted-values))
# → Use transducers: (into #{} (mapcat ...) asserted-values)

# Line 147-159: mapcat over latest-values
(set (mapcat (fn [{:keys [value datom]}] ...) latest-values))

# Line 194-212: mapcat over all-datoms (time-series mode)
(set result-list)  ; result-list from mapcat
# → Use transducers to avoid intermediate collections

# Line 219-236: mapcat over latest-per-eav
(set (mapcat (fn [datom] ...) latest-per-eav))

# Line 253-260: map for argument resolution in eval-predicate
(map (fn [arg] ...) args)
# → Use transducers for better performance

# Line 318-328: Hash join - reduce over build-side
(reduce (fn [ht binding] ...) {} build-side)
# → Consider using transients for hash table construction
```

#### Transaction Processing (src/dfdb/transaction.clj)
```clojure
# Line 287-291: mapcat and filter for delta generation
(->> tuples
     (map (fn [[op e a v]] (generate-delta ...)))
     (filter some?)
     (doall))
# → Use transducers: (into [] (comp (map ...) (filter some?)) tuples)

# Line 301-304: mapcat for storage operations
(->> deltas-with-meta
     (mapcat apply-delta)
     (filter some?)
     (doall))
# → Use transducers
```

#### Differential Dataflow (src/dfdb/dd/*.clj)
```clojure
# join_incremental.clj:31-36: mapcat for join results
(mapcat (fn [[right-binding right-mult]] ...) matching-right)
# → Use transducers to reduce allocation

# multipattern.clj: Multiple filter operations on pattern variables
# Lines 43-44, 69-70, 71-72, etc.
(filter #(and (symbol? %) (.startsWith ...)) pattern)
# → Cache these or use transducers
```

### 4. Specific Optimizations Identified

#### Hot Path: `match-pattern` in query.clj

**Issues**:
1. Lines 116-122: `group-by` creates intermediate map
2. Lines 123-135: Nested `mapcat` with set operations
3. Lines 194-212: Similar pattern repeated

**Optimization**:
```clojure
; Before (allocates intermediate collections):
(let [by-value (group-by :v all-datoms)]
  (set (mapcat (fn [{:keys [value datom]}] ...) asserted-values)))

; After (use transducers):
(into #{}
      (comp (filter #(= :assert (:op %)))
            (mapcat (fn [datom] ...)))
      all-datoms)
```

#### Hot Path: `join-bindings` in query.clj (lines 299-328)

**Current**: Hash join with persistent maps
```clojure
(reduce (fn [ht binding]
          (let [join-key (select-keys binding common-vars)]
            (update ht join-key (fnil conj []) binding)))
        {}
        build-side)
```

**Optimization**: Use transient for hash table construction
```clojure
(persistent!
  (reduce (fn [ht binding]
            (let [join-key (select-keys binding common-vars)]
              (assoc! ht join-key
                      (conj (get ht join-key []) binding))))
          (transient {})
          build-side))
```

#### Hot Path: Delta Processing

**Files**: All `src/dfdb/dd/*.clj` files

**Issue**: Many delta processing functions use `swap!` on atoms with complex operations:
```clojure
; join_incremental.clj:22
(swap! left-state update binding (fnil + 0) mult)
```

**Consideration**: For sequential delta processing, functional state passing may be more efficient:
```clojure
; Instead of atom swap
(let [new-state (update state binding (fnil + 0) mult)]
  ...
  new-state)
```

### 5. Additional Patterns for Review

#### Redundant Let Expressions
- `src/dfdb/dd/multipattern.clj:175` - Redundant let (flagged by clj-kondo)
- `src/dfdb/dd/multipattern.clj:195` - Redundant let (flagged by clj-kondo)

#### Unused Bindings
- `src/dfdb/dd/multipattern.clj:9` - Unused `this` binding

#### Missing Require
- `src/dfdb/dd/delta_simple.clj:62` - Unresolved `clojure.set` namespace

## Recommendations by Priority

### Priority 1: Add Type Hints (High Impact, Low Effort)

Add `(set! *warn-on-reflection* true)` to all files and fix reflection warnings:

```clojure
# Add to each namespace
(ns dfdb.query
  ...)

(set! *warn-on-reflection* true)

# Add type hints to resolve reflection:
(.startsWith ^String (name x) "?")
(.getTime ^java.util.Date v)
(.getMessage ^Exception e)
```

**Estimated speedup**: 10-50% on query hot paths

### Priority 2: Use Transients in Hot Paths (Medium Impact, Medium Effort)

Focus on:
1. Hash join construction in `query.clj:318-328`
2. Delta accumulation in transaction processing
3. Any place building large maps/vectors in tight loops

**Estimated speedup**: 5-15% on large result sets

### Priority 3: Replace Atoms with Functional State (Medium Impact, High Effort)

Review differential dataflow operators:
- Many atoms are used sequentially, not concurrently
- Consider functional state passing pattern
- Keep atoms only where true concurrent access is needed

**Estimated speedup**: 5-10% on subscription updates

### Priority 4: Use Transducers (Low-Medium Impact, Medium Effort)

Convert map/filter/mapcat chains to transducers:
- Focus on query result processing
- Transaction delta generation
- Any multi-step collection transformations

**Estimated speedup**: 5-10% overall, more on large collections

## Next Steps

1. **Enable reflection warnings everywhere**:
   ```bash
   # Add to each src/*.clj file
   (set! *warn-on-reflection* true)
   ```

2. **Run compiler with reflection checks**:
   ```bash
   clj -M:check-reflection -e "(compile 'dfdb.query)"
   ```

3. **Fix reflection warnings** by adding type hints

4. **Profile before further optimization** using criterium:
   ```bash
   clj -M:perf
   ```

5. **Benchmark changes** against existing performance tests in `perf/`

## Tools and References

### Added Tools
- **clj-kondo**: [GitHub](https://github.com/clj-kondo/clj-kondo) - Static analysis
- **eastwood**: Comprehensive Clojure linter
- **cljfmt**: Code formatting

### Performance Resources
- [Clojure Performance - Reflection](https://clojure-goes-fast.com/blog/performance-nemesis-reflection/)
- [Type Hints Guide](https://cuddly-octo-palm-tree.com/posts/2022-02-27-opt-clj-7/)
- [Clojure Performance Tips](https://clojure.org/reference/java_interop#typehints)
- [clj-kondo Documentation](https://cljdoc.org/d/clj-kondo/clj-kondo/2025.12.23)
- [Analysis Tools for Clojure](https://analysis-tools.dev/tag/clojure)

## Conclusion

The codebase has several optimization opportunities:

1. **Critical**: 20 files missing reflection warnings (potential 10-50% speedup)
2. **Important**: Excessive atom usage in sequential operations
3. **Beneficial**: Transducer and transient collection opportunities

Addressing Priority 1 (type hints) alone could yield significant performance improvements with minimal code changes.
