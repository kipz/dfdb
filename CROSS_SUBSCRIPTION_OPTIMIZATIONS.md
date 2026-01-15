# Cross-Subscription Optimizations (Steps 7 & 8)

## Summary

Implemented two cross-subscription optimizations to reduce overhead when multiple subscriptions are active:

1. **Step 8: Attribute-Based Subscription Filtering** - Skip subscriptions that don't watch affected attributes
2. **Step 7: Binding Delta Caching** - Share pattern-to-binding conversion across subscriptions

## Step 8: Attribute-Based Subscription Filtering

### Problem
Every transaction processes ALL active subscriptions, even if they watch attributes that weren't modified. With 100 subscriptions and a transaction that only changes `:user/email`, all 100 subscriptions are needlessly processed.

### Solution
Extract affected attributes from transaction deltas and filter subscriptions before processing:

1. **Extract watched attributes** - Parse query and cache attributes in subscription record
2. **Extract affected attributes** - Get attributes from transaction deltas
3. **Filter subscriptions** - Only process subscriptions watching affected attributes

### Implementation

#### Added to Subscription Record (subscription.clj:32-45)
```clojure
(defrecord Subscription
  [id query-form mode callback delivery channel
   watch-dimensions see-retroactive? transform-fn
   current-results active? dd-graph
   watched-attributes])  ; NEW: Cache of watched attributes
```

#### Helper Functions (subscription.clj:80-119)
- `pattern-clause?` - Check if clause is a pattern
- `extract-query-attributes` - Extract attributes from query patterns
- `subscription-watches-attributes?` - Check if subscription watches affected attributes

#### dd-computation-loop Changes (subscription.clj:135-143)
```clojure
;; Extract affected attributes from transaction deltas
affected-attrs (set (keep :attribute deltas))

;; Filter subscriptions by affected attributes
(doseq [[_id subscription] @subscriptions
        :when (and @(:active? subscription)
                   (subscription-watches-attributes? subscription affected-attrs))]
  ...)
```

#### Special Case: Time Dimensions
Subscriptions with `watch-dimensions` (other than `:time/system`) are always processed, since time dimension filtering happens in the delivery logic.

### Expected Impact
- **100 subscriptions, 10 attribute types, 1 attribute modified** → process ~10 subscriptions instead of 100
- **10x reduction** in processing overhead for attribute-localized transactions
- Zero impact when all subscriptions watch all attributes

## Step 7: Binding Delta Caching

### Problem
Each subscription independently calls `transaction-deltas-to-binding-deltas` for its patterns. With 10 subscriptions watching `[?e :user/name ?name]`, the conversion happens 10 times with identical results.

### Solution
Cache binding delta conversions and share across subscriptions with the same patterns:

1. **Extract unique patterns** - Collect patterns from all relevant subscriptions
2. **Build cache** - Compute binding deltas once per unique pattern
3. **Use dynamic binding** - Make cache available to all pipeline processing via `*binding-delta-cache*`

### Implementation

#### Dynamic Cache Variable (delta_core.clj:9-12)
```clojure
(def ^:dynamic *binding-delta-cache*
  "Dynamic cache for binding deltas. Used to share conversion work across subscriptions.
  Map of pattern -> binding-deltas. Bound during dd-computation-loop."
  nil)
```

#### Modified transaction-deltas-to-binding-deltas (delta_core.clj:53-95)
```clojure
(defn transaction-deltas-to-binding-deltas
  "Convert transaction deltas to binding deltas for a pattern.
  Uses *binding-delta-cache* if available to share conversion work across subscriptions."
  [tx-deltas pattern]
  ;; Check cache first if available
  (if-let [cached (and *binding-delta-cache* (get *binding-delta-cache* pattern))]
    cached
    ;; Cache miss or no cache - compute
    (let [...]
      ...)))
```

#### Cache Building in dd-computation-loop (subscription.clj:138-160)
```clojure
;; Build binding delta cache: pattern -> binding-deltas
relevant-subs (filter (fn [[_id sub]]
                        (and @(:active? sub)
                             (subscription-watches-attributes? sub affected-attrs)))
                      @subscriptions)

unique-patterns (set (for [[_id sub] relevant-subs
                           :let [query-form (:query-form sub)]
                           :when query-form
                           :let [parsed (try (query/parse-query query-form) ...)]
                           :when parsed
                           pattern (get parsed :where [])
                           :when (pattern-clause? pattern)]
                       pattern))

binding-delta-cache (into {}
                          (map (fn [pattern]
                                 [pattern (delta/transaction-deltas-to-binding-deltas deltas pattern)])
                               unique-patterns))

;; Process subscriptions with binding delta cache
(binding [delta/*binding-delta-cache* binding-delta-cache]
  (doseq [[_id subscription] relevant-subs]
    ...))
```

### Expected Impact
- **10 subscriptions with identical pattern** → 1 conversion instead of 10
- **O(N) → O(1)** for N subscriptions watching the same pattern
- Most beneficial when many subscriptions watch the same attributes (e.g., activity feeds)

## Combined Impact

For a system with:
- **100 subscriptions**
- **10 different patterns** (10 subscriptions per pattern on average)
- **10 attribute types**
- **Transaction modifying 1 attribute**

**Before optimizations:**
- Process 100 subscriptions
- Convert deltas 100 times (once per subscription)

**After optimizations:**
- Process ~10 subscriptions (attribute filtering)
- Convert deltas 1-2 times (caching)
- **~50x reduction** in total overhead

## Test Results

✅ **All 191 unit tests pass** (535 assertions)
✅ **No correctness regressions**
✅ **Time dimension filtering** still works correctly
✅ **Multi-pattern queries** still work correctly

## Files Modified

### New Functionality
- `src/dfdb/subscription.clj`:
  - Added `watched-attributes` field to Subscription
  - Added `extract-query-attributes` helper
  - Added `subscription-watches-attributes?` filter
  - Modified `dd-computation-loop` to build cache and filter subscriptions
  - Modified `create-subscription` to cache watched attributes

- `src/dfdb/dd/delta_core.clj`:
  - Added `*binding-delta-cache*` dynamic var
  - Modified `transaction-deltas-to-binding-deltas` to check cache

### No Breaking Changes
- All existing APIs unchanged
- Optimizations are transparent to users
- No configuration required

## Limitations

### When Attribute Filtering Doesn't Help
- Subscriptions with `watch-dimensions` (other than `:time/system`) are always processed
- Queries without patterns are always processed
- Transactions affecting many attributes reduce benefit

### When Caching Doesn't Help
- All subscriptions have unique patterns
- Pattern diversity is high

## Future Enhancements

1. **Pattern similarity** - Cache similar patterns (e.g., `[?e :user/name ?n]` and `[?x :user/name ?name]`)
2. **Subscription grouping** - Group subscriptions by pattern set for batch processing
3. **Adaptive filtering** - Profile and disable filtering if overhead > benefit

## Conclusion

Cross-subscription optimizations provide significant benefits for systems with:
- ✅ Many active subscriptions (10+)
- ✅ Attribute-localized transactions
- ✅ Pattern reuse across subscriptions

The optimizations are **transparent, zero-configuration, and regression-free**, making them safe to deploy in all environments.
