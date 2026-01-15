# Join & Aggregate Performance: Surprising Findings

**Date:** 2026-01-13
**Hypothesis:** Joins and aggregates would show maximum advantage for incremental computation
**Result:** **Hypothesis REJECTED** - Naive queries with hash joins outperform subscriptions!

---

## Summary Results

| Query Type | Compilation | Sub Latency | Naive Latency | Speedup | Winner |
|------------|-------------|-------------|---------------|---------|--------|
| 3-Way Join | 622ms | 5.77ms | 3.05ms | **0.5x** | Naive (2x faster) |
| Self-Join | 304ms | 7.07ms | 2.11ms | **0.3x** | Naive (3.3x faster) |
| Simple Aggregate | 7ms | 11.24ms | 1.60ms | **0.1x** | Naive (7x faster) |
| Join + Aggregate | 157ms | 17.54ms | 9.81ms | **0.6x** | Naive (1.8x faster) |

**Average:** Naive queries are **3-4x faster** than subscriptions for these workloads!

---

## Why This Happened

### 1. Hash Joins Made Naive Queries Extremely Fast

After implementing hash joins:
- 3-way join: **3.05ms** for full re-execution
- Join + aggregate: **9.81ms** for full re-execution

These are **so fast** that the incremental overhead isn't worth it!

### 2. Subscription Overhead is Significant

**Compilation costs:**
- 3-way join: 622ms
- 4-way join: 26,056ms (26 seconds!)

**Per-update overhead:**
- Delta generation and conversion
- Join operator state management
- Multiplicity tracking
- Result collection and diffing

### 3. Small Update Counts

With only 20-100 updates:
- Can't amortize 622ms compilation over 20 updates = +31ms per update
- Overhead dominates for fast queries

### 4. Small Result Sets

Most of these queries return small result sets (<100 results), so:
- Hash join on small sets is very fast
- Incremental state management overhead exceeds benefit

---

## The Real Crossover Point

### Subscriptions Win When:

1. **Very large result sets** (>1,000 results per query)
   - Example: 2-way friend-of-friend with 500 users → 3.1x speedup
   - Larger join intermediate states benefit from incrementality

2. **Many updates** (>1,000 updates)
   - Amortizes compilation cost
   - Example: 622ms / 1000 updates = 0.6ms overhead (negligible)

3. **Slow naive queries** (>100ms per query)
   - Incremental updates still fast even with overhead
   - Example: Before hash joins, subscriptions were 600x faster

### Naive Queries Win When:

1. ✅ **Fast hash joins** (<10ms per query)
   - Our optimizations made this the common case!
   - 3ms for 3-way join is hard to beat

2. ✅ **Small result sets** (<100 results)
   - Less state to manage
   - Projection and collection are trivial

3. ✅ **Few updates** (<100)
   - Can't amortize compilation cost
   - Better to just re-execute

4. ✅ **Aggregates with small groups**
   - Incremental aggregation overhead > benefit
   - Re-computing sum of 10 values is instant

---

## Detailed Analysis

### 3-Way Join: Why Naive Wins

**Query:** `[?a :conn ?b] [?b :conn ?c] [?c :conn ?d]`
**Data:** 50 nodes, ~75 connections

**Naive execution (3.05ms):**
1. Scan :conn attribute: ~75 datoms (instant)
2. Hash join #1: 75 × 75 with hash table (~1ms)
3. Hash join #2: Result × 75 with hash table (~1ms)
4. Project and return (~1ms)

**Subscription execution (5.77ms):**
1. Receive delta: `{?a X, ?b Y}`
2. Update left-state atom (synchronized)
3. Probe right-state for matches
4. Compute multiplicities
5. Update join-state atom
6. Repeat for right-side delta
7. Project results (with set expansion)
8. Collect and diff
9. Invoke callback

**Overhead sources:**
- Atom updates (synchronized writes)
- Multiplicity arithmetic
- Set expansion in projection
- Result diff computation
- State management for multiple operators

**Conclusion:** For small, fast queries, the overhead exceeds the benefit.

### Why Original Benchmarks Showed Subscriptions Winning

**Friend-of-Friend (2-way join):**
- 500 users → ~4,200 bindings per pattern
- Hash join: 4,200 × 4,200 lookups = significant cost
- Result set: ~500 results (large)
- Compilation: 48ms (small relative to query cost)

**With larger scale:**
- Naive query: 39-83ms (non-trivial cost)
- Subscription: 12-16ms (clear win)
- Speedup: 3.1x

**Key difference:** Result set size and query cost!

---

## The Hash Join Paradox

By optimizing naive queries with hash joins, we made them **so fast** that subscriptions can't beat them on small queries!

**Before hash joins:**
- Naive: 9,435ms (nested loops)
- Subscriptions: 10ms
- Speedup: 943x ← Subscriptions clearly essential

**After hash joins:**
- Naive: 3-40ms (hash joins)
- Subscriptions: 6-20ms (overhead)
- Speedup: 0.3-3.1x ← Depends on query!

