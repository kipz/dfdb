# Storage Backend Architecture

## Current Storage Protocol

The existing `Storage` protocol in `src/dfdb/storage.clj` provides a clean abstraction:

```clojure
(defprotocol Storage
  (put [this key value])           ; Store value at key
  (get-value [this key])            ; Retrieve value at key
  (scan [this start-key end-key])   ; Range scan [start, end)
  (delete [this key])               ; Delete value at key
  (batch-write [this ops]))         ; Atomic batch operations
```

### Usage Patterns

**Keys**: Vectors representing index coordinates (e.g., `[:eavt e a v tx-id]`)

**Values**: EDN datoms (maps with `:e`, `:a`, `:v`, `:t`, `:tx-id`, `:op`)

**Critical Requirements**:
- **Ordered keys**: Lexicographic ordering essential for range scans
- **Range scans**: Heavy usage via `scan` for all index queries
- **Atomicity**: `batch-write` must be atomic (used for transactions)
- **Performance**: Index operations are on critical path

### Current Implementation

**MemoryStorage** (`src/dfdb/storage.clj:60-94`):
- In-memory sorted map
- Good for testing and development
- Not suitable for production (no persistence, no durability)

## Proposed Protocol Enhancements

Add minimal extensions to support cloud-native backends:

```clojure
(defprotocol Storage
  ;; Existing operations
  (put [this key value])
  (get-value [this key])
  (scan [this start-key end-key])
  (delete [this key])
  (batch-write [this ops])

  ;; New operations
  (close [this]
    "Close storage backend and release resources.")

  (snapshot [this]
    "Create a consistent snapshot for reads. Returns snapshot handle.")

  (restore-snapshot [this snapshot-id]
    "Restore database from snapshot.")

  (compact [this]
    "Trigger compaction (optional, backend-specific)."))

;; Optional for streaming large scans
(defprotocol StreamingStorage
  (scan-stream [this start-key end-key opts]
    "Returns lazy seq or channel for memory-efficient streaming."))
```

**Design Principle**: Keep the protocol minimal. Most backends can implement the core 5 operations. Extended features are optional.

## Recommended Backends

### Tier 1: Production Single-Node

#### 1. RocksDB (Embedded)

**Recommendation**: Primary choice for single-node deployments

**Pros**:
- ✅ Native ordered key-value store with excellent range scans
- ✅ Atomic batch writes (WriteBatch)
- ✅ Embedded (no separate process)
- ✅ Snapshots for consistent reads
- ✅ Proven at scale (used by MySQL, Kafka, etc.)
- ✅ Local development requires no containers

**Cons**:
- ❌ Requires native libraries (JNI)
- ❌ Single-node only (not distributed)

**Container Support**: Not needed (embedded library)

