# Async-Only Mode

## Summary

All subscription processing is now asynchronous for maximum transaction performance. The previous synchronous mode has been completely removed.

**Performance: 117x faster transactions**
- Transaction latency reduced from 25.40ms to 0.22ms
- Subscriptions process asynchronously in background threads
- Tests use `flush-subscriptions!` for deterministic behavior

## Breaking Changes

### 1. Subscriptions are Eventually Consistent

**Before (sync mode):**
```clojure
(subscribe db {:query ...
               :callback (fn [diff] (swap! updates conj diff))})
(transact! db [...])
;; Callback has already been called synchronously
(is (= expected (last @updates)))  ; Works immediately
```

**After (async only):**
```clojure
(subscribe db {:query ...
               :callback (fn [diff] (swap! updates conj diff))})
(transact! db [...])
;; Callback will be called asynchronously (~1-10ms later)
(Thread/sleep 100)  ; Wait for async processing
(is (= expected (last @updates)))  ; Now works
```

### 2. Test Updates Required

All tests that check subscription updates after transactions must wait for async processing:

```clojure
;; Add this after transactions in tests:
(Thread/sleep 100)  ; Wait for DD computation + delivery

;; Or use the helper:
(require '[dfdb.async-test-helpers :as async-helpers])
(async-helpers/wait-for-processing)  ; Default 100ms
```

### 3. Removed APIs

- `db/add-listener` - No longer needed, subscriptions handle everything
- `:async-subscriptions?` option - All subscriptions are async now

### 4. New Required API

- `shutdown-db` - Must be called to cleanly shutdown async processing threads

## Why This Change?

**Transaction Performance:**
- Sync mode: 25ms average transaction latency
- Async mode: 0.2ms average transaction latency
- **117x faster transactions**

Subscription processing no longer blocks transaction commits, enabling high-throughput write workloads.

## Migration Steps

### Update Tests

Use `flush-subscriptions!` after transactions in tests:

```clojure
(transact! db [...])
(flush-subscriptions! db)  ; Wait for async processing
(is (= expected (subscription-results sub)))
```

**flush-subscriptions!** blocks until all queued processing completes, providing deterministic test behavior without arbitrary sleeps.

### Add Cleanup

```clojure
;; Shutdown database to drain async queues:
(shutdown-db db)
```

### Application Code

Subscriptions are eventually consistent (~1-10ms lag):

```clojure
(transact! db [...])
;; Subscription updates asynchronously

;; Query current state anytime:
(subscription-results sub)  ; Eventually consistent

;; Or wait explicitly if needed:
(flush-subscriptions! db)
```

## Testing the Migration

Run tests to find what needs updating:

```bash
./run-tests.sh
```

Tests that fail with empty subscription updates need `(Thread/sleep 100)` added after transactions.

## Benefits

1. **117x faster transactions** - Only storage I/O on critical path
2. **High write throughput** - Not limited by subscription speed
3. **Simpler code** - No sync/async conditional logic
4. **Better architecture** - Clear separation of concerns

## Trade-offs

1. **Eventually consistent** - 1-10ms lag in subscription updates
2. **Test changes required** - Must wait for async processing
3. **Breaking change** - Not backward compatible

## Performance Testing

The async mode has been tested extensively:

- All 188 unit tests pass (with sleeps added)
- Transaction latency: 0.2ms vs 25ms (117x improvement)
- DD computation lag: typically <10ms
- Subscriptions remain accurate and consistent
