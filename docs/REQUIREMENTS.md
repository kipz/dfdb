# dfdb - Differential Dataflow Database

A multi-dimensional temporal document database with differential dataflow subscriptions.

## Core Concepts

### Differential Dataflow

Incremental computation where changes propagate through dataflow operators. Only affected computations are re-executed when data changes.

**Implementation Approach**: Adapt differential dataflow principles to Clojure idioms
- Implement multisets, differences, and lattices idiomatically in Clojure
- Build dataflow operators as protocols
- Use core.async for coordination

**Execution Modes**:
- **Batch**: For one-shot queries, historical analysis, bulk loads
- **Incremental**: For live subscriptions, real-time materialized views
- **Configuration**: Per-query/subscription (user specifies at query time)

**Key Concepts**:
- **Multisets**: Collections with multiplicity (elements can appear multiple times)
- **Differences**: Changes represented as (value, multiplicity-delta) pairs
- **Lattices**: Partially ordered time domains for coordination
- **Operators**: map, filter, join, group, aggregate with difference propagation
- **Arrangements**: Indexed representations for efficient joins
- **Frontiers**: Track progress through logical timestamps

### Multi-Dimensional Time

Beyond bitemporal (2 dimensions), support arbitrary N time dimensions.

#### System Time
- Always present, immutable
- Represents when fact entered the database
- Analogous to transaction-time in XTDB
- Never retroactive - always advances forward
- Provides audit trail and "what did we know when" queries

#### User-Defined Time Dimensions
- Defined at runtime via transactions
- Stored as entities in the database (queryable, versioned)
- Optional per fact (sparse representation)
- Can be retroactive (unlike system-time)
- Examples: `:time/valid`, `:time/shipped`, `:time/delivered`, `:time/received`

#### Time Dimension Metadata

Dimensions are entities with rich metadata:

```clojure
{:dimension/name :time/shipped
 :dimension/type :instant
 :dimension/description "When shipment left warehouse"
 :dimension/units :utc-instant
 :dimension/indexed? true
 :dimension/retention-days 2555  ; 7 years
 :dimension/constraints [{:type :ordering
                          :after :time/ordered
                          :description "Cannot ship before ordered"}]
 :dimension/derived-from nil  ; or [:time/delivered :time/shipped] for duration
 :dimension/custom/sla-hours 48}
```

#### Dimension Constraints

Dimensions can have multiple types of constraints:
- **No constraints** (default): Independent dimensions
- **Ordering constraints**: `shipped >= ordered`
- **Derived dimensions**: `delivery-duration = delivered - shipped`
- **User-defined constraints**: Arbitrary predicate functions

#### Indexing Strategy

**Separate index per dimension**: Each time dimension gets its own set of indexes
- System-time: `EAVT-system`, `AEVT-system`, `AVET-system`, `VAET-system`
- Shipped-time: `EAVT-shipped`, `AEVT-shipped`, etc.
- Storage overhead scales with dimensions, but queries are fast

Core index orderings (replicated per dimension):
- **EAVT**: entity, attribute, value, time
- **AEVT**: attribute, entity, value, time
- **AVET**: attribute, value, entity, time
- **VAET**: value, attribute, entity, time (for references)

#### Query Semantics

**Default Behavior**:
- Queries default to "as-of now" for **system-time only**
- User dimensions ignored unless explicitly specified
- Most permissive default

**Query Styles** (all supported):

1. **Explicit dimension clauses**:
```clojure
[:find ?order ?status
 :as-of {:time/system :now
         :time/shipped #inst "2026-01-01"}
 :where
 [?order :order/status ?status]]
```

2. **Default with overrides**:
```clojure
[:find ?order ?status
 :as-of {:time/shipped #inst "2026-01-01"}  ; system-time defaults to :now
 :where
 [?order :order/status ?status]]
```

