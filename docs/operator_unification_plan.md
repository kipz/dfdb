# Operator Framework Unification Plan

## Current Problem: Hybrid System

We have TWO different operator protocols operating side-by-side:

### 1. DeltaOperator (in `incremental_core.clj`)
```clojure
(defprotocol DeltaOperator
  (process-delta [this delta]
    "Process a delta and return output deltas."))

;; Delta format: {:binding {...} :mult +1/-1}
;; Returns: sequence of output deltas
```

**Used by:**
- `PatternOperator` - pattern matching
- `ProjectOperator` - variable projection
- `PredicateFilter` - filtering
- `CollectResults` - terminal collection
- `IncrementalJoinOperator` - joins (✅ FAST!)

**Characteristics:**
- Process ONE delta at a time
- Return sequence of output deltas
- True differential dataflow semantics
- Granular, composable

### 2. Operator (in `operator.clj`)
```clojure
(defprotocol Operator
  (input [this collection timestamp]
    "Send input collection at given timestamp to operator.")
  (step [this] ...)
  (output [this] ...)
  (frontier [this] ...))

;; Collection format: multiset {value → count}
```

**Used by:**
- `MapOperator` - transformations
- `FilterOperator` - filtering
- `DistinctOperator` - deduplication
- `CollectOperator` - terminal collection
- `AggregateOperator` - aggregation (⚠️ SLOW)
- `GroupOperator` - grouping

**Characteristics:**
- Process ENTIRE collection at once
- Batch-oriented
- Timestamp-based
- More stateful

## The Problem

**Join+Aggregate boundary is broken** because:
1. Joins emit deltas: `[{:binding {...} :mult 1} {:binding {...} :mult 1} ...]`
2. Aggregates expect collections: `{value → count}`
3. The conversion between them is ad-hoc and buggy

**Example failure:**
```clojure
;; Join pipeline emits deltas → CollectResults accumulates
;; Then we try to feed to AggregateOperator via op/input
;; But the multiset format might not be right, initialization fails
```

## Solution: Unify on DeltaOperator

**Proposal**: Convert all operators to use `DeltaOperator` protocol.

### Why DeltaOperator?

1. **True differential dataflow**: Processes changes incrementally
2. **Already fast**: Joins use this and are 2-6x faster
3. **Composable**: Deltas chain naturally
4. **Simpler**: No timestamp management, no step/frontier complexity
5. **Granular**: Can batch for efficiency when needed

### Migration Plan

#### Phase 1: Convert Collection Operators to Delta-based

**AggregateOperator** (priority 1 - fixes join+aggregate):
```clojure
;; Current:
(input [this coll timestamp]
  ;; Process entire multiset at once
  ...)

;; New:
(process-delta [this delta]
  ;; Incrementally update aggregates
  ;; delta: {:binding [group-key value...] :mult +1/-1}
  ;; Update aggregate state: (swap! state update group-key agg-fn delta)
  ...)
```

**Benefits**:
- True incremental aggregates (update running totals, not recompute)
- Fixes join+aggregate boundary
- Should be MUCH faster (O(1) per delta vs O(n) recompute)

**GroupOperator**:
```clojure
(process-delta [this delta]
  ;; Update group membership incrementally
  ...)
```

#### Phase 2: Remove/Deprecate old Operator protocol

Since most operators are already delta-based, and we're converting aggregates:
- Keep `operator.clj` operators (Map, Filter, Distinct) for backwards compatibility
- But prefer delta-based versions
- Eventually phase out collection-based operators

#### Phase 3: Consolidate CollectResults/CollectOperator

We have TWO terminal collectors:
- `CollectResults` (DeltaOperator) - used by queries
- `CollectOperator` (Operator) - used by aggregates

**Solution**: Keep only `CollectResults`, it's simpler and works.

## Implementation Strategy

### Step 1: Create `IncrementalAggregateOperator`

```clojure
(defrecord IncrementalAggregateOperator [group-fn agg-fn state]
  DeltaOperator
  (process-delta [_this delta]
    (let [binding (:binding delta)
          mult (:mult delta)
          group-key (group-fn binding)
          value (value-fn binding)]  ; Extract aggregated value

      ;; Update aggregate incrementally
      (swap! state update-in [:aggregates group-key]
             (fn [current-agg]
               (agg-fn current-agg value mult)))

      ;; Return delta for this group's new aggregate value
      [(make-delta [group-key @(get-in @state [:aggregates group-key])] mult)])))
```

**Key insight**: Aggregates become stateful delta transformers:
- Input delta: `{:binding [?type ?amount] :mult +1}`
- Group by: `?type`
- Aggregate: `(sum ?amount)`
- State: `{:aggregates {type → running-sum}}`
- Output delta: `{:binding [type new-sum] :mult +1}` and `{:binding [type old-sum] :mult -1}`

### Step 2: Update compiler to use delta-based aggregates

```clojure
;; Replace:
(agg/make-aggregate-operator group-fn agg-fn downstream)

;; With:
(agg/make-incremental-aggregate-operator group-fn agg-fn)
```

### Step 3: Chain deltas through pipeline

```clojure
;; Join produces deltas → Project → Aggregate (delta-based) → CollectResults
(doseq [delta join-output]
  (let [agg-deltas (process-delta agg-op delta)]
    (doseq [agg-delta agg-deltas]
      (process-delta collect-op agg-delta))))
```

## Expected Benefits

1. **Fix join+aggregate bug**: No more boundary mismatch
2. **Faster aggregates**: O(1) incremental updates vs O(n) recomputation
3. **Simpler code**: One operator model, not two
4. **Better composability**: All operators speak the same language
5. **True differential dataflow**: Every operator processes deltas

## Migration Checklist

- [ ] Create `IncrementalAggregateOperator` with `DeltaOperator` interface
- [ ] Update aggregate functions to work incrementally (sum, count, avg, min, max)
- [ ] Update compiler to use delta-based aggregates
- [ ] Test join+aggregate queries
- [ ] Convert `GroupOperator` to delta-based
- [ ] Remove old `AggregateOperator` (collection-based)
- [ ] Update documentation
- [ ] Run full test suite

## Timeline

- **Step 1-2**: 2-3 hours (create incremental aggregate operator)
- **Step 3**: 1 hour (update compiler)
- **Testing**: 1 hour
- **Total**: 4-5 hours

This unification should not only fix the bug but make aggregates significantly faster.
