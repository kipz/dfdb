# Temporal Query Semantics - Clarification

## The Fundamental Conflict

We have TWO tests with **contradictory semantic expectations**:

### Test 1: `test-sparse-time-dimensions` (Lines 187, 193)
**Expectation**: STRICT/INCOMPARABLE semantics

```clojure
;; Entity A has time/valid only
;; Entity B has time/shipped only

(query db {:query '[:find ?id :where [?e :entity/id ?id]]
          :as-of {:time/valid #inst "2026-01-10"}})

;; Expected: #{["A"]} - only A has time/valid
;; Semantic: "Show me entities that exist at valid-time 2026-01-10"
;;           If entity lacks time/valid, it's incomparable - exclude it
```

### Test 2: `test-supply-chain-basic` (Line 404)
**Expectation**: PERMISSIVE semantics (implied)

```clojure
;; Different attributes set at different times:
;; - :order/id set with time/ordered
;; - :order/status set with time/shipped
;; - :order/status updated with time/delivered

(query db {:query '[:find ?order-id
                   :where
                   [?order :order/id ?order-id]
                   [?order :order/status _ :at/shipped ?st]
                   (not [?order :order/status _ :at/delivered ?dt])]
          :as-of {:time/shipped X, :time/delivered Y}})

;; Needs: :order/id datom (lacks time/shipped) to pass filter
;; Semantic: "Show me what was true at these times, join across attributes"
```

---

## The Core Question

**When filtering datoms by :as-of temporal constraints:**

### Option A: STRICT/INCOMPARABLE (Original Requirement)
```clojure
;; Datom must have ALL dimensions in :as-of filter
(if-let [datom-dim (get datom dimension)]
  (<= datom-dim filter-value)
  false)  ; Missing dimension = EXCLUDE
```

**Pros**:
- Clear "incomparable timestamp" semantics
- Prevents mixing apples and oranges
- Good for: "Show users registered in January" (users without registration-time excluded)

**Cons**:
- **Breaks cross-attribute queries** when attributes have different dimensions
- Can't query "order created at time A, shipped at time B"

### Option B: PERMISSIVE
```clojure
;; Only filter by dimensions present in datom
(if-let [datom-dim (get datom dimension)]
  (<= datom-dim filter-value)
  true)  ; Missing dimension = ALLOW
```

**Pros**:
- Enables cross-attribute temporal joins
- Works for supply chain scenarios
- More flexible

**Cons**:
- Weakens "incomparable" semantics
- "Show users registered in January" would include users without registration-time

### Option C: HYBRID (What We Need)
```clojure
;; Different semantics based on context:
// 1. If pattern has :at/dimension - skip global :as-of (already implemented!)
//    The :at/ requirement filters by demanding dimension exists

// 2. If pattern has NO :at/dimension - use :as-of with STRICT semantics
//    Filter out datoms lacking dimensions
```

**Pros**:
- Satisfies both use cases
- :at/ patterns get permissive (need specific dimension)
- Regular patterns get strict (incomparable)

**Cons**:
- More complex semantics
- Different behavior based on pattern structure

---

## RECOMMENDED SOLUTION

**Use HYBRID semantics** (partially implemented):

1. **Patterns with :at/dimension** → Skip global :as-of, let :at/ filter
   - Already implemented: `effective-as-of = nil` when temporal-spec exists
   - The :at/ requirement ensures dimension exists

2. **Patterns without :at/dimension** → Use STRICT :as-of semantics
   - Keep incomparable: datom without dimension = exclude

3. **For supply chain queries** → Use :at/ on patterns that need dimensions

**Example**:
```clojure
;; CORRECT supply chain query:
(query db {:query '[:find ?order-id
                   :where
                   [?order :order/id ?order-id]  ; No :at/ - matches anything
                   [?order :order/status _ :at/shipped ?st]  ; Requires shipped
                   (not [?order :order/status _ :at/delivered ?dt])]  ; Requires delivered
          :as-of {:time/shipped X, :time/delivered Y}})

;; The :order/id pattern has no :at/, so it's not filtered by as-of
;; Only the :at/ patterns filter for their specific dimensions
```

---

## DECISION

**Adopt HYBRID semantics**:
- ✅ Already implemented: :at/ patterns skip global :as-of
- ✅ Keep: STRICT semantics for non-:at/ patterns
- 🔧 **Adjust tests** to match this semantic choice

### Test Adjustments Needed

**test-sparse-time-dimensions**: ✓ Keep as-is (tests STRICT semantics)

**test-supply-chain-basic**:
- Already uses :at/ on patterns that need dimensions ✓
- Pattern `[?order :order/id ?order-id]` has no :at/, so skips global filter ✓
- **Should work but doesn't... let me investigate**

---

## Conclusion

The semantics are actually **already correct** with our hybrid approach!

The remaining issue must be **implementation bugs** in how we apply effective-as-of, not a semantic problem.

Let me investigate the actual bug...