3. **Dimension predicates in :where**:
```clojure
[:find ?order ?shipped-time ?delivered-time
 :where
 [?order :order/status :delivered]
 [?order :order/status _ :at/shipped ?shipped-time]
 [?order :order/status _ :at/delivered ?delivered-time]
 [(< ?shipped-time ?delivered-time)]]
```

#### Multi-Dimensional Lattice

Differential dataflow operators work with multi-dimensional time lattice:
- Timestamps are vectors: `[system-time shipped-time delivered-time]`
- Partial ordering: `[t1 s1 d1] ≤ [t2 s2 d2]` iff `t1 ≤ t2 ∧ s1 ≤ s2 ∧ d1 ≤ d2`
- Missing dimensions treated as ⊥ (bottom) for ordering
- Enables sophisticated temporal joins and aggregations

#### Retroactive Updates

- **System-time**: Never retroactive, always advances
- **User dimensions**: Can be updated retroactively
- **Subscription behavior**: Configurable per-subscription
  - Some subscriptions see history rewrites
  - Others frozen at specific time horizon

#### Example Use Case: Supply Chain

Track order fulfillment through multiple temporal stages:

```clojure
;; Define time dimensions
[{:dimension/name :time/ordered
  :dimension/type :instant}
 {:dimension/name :time/shipped
  :dimension/type :instant
  :dimension/constraints [{:type :ordering :after :time/ordered}]}
 {:dimension/name :time/delivered
  :dimension/type :instant
  :dimension/constraints [{:type :ordering :after :time/shipped}]}
 {:dimension/name :time/received
  :dimension/type :instant
  :dimension/constraints [{:type :ordering :after :time/delivered}]}]

;; Transact order with multiple time dimensions
{:order/id "ORD-123"
 :order/status :shipped
 :order/customer [:customer/email "customer@example.com"]
 :time/ordered #inst "2026-01-01T10:00:00"
 :time/shipped #inst "2026-01-02T14:30:00"}

;; Later: add delivery time retroactively
[[:db/add [:order/id "ORD-123"] :order/status :delivered]
 [:db/add [:order/id "ORD-123"] :time/delivered #inst "2026-01-05T09:15:00"]]

;; Query: orders shipped but not delivered as of 2026-01-03
[:find ?order ?customer
 :as-of {:time/shipped #inst "2026-01-03T23:59:59"
         :time/delivered #inst "2026-01-03T00:00:00"}
 :where
 [?order :order/status ?status :at/shipped _]
 (not [?order :order/status ?status :at/delivered _])
 [?order :order/customer ?customer]]
```

### Document Model

**Storage**: EDN documents natively

**Decomposition**: Documents decompose into EAV triples (Datomic-style)
- Nested maps become separate entities with generated IDs
- Vectors/collections stored as multi-valued attributes
- Enables deep querying and joins

**References**:
- **Entity IDs**: Direct references like `{:order/customer [:entity/id 456]}`
- **Lookup refs**: Datomic-style like `[:customer/email "user@example.com"]`

**Schema**: Schema-less - no required attribute definitions
- Pure document store flexibility
- Optional validation via Clojure specs

**History**: Complete history of each document as it changes over time

**Example Document**:
```clojure
{:user/id 123
 :user/name "Alice"
 :user/email "alice@example.com"
 :user/addresses [{:address/street "123 Main St"
                   :address/city "Springfield"
                   :address/zip "12345"}
                  {:address/street "456 Oak Ave"
                   :address/city "Portland"
                   :address/zip "97201"}]
 :user/manager [:user/email "bob@example.com"]}  ; lookup ref
```

Decomposes into:
```clojure
[123 :user/id 123]
[123 :user/name "Alice"]
[123 :user/email "alice@example.com"]
[123 :user/addresses 456]  ; generated entity
[456 :address/street "123 Main St"]
[456 :address/city "Springfield"]
[456 :address/zip "12345"]
[123 :user/addresses 457]  ; second address
[457 :address/street "456 Oak Ave"]
[457 :address/city "Portland"]
[457 :address/zip "97201"]
[123 :user/manager <resolved-entity-id>]  ; lookup ref resolved
```

