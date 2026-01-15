# Open Questions - Resolved

This document captures the resolutions to open design questions for dfdb.

## 1. Collection Element Deltas ✓

**Question**: How to represent collection element deltas, especially for vectors?

**Resolution**: **Position-based with indices**
- Vectors track position: `{:collection-op :conj :collection-index 3 :collection-element "new"}`
- Operations: `:conj` (append), `:assoc` (update at index), `:dissoc` (remove at index)
- Sets use `:conj`/`:disj` without index
- Maps use `:assoc`/`:dissoc` with `:collection-key`

**Rationale**: Preserves order semantics, enables precise subscriptions to specific changes.

## 2. Missing Dimensions in Timestamp Comparison ✓

**Question**: When comparing multi-dimensional timestamps, how to handle missing dimensions?

**Resolution**: **Incomparable**
- Timestamps with different present dimensions cannot be compared
- `[t1, nil]` and `[t2, 5]` have no ordering relationship
- Queries must explicitly specify which dimensions they query

**Rationale**: Prevents incorrect temporal reasoning. Forces explicit temporal semantics in queries.

**Example**:
```clojure
;; Entity A has :time/valid only
;; Entity B has :time/shipped only
;; Query as-of :time/valid returns only A
;; Query as-of :time/shipped returns only B
```

## 3. Temporal Joins ✓

**Question**: When joining on multiple time dimensions, what are the semantics?

**Resolution**: **User-specified join predicates**
- Users write explicit temporal predicates in `:where` clauses
- Example: `[(< ?ordered-time ?shipped-time)]`
- No automatic temporal join semantics
- Maximum flexibility for complex temporal relationships

**Rationale**: Different use cases need different temporal join semantics. Better to be explicit than assume.

## 4. GC/Retention for Temporal Data ✓

**Question**: How should garbage collection work for old temporal data?

**Resolution**: **Explicit user GC commands**
- No automatic GC or retention policies
- User explicitly compacts or deletes old versions via API
- Dimension metadata can include `:dimension/retention-days` for documentation but not enforcement

**Rationale**: Avoids surprising data loss. User controls when/how to compact history.

## 5. Cross-Dimensional Subscription Triggers ✓

**Question**: If subscription watches `:time/shipped`, should update to `:time/delivered` trigger notification?

**Resolution**: **No - only watched dimensions**
- Subscriptions specify `:watch-dimensions`
- Only updates to watched dimensions trigger notifications
- Updates to other dimensions on same entity do not trigger

**Rationale**: Predictable subscription behavior. Prevents spurious notifications.

**Example**:
```clojure
(subscribe db {:query [...]
               :watch-dimensions [:time/system :time/shipped]})
;; Triggers on system-time or shipped-time updates only
;; Does NOT trigger on delivered-time updates
```

## 6. Constraint Validation ✓

**Question**: Should dimension constraints be validated during transactions?

**Resolution**: **Hard validation - reject invalid transactions**
- Constraints (ordering, derived, user-defined) validated at transaction time
- Invalid transactions throw exceptions
- Transaction is atomic - either all constraints pass or none

**Rationale**: Data integrity. Better to fail fast than allow inconsistent data.

**Example**:
```clojure
;; This fails:
(transact! db {:tx-data [{:order/id 100}]
               :time-dimensions {:time/ordered #inst "2026-01-10"
                                 :time/shipped #inst "2026-01-05"}})
;; Exception: Constraint violation - shipped must be after ordered
```

## 7. Cross-Dimensional Query Syntax ✓

**Question**: For query "orders where shipped-time is 2 days after ordered-time", how to express?

**Resolution**: **Temporal predicates in :where**
- Use standard datalog predicates with temporal attribute access
- Access time dimensions via `:at/<dimension>` syntax in patterns
- Use Clojure predicates for temporal arithmetic

**Example**:
```clojure
[:find ?order
 :where
 [?order :order/id _ :at/ordered ?ord]
 [?order :order/id _ :at/shipped ?ship]
 [(- ?ship ?ord) ?diff]
 [(> ?diff 172800000)]]  ; 2 days in ms
```

## 8. Backpressure ✓

**Question**: How to handle slow subscribers that can't keep up?

**Resolution**: **Block transaction until consumed**
- Subscriptions have configurable buffer size
- When buffer full, transactions block until subscriber consumes
- Strong consistency guarantee
- Prevents system from overwhelming slow subscribers

**Rationale**: Ensures subscribers never miss updates. Backpressure signals system to slow down.

**Note**: May want to make this configurable per-subscription in future (block vs drop vs disconnect).

## 9. Memory Management for Long-Running Subscriptions ✓

**Question**: How to handle memory for large diff accumulations?

**Resolution**: **Let DD engine handle it**
- Trust differential dataflow's arrangement mechanisms
- Arrangements efficiently maintain indexed state
- No manual memory management needed
- DD naturally compacts equivalent diffs (e.g., +X, -X, +X → +X)

**Rationale**: DD is designed for this. Don't reinvent the wheel.

## 10. Query Default Time Dimensions ✓

**Resolution**: Queries default to "as-of now" for **system-time only**
- User dimensions ignored unless explicitly specified in `:as-of`
- Most permissive default
- Encourages explicit temporal reasoning for user dimensions

## 11. Retroactive Updates ✓

**Resolution**:
- **System-time**: Never retroactive, always advances forward
- **User dimensions**: Can be updated retroactively
- Subscriptions can choose to see retroactive updates or freeze to current horizon

## 12. Transaction Formats ✓

**Resolution**: Support both Datomic-style tx-data and raw documents
```clojure
;; Style 1: Datomic tx-data
[{:db/id -1 :user/name "Alice"}
 [:db/add 123 :user/age 30]]

;; Style 2: Raw documents (system computes diff)
[{:user/id 123 :user/name "Alice"}]
```

## 13. Indexing Strategy ✓

**Resolution**: **Separate index per dimension**
- Each time dimension gets its own EAVT/AEVT/AVET/VAET indexes
- Storage scales with dimensions but queries are fast
- No filtering at query time

## 14. Multi-Dimensional Lattice ✓

**Resolution**: DD operators work with multi-dimensional time lattice
- Timestamps are sparse maps: `{:time/system t1, :time/shipped t2}`
- Partial ordering based on present dimensions
- Incomparable timestamps require explicit handling in queries

## Summary Table

| Question | Resolution | Key Insight |
|----------|-----------|-------------|
| Collection deltas | Position-based indices | Preserves order, precise tracking |
| Missing dimensions | Incomparable | Forces explicit temporal reasoning |
| Temporal joins | User-specified predicates | Maximum flexibility |
| GC/Retention | Explicit user commands | No surprising data loss |
| Cross-dim subscriptions | Watch-specific only | Predictable behavior |
| Constraint validation | Hard validation, reject tx | Fail fast, data integrity |
| Cross-dim query syntax | Predicates in :where | Standard datalog patterns |
| Backpressure | Block transactions | Strong consistency |
| Memory management | Trust DD engine | Leverage arrangements |
| Query defaults | System-time only | Encourage explicitness |
| Retroactive updates | Only user dimensions | System-time immutable |
| Transaction formats | Both Datomic & docs | Flexibility |
| Indexing | Separate per dimension | Fast queries |
| DD lattice | Multi-dimensional sparse | Incomparable timestamps OK |
