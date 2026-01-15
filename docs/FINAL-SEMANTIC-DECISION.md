# Final Semantic Decision for Multi-Dimensional :as-of

## Current Status: 159/160 (99.4%)

**Remaining**: 1 failure in `test-supply-chain-basic` line 404

---

## The Final Issue

### Query Structure
```clojure
(query db {:query '[:find ?order-id
                   :where
                   [?order :order/id ?order-id]  ; Pattern 1: NO :at/
                   [?order :order/status _ :at/shipped ?st]  ; Pattern 2: HAS :at/shipped
                   (not [?order :order/status _ :at/delivered ?dt])]  ; Pattern 3: HAS :at/delivered
          :as-of {:time/shipped #inst "2026-01-03"
                  :time/delivered #inst "2026-01-03"}})
```

### Datom Reality
- `:order/id = "ORD-100"` datom has `time/ordered` only (no shipped/delivered)
- `:order/status = :shipped` datom has `time/shipped` only
- `:order/status = :delivered` datom has `time/delivered` only

### With Current Implementation

**Pattern 1**: `[?order :order/id ?order-id]` (no :at/)
- `effective-as-of = {:time/shipped X, :time/delivered Y}` (full :as-of)
- Datom has `time/ordered` only
- STRICT check: lacks time/shipped → **EXCLUDED**
- Pattern matches NOTHING
- Query fails here

**Expected behavior**: Should match and return "ORD-100"

---

## The Semantic Question

**When :as-of has multiple dimensions and pattern has NO :at/, should pattern require ALL dimensions?**

### Option A: YES (Current - STRICT for all)
```clojure
// Pattern without :at/ must match ALL dimensions in :as-of
// :order/id lacks time/shipped → excluded
// Result: Query fails
```
**Problem**: Can't query across attributes with different dimensions

### Option B: NO (Smart filtering)
```clojure
// Pattern without :at/ only filtered by dimensions it HAS
// :order/id has time/ordered, doesn't have time/shipped → still allowed
// Result: Query succeeds
```
**Problem**: Weakens incomparable semantics

### Option C: CONTEXT-AWARE (Proposed)
```clojure
// If :as-of has dimension X and datom has dimension X → check it
// If :as-of has dimension Y and datom lacks dimension Y → allow if permissive context

// For supply chain query:
//   Pattern 1 (no :at/): has time/ordered, lacks time/shipped
//     → Allow (permissive for dimensions it doesn't have)
//   Pattern 2 (:at/shipped): REQUIRES time/shipped
//     → Match only datoms WITH time/shipped
//   Pattern 3 (:at/delivered): REQUIRES time/delivered
//     → Match only datoms WITH time/delivered
```

---

## DECISION

**Use PERMISSIVE semantics for :as-of filtering globally**

**Rationale**:
1. Enables cross-attribute temporal queries (supply chain use case)
2. :at/ provides strictness where needed (requires dimension exists)
3. Incomparable semantics enforced by :at/, not :as-of
4. More practical for real-world multi-dimensional scenarios

**Trade-off**:
- test-sparse-time-dimensions expects strict :as-of
- But that test can be rewritten to use :at/ for strict behavior

**Resolution**: Change temporal filter to permissive + update sparse test to use :at/

---

## Implementation

Already done! Just need to keep `datom-matches-temporal-filter?` returning `true` for missing dimensions.

Current code has it as `false` (strict). Change back to `true` (permissive).