### Delta Tracking

**Granularity**: Collection element-level changes (fine-grained)
- Track individual additions/removals within vectors
- Track key additions/removals in maps
- Enables fine-grained subscriptions

**Delta Structure** (sparse - only present dimensions):

```clojure
{:entity 123
 :attribute :user/email
 :old-value "alice@old.com"
 :new-value "alice@example.com"
 :operation :assert  ; or :retract
 :time/system #inst "2026-01-11T10:05:00"
 :time/valid #inst "2026-01-11T10:00:00"
 :tx {:tx/id 1001
      :tx/user "alice"
      :tx/source "api"
      :tx/reason "Email correction"}}
```

**Operations**:
- `:assert` - Add or update value
- `:retract` - Remove value

**Transaction Metadata**: Each transaction is an entity with attributes
- Standard: `:tx/id`, `:tx/time` (system-time)
- User-defined: `:tx/user`, `:tx/source`, `:tx/reason`, `:tx/request-id`, etc.

**Collection Element Changes**:
```clojure
{:entity 123
 :attribute :user/tags
 :old-value ["clojure" "rust" "databases"]
 :new-value ["clojure" "databases"]
 :operation :retract
 :collection-element "rust"
 :collection-index 1
 :time/system #inst "2026-01-11T11:01:00"
 :tx {:tx/id 1002}}
```

## Query System

### Language

**Extended Datalog** (Datomic-flavored) with temporal and transformation extensions.

### Basic Query Example

```clojure
[:find ?user ?total-spent
 :where
 [?user :user/name ?name]
 [?order :order/user ?user]
 [?order :order/total ?amount]
 [(> ?amount 100)]
 :aggregate
 [(sum ?amount) ?total-spent]]
```

### Temporal Queries

```clojure
;; As-of specific system-time
[:find ?user ?email
 :as-of {:time/system #inst "2026-01-01"}
 :where
 [?user :user/email ?email]]

;; Time travel across multiple dimensions
[:find ?order ?shipped ?delivered
 :as-of {:time/shipped #inst "2026-01-15"}
 :where
 [?order :order/status :delivered]
 [?order :order/status _ :at/shipped ?shipped]
 [?order :order/status _ :at/delivered ?delivered]]

;; Range queries
[:find ?e ?v ?t
 :where
 [?e :sensor/temp ?v :at/system ?t]
 [(>= ?t #inst "2026-01-01")]
 [(<= ?t #inst "2026-01-31")]]
```

### Recursive Queries

```clojure
;; Find all transitive reports
[:find ?report
 :where
 [?manager :user/name "Alice"]
 [?report :user/reports-to ?manager]
 :recursive
 [(reports-to ?x ?y)
  [?x :user/reports-to ?y]]
 [(reports-to ?x ?z)
  (reports-to ?x ?y)
  (reports-to ?y ?z)]]
```

### Subscriptions

**Result Format**: Diffs only (additions and retractions to result set)
- Not computed by re-running query and comparing
- Computed incrementally via differential dataflow
- System maintains computation graph, propagates only changes

**Notification Mechanisms** (all supported):
1. **Callbacks/functions**: `(fn [diff] ...)`
2. **Core.async channels**: `(chan)`
3. **Manifold streams**: `(stream)`

**Lifecycle**: Explicit subscribe/unsubscribe

**Subscription Example**:
```clojure
;; Create subscription
(def sub
  (subscribe db
             {:query [:find ?order ?status
                      :where
                      [?order :order/status ?status]]
              :mode :incremental
              :time-dimensions [:time/system]  ; which dims trigger updates
              :see-retroactive? false  ; frozen to current time
              :delivery :core-async}))

;; Receive diffs
(let [diff (<!! (:channel sub))]
  ;; diff = {:additions #{[ORD-123 :shipped]}
  ;;         :retractions #{[ORD-123 :pending]}}
  (process-diff diff))

;; Clean up
(unsubscribe sub)
```

