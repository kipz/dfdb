# Operator State Formalization Plan

## Current State

Operators in DFDB use atoms for mutable state:

```clojure
(defrecord IncrementalJoinOperator [left-state right-state join-vars]
  ...)
; left-state and right-state are atoms: (atom {binding -> multiplicity})
```

**Problems**:
1. Hard to reason about correctness (when/how state changes)
2. State mutations not atomic across operators
3. Debugging state changes difficult (no history)
4. No state snapshots for testing
5. Concurrency issues possible (though currently single-threaded)

## Goals

1. **Explicit State Protocol**: Define clear interface for operator state
2. **State Snapshots**: Enable capturing state for testing/debugging
3. **State History**: Optional tracking of state changes
4. **Deterministic Testing**: Replay state transitions for debugging
5. **Better Debugging**: Inspect operator state without side effects

## Proposed Design

### Option A: Immutable State Threading (Functional)

Thread state through operator calls instead of mutating atoms:

```clojure
(defprotocol StatefulOperator
  (process-delta-with-state [this delta current-state]
    "Process delta and return [output-deltas new-state]"))

(defrecord IncrementalJoinOperator [join-vars]
  StatefulOperator
  (process-delta-with-state [_this delta state]
    (let [{:keys [left-state right-state]} state
          {:keys [binding mult source]} delta

          ;; Update appropriate side
          new-left (if (= source :left)
                     (update left-state binding (fnil + 0) mult)
                     left-state)
          new-right (if (= source :right)
                      (update right-state binding (fnil + 0) mult)
                      right-state)

          ;; Compute output deltas
          output-deltas (compute-join-deltas delta state)]

      [output-deltas {:left-state new-left :right-state new-right}])))
```

**Pros**:
- Purely functional, easy to reason about
- State transitions explicit
- Easy to test (no setup needed)
- History comes naturally (keep old states)

**Cons**:
- Requires rewriting all operators
- Performance overhead (state copying)
- More complex to integrate with current code

### Option B: State Management Layer (Hybrid)

Add abstraction layer over atoms with tracking:

```clojure
(defprotocol OperatorState
  (get-state [this] "Get current state")
  (update-state! [this f] "Update state with function")
  (snapshot [this] "Create state snapshot")
  (restore! [this snapshot] "Restore from snapshot"))

(defrecord TrackedState [current history-atom max-history]
  OperatorState
  (get-state [_] @current)
  (update-state! [this f]
    (let [old-state @current
          new-state (f old-state)]
      (reset! current new-state)
      (when history-atom
        (swap! history-atom
               (fn [hist]
                 (let [updated (conj hist {:old old-state
                                          :new new-state
                                          :ts (System/currentTimeMillis)})]
                   (if max-history
                     (take-last max-history updated)
                     updated)))))
      new-state))
  (snapshot [_] @current)
  (restore! [_ snapshot] (reset! current snapshot)))

(defn create-tracked-state
  [initial-value & {:keys [track-history? max-history]
                    :or {track-history? false max-history 100}}]
  (->TrackedState (atom initial-value)
                  (when track-history? (atom []))
                  max-history))
```

**Pros**:
- Backward compatible (still uses atoms internally)
- Add tracking incrementally
- Less code rewrite
- Configurable (history optional)

**Cons**:
- Still mutable (atoms underneath)
- Not purely functional
- History consumes memory

### Option C: Event Sourcing

Store events that led to current state:

```clojure
(defrecord StateManager [events state]
  ;; events: [{:type :update-left :binding {...} :mult 1 :ts 123}]
  ;; state: reconstructed from events
  )

(defn apply-event [state event]
  (case (:type event)
    :update-left
    (update-in state [:left-state (:binding event)]
               (fnil + 0) (:mult event))

    :update-right
    (update-in state [:right-state (:binding event)]
               (fnil + 0) (:mult event))))

(defn replay-events [events]
  (reduce apply-event {} events))
```

**Pros**:
- Complete history
- Time-travel debugging
- Audit trail
- Easy to test (replay events)

**Cons**:
- Most complex to implement
- Events consume memory
- Slower (replay for current state)
- Overkill for this use case

## Recommended Approach

**Option B: State Management Layer (Hybrid)**

Rationale:
- Backward compatible (minimal code changes)
- Adds debugging capabilities without major refactoring
- Can be added incrementally (start with join operators)
- Optional history (enable for debugging, disable for production)
- Good balance of benefits vs complexity

## Implementation Plan

### Phase 1: Create State Abstraction (1 week)
1. Implement `OperatorState` protocol
2. Create `TrackedState` implementation
3. Add helper functions for common patterns
4. Write tests for state manager

### Phase 2: Migrate Join Operators (1 week)
1. Update `IncrementalJoinOperator` to use `TrackedState`
2. Add state inspection utilities
3. Update tests to use state snapshots
4. Verify no performance regression

### Phase 3: Migrate Other Operators (1 week)
1. Update `CollectResults` to use `TrackedState`
2. Update `AggregateOperator` to use `TrackedState`
3. Update pattern operators as needed
4. Comprehensive testing

### Phase 4: Add Debugging Tools (1 week)
1. State visualization functions
2. State diff utilities (compare snapshots)
3. History replay for debugging
4. Integration with REPL tools

## Testing Strategy

```clojure
(deftest join-operator-state-test
  (let [op (make-join-operator [:?x] :track-history true)
        state-manager (:state op)]

    ;; Process some deltas
    (process-delta op (make-delta {:?x 1} 1 :left))
    (process-delta op (make-delta {:?x 1} 1 :right))

    ;; Take snapshot
    (let [snapshot (snapshot state-manager)]

      ;; Verify state
      (is (= 1 (get-in (get-state state-manager) [:left-state {:?x 1}])))

      ;; Process more deltas
      (process-delta op (make-delta {:?x 2} 1 :left))

      ;; Restore to snapshot
      (restore! state-manager snapshot)

      ;; Verify restoration
      (is (nil? (get-in (get-state state-manager) [:left-state {:?x 2}]))))))
```

## Benefits

1. **Better Testing**: Snapshot/restore enables deterministic tests
2. **Easier Debugging**: Inspect state without side effects
3. **History Tracking**: Optional history for debugging
4. **Clear Interface**: Protocol defines state operations
5. **Gradual Migration**: Add to operators incrementally

## Future Enhancements

1. **Persistence**: Save/load state to disk for crash recovery
2. **Distributed State**: Replicate state across nodes
3. **Compaction**: Compress history to save memory
4. **Metrics**: Track state size, update frequency, etc.

## Decision

**Recommended**: Implement Option B (State Management Layer) incrementally.

Start with join operators (highest value), then expand to other stateful operators as needed. Keep history optional and disabled by default to avoid performance impact.