**Conclusion:** Our optimization success made subscriptions less universally beneficial.

---

## Revised Recommendations

### Use Subscriptions When:

1. **Large-scale multi-pattern joins** (500+ entities, >2 patterns)
   - Friend-of-friend with 500 users: 3.1x faster
   - Complexity and scale matter

2. **High update frequency** (>1,000 updates expected)
   - Amortizes compilation cost
   - Incremental updates cheaper in aggregate

3. **Real-time requirements** (need instant updates)
   - Callback-based delivery
   - No polling needed

4. **Naive queries are slow** (>50ms per query)
   - Even with overhead, subscriptions faster
   - More room for improvement

### Use Naive Queries When:

1. ✅ **Small queries** (<10ms with hash joins)
   - 3-way joins on small data: Naive 2x faster
   - Overhead not worth it

2. ✅ **Few updates** (<100 total)
   - Can't amortize compilation
   - Simple re-execution cheaper

3. ✅ **Small result sets** (<50 results)
   - Little state to maintain
   - Incremental overhead dominates

4. ✅ **Simple aggregates** (count/sum over <1,000 items)
   - Re-computing is instant
   - Naive 7-10x faster

---

## Performance Data

### Compilation Costs

| Patterns | Compilation Time |
|----------|-----------------|
| 2-way join | 48-300ms |
| 3-way join | 500-1,000ms |
| 4-way join | 26,000ms (26 sec!) |

**Implication:** Compilation cost grows dramatically with join depth. Only worth it if query will run many times.

### Per-Update Latency

| Query | Subscription | Naive | Advantage |
|-------|--------------|-------|-----------|
| 2-way (500 users) | 12-16ms | 39-83ms | Sub 3x faster |
| 2-way (50 users) | 6ms | 3ms | Naive 2x faster |
| 3-way (50 nodes) | 5.77ms | 3.05ms | Naive 2x faster |
| Self-join (100 nodes) | 7.07ms | 2.11ms | Naive 3.3x faster |
| Simple aggregate | 11.24ms | 1.60ms | Naive 7x faster |

**Pattern:** Subscriptions need **scale** (data size and updates) to win.

---

## Key Insight: Scale Matters More Than Complexity

Initial hypothesis: **Complexity** (number of patterns) determines winner
**Reality:** **Scale** (data size, result set size, update count) determines winner

**Small scale (50-100 entities):**
- Even 3-4 way joins: Naive wins
- Hash joins are so fast that overhead dominates

**Medium scale (500 entities):**
- 2-way joins: Subscriptions win 3x
- Breaking even point for complexity vs overhead

**Large scale (5,000+ entities):**
- Subscriptions should dominate
- Need to test to confirm

---

## Implications for DFDB

### 1. Adaptive Query Routing Needed

DFDB should automatically choose between naive and incremental based on:
- **Estimated query cost** (from statistics)
- **Expected update count** (user hint or learned)
- **Result set size** (sampled or estimated)

### 2. Lazy Compilation

Don't compile DD graph until:
- Update #10 or #100 (configurable threshold)
- Query cost exceeds threshold (e.g., >50ms)
- User explicitly requests incremental mode

### 3. Compilation Optimization

26 seconds for 4-way join compilation is prohibitive. Need to:
- Cache compiled operator graphs
- Optimize join operator construction
- Consider JIT-style progressive optimization

### 4. Hash Joins are Critical

The 113x speedup from hash joins is **essential**. Without them:
- Naive queries: unusable
- Subscriptions: only option
- False sense of subscription superiority

With hash joins:
- Naive queries: viable for most workloads
- Subscriptions: beneficial only at scale
- True performance characteristics revealed

---

## Conclusion

**Your hypothesis was partially correct:** Joins and aggregates DO benefit from incremental computation, **but only at sufficient scale**.

**The surprise:** Hash joins made naive queries SO fast that subscriptions can't beat them on small data.

**The lesson:**
- Optimize your baseline first (hash joins)
- Then measure if incremental is worth it
- Don't assume complexity → incremental advantage
- Scale (data size + update count) matters more than query complexity

**For DFDB:**
- Keep both approaches
- Make hash joins the default optimization
- Use subscriptions selectively for:
  - Large-scale data (500+ entities)
  - High update frequency (>1000 updates)
  - Real-time delivery requirements

**Performance hierarchy:**
1. Naive with hash joins on small data: **FASTEST** (1-10ms)
2. Subscriptions on large data: **FAST** (10-20ms)
3. Naive without hash joins: **SLOW** (100-9000ms)
4. No viable option before optimizations: **UNUSABLE**

---

## Test Results (Detailed)

All tests used small-medium scale data (50-200 entities, 20-100 updates).

**Results match:** ✅ All correctness validations passed (except aggregate edge case)

**Winner:** Naive queries consistently faster by 2-10x

**Reason:** Hash joins + small scale = overhead dominates incremental benefit

**Next steps:** Need to test at **larger scale** (1,000-10,000 entities) to find where subscriptions regain advantage.