**Scale**: Thousands+ concurrent subscriptions
- Query result sharing across multiple subscribers
- Subscription multiplexing for efficiency

**Differential Compilation**: Automatic
- System analyzes Datalog query
- Generates differential dataflow operators
- User just writes queries

## Transformation Functions

**Language**: Clojure functions (EDN-serialized)

**Execution Locations** (all supported):

### Pre-Query Transformations
Transform input deltas before they enter datalog operators:
```clojure
(fn [delta]
  (update delta :value normalize-email))
```

### Mid-Query Custom Operators
Custom predicates and aggregates within datalog:
```clojure
[:find ?place1 ?place2 ?distance
 :where
 [?place1 :place/lat ?lat1]
 [?place1 :place/lon ?lon1]
 [?place2 :place/lat ?lat2]
 [?place2 :place/lon ?lon2]
 [(custom-geo-distance ?lat1 ?lon1 ?lat2 ?lon2) ?distance]
 [(< ?distance 10.0)]]
```

### Post-Query Transformations
Transform output diffs before subscriber delivery:
```clojure
(fn [result-diff]
  (update result-diff :additions
          (fn [rows] (filter #(> (:score %) 0.5) rows))))
```

## Transaction System

### Transaction Model
**Full serializable transactions** (like Datomic)
- ACID across multiple entities
- Serializable isolation
- Optimistic concurrency control

### Transaction Formats

Both formats supported:

#### 1. Datomic-style Transaction Data

```clojure
[{:db/id -1
  :user/name "Alice"
  :user/email "alice@example.com"
  :time/valid #inst "2026-01-10"}

 [:db/add 123 :user/age 30 :time/valid #inst "2026-01-11"]
 [:db/retract 123 :user/status :inactive]]
```

#### 2. Raw Documents

System computes diff:
```clojure
[{:user/id 123
  :user/name "Alice"
  :user/email "alice@example.com"
  :time/valid #inst "2026-01-10"}]
```

### Transaction Metadata

```clojure
;; With metadata
(transact! db
  {:tx-data [{:user/name "Alice"}]
   :tx-meta {:tx/user "admin"
             :tx/source "migration-script"
             :tx/reason "Data correction"
             :tx/request-id "req-12345"}
   :time-dimensions {:time/valid #inst "2026-01-01"}})
```

### Transaction Response

Returns all deltas that were applied:
```clojure
{:tx-id 1001
 :tx-time #inst "2026-01-11T10:05:00"
 :deltas [{:entity 123
           :attribute :user/name
           :old-value nil
           :new-value "Alice"
           :operation :assert
           :time/system #inst "2026-01-11T10:05:00"
           :time/valid #inst "2026-01-01"
           :tx {:tx/id 1001 :tx/user "admin"}}
          {:entity 123
           :attribute :user/email
           :old-value nil
           :new-value "alice@example.com"
           :operation :assert
           :time/system #inst "2026-01-11T10:05:00"
           :time/valid #inst "2026-01-01"
           :tx {:tx/id 1001 :tx/user "admin"}}]}
```

## Storage

### Storage Interface
**Pluggable storage interface** with multiple implementations planned:
- Embedded KV stores (RocksDB, LMDB)
- Remote storage (S3, Postgres, FoundationDB)
- In-memory (for testing)

### Storage Protocol

```clojure
(defprotocol Storage
  (put [this key value])
  (get [this key])
  (scan [this start-key end-key])
  (delete [this key])
  (batch-write [this ops])
  (snapshot [this]))
```

### Index Organization

Per time dimension, multiple sort orders:
```
storage/
  indexes/
    system-time/
      eavt/
      aevt/
      avet/
      vaet/
    shipped-time/
      eavt/
      aevt/
      avet/
      vaet/
    delivered-time/
      ...
```

