# Phase 3: Differential Dataflow Implementation - Plan

**Status**: Phase 1 & 2 Complete (100%) ✅
**Next**: Phase 3 - Differential Dataflow Engine

---

## 🎯 What We've Completed

✅ **Phase 1**: Core EAV storage, transactions, time-travel queries
✅ **Phase 2**: Multi-dimensional time, dimension metadata
✅ **Phase 3** (original): Datalog query compilation and execution

**Achievement**: 160/160 tests (100%) - All core features working

---

## 🚀 What's Next: Differential Dataflow (New Phase 3)

Based on original plan phases 4-6, we need to implement:

### Phase 3A: Differential Dataflow Engine (~1,500 LOC)

**Goal**: Implement Clojure-idiomatic differential dataflow for incremental computation

#### Core Components

1. **Multisets & Differences** (~200 LOC)
   ```clojure
   ;; Multiset: collection with multiplicity
   (deftype Multiset [values])  ; {value -> count}

   ;; Difference: change to multiset
   (deftype Difference [additions retractions])
   ;; additions: {value -> +count}
   ;; retractions: {value -> -count}
   ```

2. **Multi-Dimensional Timestamps** (~100 LOC)
   ```clojure
   ;; Timestamp with multiple time dimensions
   (deftype Timestamp [dimensions])  ; {:time/system t1, :time/shipped t2}

   ;; Partial ordering for multi-dimensional time
   (defn timestamp<= [t1 t2]
     ;; t1 <= t2 iff all present dimensions satisfy di <= dj
     ...)
   ```

3. **Collections with Time** (~150 LOC)
   ```clojure
   ;; Timestamped multiset
   (deftype Collection [data])  ; {timestamp -> multiset}

   ;; Track data with time and multiplicity
   ```

4. **DD Operators as Protocols** (~400 LOC)
   ```clojure
   (defprotocol Operator
     (input [this collection])   ; Send data to operator
     (output [this])             ; Get results
     (step [this])              ; Advance computation
     (frontier [this]))         ; Current progress timestamp

   ;; Implement: MapOperator, FilterOperator, JoinOperator, etc.
   ```

5. **Arrangements for Joins** (~300 LOC)
   ```clojure
   (deftype Arrangement [index])  ; Indexed data for efficient joins

   ;; Maintain sorted indices for join lookups
   ```

6. **Datalog → DD Compilation** (~350 LOC)
   ```clojure
   ;; Translate Datalog query to DD operator graph
   (defn compile-query [query]
     ;; [:find ?x :where [?e :attr ?x]]
     ;; →
     ;; (-> (scan-aevt :attr)
     ;;     (map extract-value)
     ;;     (distinct)))
   ```

### Phase 3B: Subscription System (~500 LOC)

**Goal**: Wire DD engine to deliver incremental updates

1. **Subscription Manager** (~200 LOC)
   ```clojure
   (defn subscribe [db config]
     {:query '[:find ?x :where ...]
      :mode :incremental
      :callback (fn [diff] ...)
      :watch-dimensions [:time/system :time/shipped]
      :backpressure :block})
   ```

2. **Query Result Sharing** (~150 LOC)
   - Multiple subscribers to same query share computation
   - Subscription multiplexing

3. **Delivery Mechanisms** (~150 LOC)
   - Callbacks
   - core.async channels
   - Manifold streams

### Phase 3C: Integration (~200 LOC)

1. **Wire DD to Transaction Processing**
   - When transaction commits, feed deltas to DD operators
   - Propagate changes through computation graph
   - Deliver diffs to subscribers

2. **Backpressure Handling**
   - Block transactions if subscribers can't keep up
   - Configurable per-subscription

---

## 📋 Test-Driven Approach

**We already have 11 comprehensive subscription tests** in `usecase_subscriptions_test.clj`:

1. ✅ test-subscription-basic-updates
2. ✅ test-subscription-with-filter
3. ✅ test-subscription-aggregation-updates
4. ✅ test-subscription-recursive-query
5. ✅ test-subscription-multi-dimensional-time
6. ✅ test-subscription-with-transformation
7. ✅ test-subscription-backpressure
8. ✅ test-subscription-multiple-subscribers-same-query
9. ✅ test-subscription-core-async
10. ✅ test-subscription-dashboard-materialized-view
11. ✅ test-subscription-event-sourcing

**Approach**: Make these 11 tests pass, one by one

---

## 🎯 Phase 3 Goals