**Clojure Library**: [clj-rocksdb](https://github.com/mpenet/clj-rocksdb)

**Implementation Notes**:
```clojure
;; Key encoding: serialize vectors to byte arrays (sorted correctly)
;; Value encoding: EDN -> bytes via nippy or transit
;; Batch: RocksDB WriteBatch for atomicity
;; Snapshots: RocksDB native snapshots
```

**Cloud Integration**:
- Run RocksDB locally
- Periodic snapshots to S3 for backups
- Write-ahead log (WAL) shipping to S3 for point-in-time recovery

#### 2. PostgreSQL (Relational)

**Recommendation**: Strong alternative for teams preferring SQL databases

**Pros**:
- ✅ B-tree indexes support range scans
- ✅ ACID transactions built-in
- ✅ Widely understood and supported
- ✅ Cloud-native (AWS RDS, GCP Cloud SQL, etc.)
- ✅ Local development via Docker
- ✅ Excellent tooling and monitoring

**Cons**:
- ⚠️ Need to serialize keys/values (JSONB or BYTEA)
- ⚠️ Higher latency than embedded stores
- ⚠️ More operational overhead than embedded

**Container Support**:
```bash
docker run -e POSTGRES_PASSWORD=dev -p 5432:5432 postgres:16-alpine
```

**Clojure Library**: [next.jdbc](https://github.com/seancorfield/next-jdbc)

**Schema**:
```sql
CREATE TABLE dfdb_index (
  key_bytes BYTEA PRIMARY KEY,
  value_bytes BYTEA NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_range_scan ON dfdb_index (key_bytes);
```

**Implementation Notes**:
- Encode vector keys to sortable byte arrays (BYTEA)
- Store datoms as JSONB or serialized bytes
- `batch-write` uses PostgreSQL transactions
- `scan` uses `WHERE key_bytes >= ? AND key_bytes < ?`

### Tier 2: Cloud-Native Distributed

#### 3. FoundationDB (Distributed)

**Recommendation**: Best choice for distributed, cloud-native deployment

**Pros**:
- ✅ Ordered key-value store with excellent range scans
- ✅ Distributed ACID transactions
- ✅ Strong consistency guarantees
- ✅ Used in production (Apple, Snowflake)
- ✅ Local development via Docker
- ✅ Built for cloud-scale

**Cons**:
- ⚠️ More complex than needed for single-node
- ⚠️ Requires cluster setup (minimum 3 nodes for production)
- ⚠️ Learning curve for operational aspects

**Container Support**:
```bash
docker run -p 4500:4500 foundationdb/foundationdb:7.1.38
```

**Clojure Library**: [foundationdb-clj](https://github.com/vedang/clj-fdb)

**Implementation Notes**:
- Use FDB's built-in ordered key-value model
- Transaction support maps directly to `batch-write`
- Directory layer for namespace isolation
- Range scans via `getRange`

#### 4. TiKV (Distributed KV)

**Recommendation**: Good alternative to FoundationDB

**Pros**:
- ✅ Distributed ordered key-value store
- ✅ ACID transactions
- ✅ Rust-based (good performance)
- ✅ Local development via Docker Compose
- ✅ Active development (CNCF project)

**Cons**:
- ⚠️ Less mature than FoundationDB
- ⚠️ Requires PD (Placement Driver) cluster

**Container Support**:
```bash
# Docker Compose with TiKV + PD
docker-compose up tikv
```

**Clojure Library**: Java client available, needs Clojure wrapper

### Tier 3: Managed Cloud Services

#### 5. DynamoDB (AWS)

**Recommendation**: Use only if locked into AWS ecosystem

**Pros**:
- ✅ Fully managed, serverless
- ✅ Local development (DynamoDB Local)
- ✅ Zero operational overhead

**Cons**:
- ⚠️ Range scans limited to single partition key
- ⚠️ Would need multiple tables (one per index type)
- ⚠️ Cost can be high for scan-heavy workloads
- ⚠️ Query patterns limited by partition/sort key design
- ⚠️ Eventual consistency by default

**Container Support**:
```bash
docker run -p 8000:8000 amazon/dynamodb-local
```

**Implementation Strategy**:
```
Table: dfdb_eavt
  Partition Key: entity (or entity prefix)
  Sort Key: attribute-value-txid (concatenated)

Table: dfdb_aevt
  Partition Key: attribute
  Sort Key: entity-value-txid

Table: dfdb_avet
  Partition Key: attribute
  Sort Key: value-entity-txid

Table: dfdb_vaet
  Partition Key: value
  Sort Key: attribute-entity-txid
```

**Challenge**: True range scans across partition keys not supported. Would need careful key design or scatter-gather queries.

## Implementation Roadmap

### Phase 1: Enhance Protocol (Current)
- Add `close`, `snapshot`, `compact` to protocol
- Keep backward compatibility with MemoryStorage
- Update `db.clj` to accept storage backend as parameter

### Phase 2: RocksDB Backend
- Implement `RocksDBStorage`
- Key/value serialization (Nippy or Transit)
- Batch write via WriteBatch
- Snapshot support
- Local persistence and recovery

### Phase 3: PostgreSQL Backend
- Implement `PostgresStorage`
- Schema creation scripts
- Key/value encoding for BYTEA
- Transaction mapping
- Migration from MemoryStorage

### Phase 4: Cloud Backups
- S3 integration for RocksDB snapshots
- Automated backup schedules
- Point-in-time recovery

### Phase 5: Distributed (Optional)
- FoundationDB backend
- Cluster deployment guides
- Distributed query optimization

## Backend Selection Guide

**Choose RocksDB if**:
- Single-node deployment
- Embedded database preferred
- Maximum performance needed
- Willing to manage backups to S3

**Choose PostgreSQL if**:
- Team familiar with PostgreSQL
- Prefer SQL database operational model
- Want managed cloud options (RDS, Cloud SQL)
- Need standard database tooling

**Choose FoundationDB if**:
- Need distributed deployment
- Multi-region requirements
- Strong consistency across data centers
- Have operational expertise

**Choose MemoryStorage if**:
- Testing only
- Prototyping
- CI/CD environments

## Key Encoding Strategy

All backends must maintain lexicographic ordering of vector keys.

**Encoding Requirements**:
1. Type-preserving: Different types must sort correctly
2. Order-preserving: `compare-keys [a b] < [a c]` must hold after encoding
3. Prefix-free: No key can be a prefix of another

**Recommended Approach**:
```clojure
(defn encode-value [v]
  (cond
    (nil? v) [0x00]
    (number? v) [0x01 <encoded-number>]
    (string? v) [0x02 <utf8-bytes> 0x00]
    (keyword? v) [0x03 <namespace-bytes> 0x00 <name-bytes> 0x00]
    (inst? v) [0x04 <long-millis>]
    ;; Add more types as needed
    ))

(defn encode-key [key-vector]
  (byte-array
    (mapcat encode-value key-vector)))
```

**Library**: Consider [byte-streams](https://github.com/clj-commons/byte-streams) or implement custom encoding.

## Configuration

Add storage configuration to database initialization:

```clojure
;; Current
(def db (create-db))

;; Proposed
(def db (create-db {:storage {:type :rocksdb
                              :path "/var/lib/dfdb/data"
                              :options {:compression :snappy}}}))

(def db (create-db {:storage {:type :postgres
                              :connection-uri "postgresql://localhost/dfdb"}}))

(def db (create-db {:storage {:type :foundationdb
                              :cluster-file "/etc/fdb/fdb.cluster"}}))
```

## Testing Strategy

**Unit Tests**: Each backend must pass the same test suite:
```clojure
(deftest storage-conformance-tests
  (testing "put and get"
    (let [storage (create-test-storage)]
      ...))

  (testing "range scan"
    ...)

  (testing "batch write atomicity"
    ...)

  (testing "key ordering"
    ...))
```

**Integration Tests**: Test with actual backend containers

**Property-Based Tests**: Use `test.check` to verify ordering properties

## Performance Benchmarks

Track metrics for each backend:
- Single put/get latency (p50, p95, p99)
- Range scan throughput (MB/s)
- Batch write latency
- Storage overhead (bytes per datom)

## Migration Path

Support migration between backends:

```clojure
(defn migrate-storage [source-db target-storage]
  ;; Stream all data from source
  ;; Write to target in batches
  ;; Verify checksums
  )
```

## Open Questions

1. Should we support pluggable serialization (EDN vs Transit vs Nippy)?
2. Do we need streaming `scan` for very large result sets?
3. Should `batch-write` support read-your-writes within batch?
4. How to handle schema evolution of datom format?
5. Should we expose backend-specific tuning options?