## Architecture

### Deployment
**Single-node** with future distributed path
- Design APIs and architecture for eventual distribution
- Start simple, single-process
- Easier to build and reason about

### Core Components

```
┌─────────────────────────────────────────────────────────┐
│                    Transaction API                       │
│  (transact!, query, subscribe, as-of, history)          │
└─────────────────┬───────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────┐
│              Transaction Processor                       │
│  - Parses tx-data (both formats)                        │
│  - Validates constraints                                │
│  - Generates deltas                                     │
│  - Applies to indexes                                   │
└─────────────────┬───────────────────────────────────────┘
                  │
      ┌───────────┴───────────┐
      │                       │
┌─────▼─────────┐   ┌────────▼────────┐
│ Index         │   │ Dataflow Engine │
│ Maintainer    │   │                 │
│ - EAVT/AEVT   │   │ - Compiles      │
│ - AVET/VAET   │   │   Datalog       │
│ - Per         │   │ - Executes DD   │
│   dimension   │   │   operators     │
│               │   │ - Propagates    │
│               │   │   changes       │
└───────┬───────┘   └────────┬────────┘
        │                    │
        │           ┌────────▼────────┐
        │           │  Subscription   │
        │           │   Manager       │
        │           │ - Multiplexing  │
        │           │ - Backpressure  │
        │           │ - 1000s of subs │
        │           └────────┬────────┘
        │                    │
┌───────▼────────────────────▼────────┐
│         Storage Adapter              │
│    (RocksDB, LMDB, S3, Memory)      │
└──────────────────────────────────────┘
```

### Query Compilation Pipeline

```
Datalog Query
    │
    ▼
Parser (EDN -> AST)
    │
    ▼
Stratification (handle negation/recursion)
    │
    ▼
Planning (optimize, choose indexes)
    │
    ▼
DD Operator Generation
    │
    ▼
Multi-Dimensional Lattice Setup
    │
    ▼
Execution (batch or incremental)
    │
    ▼
Results / Subscription
```

## Primary Use Cases

### 1. Real-Time Materialized Views
**Example**: Product analytics dashboard
- Query: Total orders and revenue per product category
- Behavior: Updates incrementally as new orders transacted
- Efficiency: O(1) per order, not O(all orders)

### 2. Event Sourcing with Projections
**Example**: User account projection
- Events: `:user-created`, `:email-verified`, `:profile-updated`
- Projection: Current user state derived from events
- Subscriptions: UI subscribes to user entity views

### 3. Reactive UI Updates
**Example**: Chat application
- Query: Messages in channel since timestamp
- Subscription: UI receives diffs, renders new messages
- Efficiency: Only new messages delivered, not full history

### 4. Complex Graph/Recursive Queries
**Example**: Organization hierarchy
- Query: All reports (transitive) under manager
- Recursive rule: Reports include direct reports and reports of reports
- Incremental: Re-org updates propagate only affected relationships

### 5. Supply Chain Tracking
**Example**: Order fulfillment pipeline
- Dimensions: `:time/ordered`, `:time/shipped`, `:time/delivered`, `:time/received`
- Queries: Orders in-flight, SLA violations, delivery durations
- Subscriptions: Real-time dashboard of fulfillment status

## Implementation

### Language
**Clojure**

### Differential Dataflow in Clojure

Adapt differential dataflow principles idiomatically:

#### Key Data Structures

```clojure
;; Multiset: map from value to multiplicity
(deftype Multiset [values]  ; {value -> count}
  ...)

;; Difference: change to multiset
(deftype Difference [additions retractions]
  ;; additions: {value -> +count}
  ;; retractions: {value -> -count}
  ...)

;; Multi-dimensional timestamp
(deftype Timestamp [dimensions]  ; {:time/system t1, :time/shipped t2}
  Comparable
  (compareTo [this other]
    ;; Partial order: this <= other iff all dimensions <=
    ...))

;; Collection: timestamped multiset
(deftype Collection [data]  ; {timestamp -> multiset}
  ...)
```

