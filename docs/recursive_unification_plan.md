# Recursive Query Unification Plan

## Current State

DFDB has two separate implementations for recursive queries:

### 1. Naive Implementation (`dfdb.recursive`)
- **Purpose**: One-time transitive closure computation
- **Approach**: Iterative frontier expansion using EAVT/VAET indexes
- **Use Case**: Initial query execution, one-shot reachability
- **Performance**: O(V + E) per query where V = vertices, E = edges

### 2. Differential Implementation (`dfdb.dd.recursive-incremental`)
- **Purpose**: Incremental transitive closure maintenance
- **Approach**: Differential dataflow with delta propagation
- **Use Case**: Subscription-based incremental updates
- **Performance**: O(affected paths) per transaction delta

## Problem

Having two implementations creates:
1. **Code Duplication**: Similar logic implemented differently
2. **Maintenance Burden**: Bug fixes must be applied to both
3. **Risk of Divergence**: Implementations may produce different results
4. **Cognitive Load**: Developers must understand both approaches

## Proposed Unification

### Option A: Use Differential Everywhere (Recommended)
- Replace naive implementation with differential approach
- Initialize differential pipeline with empty state
- Use single pass through pipeline for one-shot queries
- Reuse same pipeline for subscriptions

**Pros**:
- Single source of truth
- Consistent behavior
- Better tested (used in subscriptions)

**Cons**:
- Slightly higher overhead for one-shot queries
- More complex initialization

### Option B: Keep Both, Document Clearly
- Keep naive for simple one-shot queries
- Use differential only for subscriptions
- Add comprehensive tests comparing both
- Document when each is used

**Pros**:
- Lower risk
- Simpler one-shot query path

**Cons**:
- Ongoing maintenance of two implementations
- Risk of divergence remains

## Implementation Steps (Option A)

1. **Create Unified Interface**
   ```clojure
   (defn compute-transitive-closure
     [db pattern bindings opts]
     ;; opts: {:incremental? true/false, :max-depth N}
     ...)
   ```

2. **Migrate Naive Queries to Differential**
   - Update `dfdb.query/match-recursive-pattern` to use differential pipeline
   - Add initialization step for empty state
   - Ensure single-pass execution for non-incremental case

3. **Update Tests**
   - Verify both one-shot and incremental cases
   - Add property-based tests comparing results
   - Performance benchmarks for both cases

4. **Deprecate Old Implementation**
   - Mark `dfdb.recursive` as deprecated
   - Add migration guide
   - Keep for one release cycle

5. **Remove Old Implementation**
   - Delete `dfdb.recursive` after deprecation period
   - Update all references

## Testing Strategy

1. **Correctness Tests**
   - Graph reachability examples
   - Cycles in graphs
   - Self-loops
   - Disconnected components

2. **Equivalence Tests**
   ```clojure
   (deftest naive-vs-differential
     (let [db (create-test-graph)
           query '[:find ?x ?y :where [?x :parent+ ?y]]
           naive-result (query-naive db query)
           diff-result (query-differential db query)]
       (is (= naive-result diff-result))))
   ```

3. **Performance Tests**
   - Small graphs (<100 nodes)
   - Large graphs (10K+ nodes)
   - Deep paths (depth > 100)
   - Wide graphs (high branching factor)

## Migration Timeline

- **Phase 1** (1 week): Create unified interface and adapter layer
- **Phase 2** (2 weeks): Migrate all callers to unified interface
- **Phase 3** (1 week): Comprehensive testing
- **Phase 4** (1 week): Deprecate old implementation
- **Phase 5** (After 1 release): Remove old implementation

## Decision

**Recommended**: Option A (Use Differential Everywhere)

The differential implementation is more powerful and already handles the complex incremental case. Using it for one-shot queries adds minimal overhead while eliminating code duplication and ensuring consistency.

Next step: Create unified interface in `dfdb.recursive.core` that delegates to differential implementation.
