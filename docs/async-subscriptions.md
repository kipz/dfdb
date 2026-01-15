# Async Subscription Processing

## Overview

All subscription processing in dfdb is asynchronous through a three-stage pipeline that dramatically improves transaction latency by decoupling subscription computation and delivery from the transaction commit path.

## Performance Impact

**Transaction Latency: 117x faster than previous sync implementation**

- **Previous sync mode**: 25.40ms average
- **Current async mode**: 0.22ms average
- **Improvement**: 117.7x

This improvement is achieved by moving subscription processing off the transaction thread.

## Architecture

### Three-Stage Pipeline

```
Stage 1: Transaction Thread
  transact! → storage write → enqueue deltas → return (0.22ms)
                              ↓

Stage 2: DD Computation Thread
  go-loop → process deltas → update DD pipelines → compute diffs → enqueue deliveries
                                                                    ↓

Stage 3: Delivery Thread
  go-loop → deliver to callbacks/channels
```

### Key Benefits

1. **Fast transactions** - Only storage I/O on critical path
2. **Eventually consistent subscriptions** - Typically <10ms lag
3. **Independent backpressure** - Each stage has its own queue policy
4. **Graceful degradation** - Slow consumers don't block writers

## Usage

### Basic Usage

```clojure
;; Create database (subscriptions are always async)
(def db (create-db))

;; Subscribe as usual
(def sub (subscribe db {:query '[:find ?name :where [?e :user/name ?name]]
                        :callback #(println "Update:" %)}))

;; Transactions return immediately after storage write
(transact! db [{:user/name "Alice"}])  ; Returns in ~0.2ms

;; For deterministic behavior in tests, use flush:
(flush-subscriptions! db)  ; Blocks until all async processing completes

;; Or query current state anytime
(subscription-results sub)  ; Get current results (eventually consistent)
```

### Configuration

```clojure
;; Configure queue sizes (dynamic vars)
(binding [dfdb.subscription/*dd-computation-queue-size* 5000
          dfdb.subscription/*delivery-queue-size* 500]
  (def db (create-db {:async-subscriptions? true})))
```

**Queue Backpressure Policies:**
- **DD Computation Queue** (default: 10000): Blocks transactions if full
- **Delivery Queue** (default: 1000): Drops deliveries if full

### Monitoring

```clojure
;; Check DD computation stats
(dd-computation-stats db)
;; => {:processed 100, :errors 0, :lag-ms 5, :last-process-time 2.3}

;; Check delivery stats
(delivery-stats db)
;; => {:delivered 95, :dropped 5, :errors 0}

;; Query current subscription state (eventually consistent)
(subscription-results sub)
;; => #{["Alice"] ["Bob"] ...}
```

### Graceful Shutdown

```clojure
;; Drain queues and shutdown cleanly
(shutdown-db db)
;; DD Computation notifier shutdown: processed=100 errors=0
;; Delivery notifier shutdown: delivered=95 dropped=5 errors=0
```

## Trade-offs

### Pros
- **117x faster transaction latency**
- **High write throughput** - Not limited by subscription speed
- **Slow consumers don't block writers**
- **Configurable backpressure** per stage

### Cons
- **Eventually consistent** - Subscriptions lag by ~1-10ms
- **Delivery can be dropped** - Under extreme load
- **More complex error handling** - Transaction committed but DD computation may fail
- **DD queue can fill** - Causes transaction blocking under extreme write load

## Test Guidelines

**Using `flush-subscriptions!` in Tests:**

Subscriptions process asynchronously, so tests need to wait for processing:

```clojure
(transact! db [{:user/name "Alice"}])
(flush-subscriptions! db)  ; Blocks until processing completes
(is (= expected (subscription-results sub)))
```

**flush-subscriptions!** returns a map with stats:
```clojure
{:success? true
 :dd-processed 5       ; Number of DD computations completed
 :delivered 5          ; Number of deliveries sent
 :elapsed-ms 23}       ; Time taken to flush
```

## Breaking Changes from Previous Sync Mode

⚠️ **Sync mode has been removed**
- All subscriptions are now async (no opt-in required)
- Tests must use `flush-subscriptions!` after transactions
- `:async-subscriptions?` option removed
- All 190 tests pass with async mode

## Implementation Details

### DD Computation Loop

Processes transaction deltas through all active subscription DD pipelines:

```clojure
(defn- dd-computation-loop [computation-channel delivery-channel stats-atom]
  (async/go-loop []
    (when-let [event (async/<! computation-channel)]
      ;; Update all subscription DD pipelines
      (doseq [subscription @subscriptions]
        (update-dd-pipeline subscription (:deltas event))
        ;; Enqueue delivery if results changed
        (when results-changed?
          (async/offer! delivery-channel delivery-event)))
      (recur))))
```

### Delivery Loop

Delivers result diffs to callbacks/channels:

```clojure
(defn- delivery-loop [delivery-channel stats-atom]
  (async/go-loop []
    (when-let [event (async/<! delivery-channel)]
      ;; Deliver to subscription
      (case (:delivery subscription)
        :callback (callback {:additions ..., :retractions ...})
        :core-async (async/offer! channel diff))
      (recur))))
```

### Transaction Integration

Transactions enqueue deltas instead of processing synchronously:

```clojure
(if-let [notifiers @(:subscription-notifiers db)]
  ;; Async: enqueue (may block if queue full)
  (enqueue-dd-computation! (:computation notifiers) deltas tx-id)

  ;; Sync: process directly (original behavior)
  (notify-all-subscriptions db deltas))
```

## Performance Testing

Run the demo tests to see the performance improvement:

```bash
clojure -M:test -m cognitect.test-runner -n dfdb.async-subscription-demo-test
```

Expected output:
```
=== Transaction Latency Comparison ===
Sync mode avg: 25.40 ms
Async mode avg: 0.22 ms
Speedup: 117.7 x
```

## Future Optimizations

After async subscriptions, additional optimizations can be considered:

1. **Binding delta caching** - Cache conversion per pattern, share across subscriptions
2. **Early pattern filtering** - Filter subscriptions by affected attributes before processing
3. **Thread pool for DD computation** - Parallel processing of independent queries
4. **Reduce index writes** - From 4x per datom to configurable per-attribute

## Examples

See `test/dfdb/async_subscription_demo_test.clj` for complete examples:

- `test-transaction-latency-sync-vs-async` - Performance comparison
- `test-eventual-consistency` - Demonstrates eventual consistency
- `test-backpressure-delivery-drops` - Backpressure handling