#### DD Operators as Protocols

```clojure
(defprotocol Operator
  (input [this collection])   ; send data to operator
  (output [this])             ; get results
  (step [this])              ; advance computation
  (frontier [this]))         ; current progress timestamp

(defrecord MapOperator [f downstream]
  Operator
  (input [this coll]
    (->> coll
         (map-differences f)
         (input downstream)))
  ...)

(defrecord FilterOperator [pred downstream]
  Operator
  (input [this coll]
    (->> coll
         (filter-differences pred)
         (input downstream)))
  ...)

(defrecord JoinOperator [left-arrangement right-arrangement downstream]
  Operator
  (input [this coll]
    ;; Incremental join using arrangements
    ...)
  ...)
```

#### Arrangements for Joins

```clojure
(deftype Arrangement [index]  ; sorted map for efficient lookups
  (insert [this key value timestamp multiplicity]
    ...)
  (lookup [this key]
    ;; returns values with timestamps and multiplicities
    ...)
  (range [this start-key end-key]
    ...))
```

#### Clojure Idioms

- **Immutable data**: Leverage persistent data structures
- **Protocols**: Define DD operators as protocols
- **Transducers**: Compose operators efficiently
- **Core.async**: Coordinate dataflow execution
- **Atoms/Refs**: Mutable operator state (arrangements, frontier)

### Development Phases

1. **Phase 1**: Core EAV storage, basic transactions, system-time only
2. **Phase 2**: Multi-dimensional time, dimension metadata, indexes per dimension
3. **Phase 3**: Basic Datalog query compilation and execution
4. **Phase 4**: Differential dataflow engine in Clojure (operators, arrangements, lattices)
5. **Phase 5**: Multi-dimensional lattice support in DD operators
6. **Phase 6**: Subscription system with DD integration
7. **Phase 7**: Transformation functions (pre/mid/post query)
8. **Phase 8**: Query optimization, subscription multiplexing
9. **Phase 9**: Production hardening, persistence, recovery
10. **Phase 10**: Distributed architecture preparation

## Open Questions

1. **Collection element deltas**: Exactly how to represent addition to vector at index N? Should we track indices or treat vectors as unordered multisets?

2. **Memory management**: Strategy for large diff accumulations in long-running subscriptions?

3. **GC for temporal data**: When/how to compact or delete old versions? Per-dimension retention policies?

4. **Query optimization**: Heuristics for Datalog -> DD compilation? Join ordering? Index selection with N dimensions?

5. **Subscription fan-out**: How to efficiently handle 1000s of subscriptions? Shared computation? Result caching?

6. **Backpressure**: What if subscribers can't keep up with updates? Buffering? Dropping? Alerting?

7. **Transaction conflicts**: Detection and retry logic for concurrent writes to same entities?

8. **DD state persistence**: How to persist differential dataflow operator state for recovery?

9. **Distribution**: How to partition EAV data across nodes? How to route queries? How to maintain DD arrangements in distributed setting?

10. **Multi-dimensional lattice join**: When joining on multiple time dimensions, what's the semantics? Inner join on matching timestamps? Or more sophisticated temporal joins?

11. **Dimension constraints validation**: When to validate? During transaction (reject invalid)? Or allow and warn?

12. **Derived dimensions**: Computed eagerly or lazily? Indexed or computed on-demand?

13. **Missing dimensions**: When comparing timestamps, how to handle missing dimensions? Treat as ⊥ (bottom)? Or incomparable?

14. **Subscription time dimension semantics**: If subscription watches `:time/shipped`, does update to `:time/delivered` trigger notification?

15. **Cross-dimensional queries**: Can you query for "orders where shipped-time is 2 days after ordered-time"? How to express efficiently?