### Minimum Viable (Core DD)
- [ ] Multisets and differences
- [ ] Basic DD operators (map, filter, distinct)
- [ ] Simple subscriptions (callbacks)
- [ ] Incremental updates for basic queries

**Estimated**: ~800 LOC, Tests 1-3 passing

### Full Featured
- [ ] All DD operators (join, group, aggregate)
- [ ] Arrangements for efficient joins
- [ ] Multi-dimensional timestamp lattices
- [ ] All delivery mechanisms
- [ ] Subscription multiplexing
- [ ] Backpressure handling

**Estimated**: ~2,000 LOC, All 11 tests passing

---

## 📊 Expected Outcomes

### After Phase 3A (DD Engine)
- Datalog queries compile to DD operators
- Incremental computation on updates
- O(changes) complexity instead of O(data)

### After Phase 3B (Subscriptions)
- Real-time query subscriptions
- Incremental diff delivery
- Multiple subscribers share computation
- Materialized views update automatically

### After Phase 3C (Integration)
- Complete event-sourcing support
- Reactive UI capabilities
- Real-time analytics dashboards
- O(1) per-transaction updates for aggregations

---

## 🔧 Technical Approach

### Clojure-Idiomatic DD

**Not**: FFI to Rust's differential-dataflow
**Instead**: Adapt DD principles to Clojure

```clojure
;; Use Clojure's strengths:
;; - Immutable persistent data structures
;; - Protocols for operator abstraction
;; - Atoms/refs for mutable state (arrangements)
;; - core.async for dataflow coordination
;; - Transducers for operator composition
```

### Key Data Structures

```clojure
;; Multiset using Clojure map
{value1 3    ; appears 3 times
 value2 -1}  ; retracted once

;; Difference as two maps
{:additions {value1 2}
 :retractions {value2 1}}

;; Arrangement using sorted-map (already have from Phase 1!)
(deftype Arrangement [index])  ; Reuse our index infrastructure
```

---

## 💡 Why This is Tractable

### We Have Strong Foundations

1. ✅ **Index infrastructure** - Can reuse for arrangements
2. ✅ **Sorted-map experience** - Already built heterogeneous comparator
3. ✅ **Query compilation** - Already parse Datalog, just need DD translation
4. ✅ **Multi-dimensional time** - Lattice support foundation exists
5. ✅ **Delta tracking** - Transaction deltas feed DD operators

### Clear Specifications

- ✅ 11 subscription tests define exact behavior
- ✅ FlowLog paper provides DD on Datalog reference
- ✅ Requirements document specifies architecture

### Incremental Approach

Start simple:
1. Basic multisets/differences
2. Simple operators (map, filter)
3. Basic subscriptions (callbacks only)
4. One test at a time

Then build up:
- Add join operators
- Add aggregation operators
- Add other delivery mechanisms
- Optimize with arrangements

---

## 📅 Recommended Next Steps

### Option A: Start Phase 3 Now
**Timeline**: 2-3 weeks for full implementation
**Approach**: TDD with 11 existing tests
**Outcome**: Complete differential dataflow database

### Option B: Production Deployment
**Timeline**: Immediate
**Approach**: Deploy current 100% tested implementation
**Outcome**: Gather real-world usage patterns, then implement Phase 3

### Option C: Optimize & Harden
**Timeline**: 1 week
**Approach**:
- Add persistence (RocksDB/LMDB backend)
- Performance optimization
- Production monitoring
- Then Phase 3

---

## 🎯 Recommendation

**Start Phase 3 now** because:

1. ✅ **Momentum** - We're in flow, codebase is fresh
2. ✅ **Tests ready** - 11 specs define exactly what to build
3. ✅ **Foundation solid** - 100% tested base to build on
4. ✅ **Clear path** - FlowLog + our infrastructure = achievable
5. ✅ **High value** - Subscriptions unlock reactive use cases

**Approach**:
```
1. Start with test-subscription-basic-updates
2. Implement minimal DD engine to make it pass
3. Move to next test
4. Iterate until all 11 pass
5. Estimated: 1-2 weeks with focused effort
```

---

## 📖 Resources

**We Have**:
- ✅ 11 subscription test specs
- ✅ FlowLog-VLDB paper (DD on Datalog)
- ✅ Differential Dataflow paper
- ✅ Our own requirements (6,500 lines)
- ✅ Working query engine to build on

**Next**: Implement multisets → operators → subscriptions

---

**READY TO START PHASE 3?**

The foundation is perfect (100%). The path is clear. The tests are ready.

Let's build differential dataflow! 🚀
