# Generative Stress Tests

## Overview

This document describes the generative stress test suite for the dfdb query and differential dataflow engine. These tests use property-based testing with `clojure.test.check` to generate random test scenarios and verify system invariants.

## Test File

- **Location**: `test/dfdb/generative_stress_test.clj`
- **Run Script**: `./run-generative-tests.sh`

## Test Categories

### Property-Based Tests (Generative)

These tests use `test.check` to generate random inputs and verify properties hold across many scenarios:

1. **subscription-matches-query-test** (30 tests)
   - **Property**: Subscription final state matches direct query results
   - **Approach**: Applies random transactions, compares subscription incremental results with direct query
   - **Validates**: Differential dataflow correctness

2. **add-then-retract-is-empty-test** (50 tests)
   - **Property**: Adding datoms then retracting them yields empty results
   - **Approach**: Generates random datoms, adds then retracts all, verifies database is empty
   - **Validates**: Retraction correctness, database cleanup

3. **high-churn-correctness-test** (30 tests)
   - **Property**: Database remains correct under high churn (many adds/retracts)
   - **Approach**: Generates 10-100 random add/retract operations, verifies final state matches expected
   - **Validates**: Correctness under rapid changes, transaction ordering

4. **query-result-size-bounded-test** (50 tests)
   - **Property**: Query results never exceed total datoms in database
   - **Approach**: Adds random transactions, queries all data, verifies result count
   - **Validates**: Query engine bounds, no result duplication

5. **concurrent-subscriptions-consistency-test** (30 tests)
   - **Property**: Multiple subscriptions on same query receive identical updates
   - **Approach**: Creates 3 subscriptions, applies transactions, verifies all receive same updates
   - **Validates**: Subscription consistency, broadcast correctness

### Stress Tests (Specific Scenarios)

These tests target specific high-load or complex scenarios:

1. **stress-test-large-dataset**
   - Creates 1000 entities with multiple attributes
   - Queries with filters across large dataset
   - **Validates**: Scalability with large data volumes

2. **stress-test-deep-joins**
   - Tests joins across 4+ patterns (tags → posts → authors)
   - **Validates**: Complex query plans, multi-hop joins

3. **stress-test-aggregation-correctness**
   - Tests sum and count aggregations
   - Verifies aggregate values across different groupings
   - **Validates**: Aggregation accuracy, group-by correctness

4. **stress-test-subscription-with-rapid-updates**
   - Rapidly adds 100 entities in succession
   - Verifies subscription receives all 100 additions
   - **Validates**: Subscription performance under rapid changes

5. **stress-test-complex-predicate-filters**
   - Tests multiple predicates (>, <) in combination
   - 50 entities with complex filtering logic
   - **Validates**: Predicate evaluation, filter combinations

6. **stress-test-retraction-cascade**
   - Tests retractions causing join results to appear/disappear
   - Adds data, retracts part causing join to break, re-adds
   - **Validates**: Incremental join maintenance, cascade correctness

## Generators

The test suite includes several generators for creating random test data:

- **entity-id-gen**: Generates entity IDs from pool of 1-20 for interesting overlaps
- **attribute-gen**: Generates from predefined attribute pool (user/product/order/etc.)
- **value-gen**: Generates strings, numbers, or booleans
- **datom-gen**: Generates [entity attribute value] tuples
- **transaction-gen**: Generates transactions with multiple datoms
- **mixed-transaction-gen**: Generates mix of additions and retractions

## Running the Tests

### Run all generative tests
```bash
./run-generative-tests.sh
```

### Run specific test namespace
```bash
clojure -X:unit-test cognitect.test-runner.api/test \
  :dirs '["test"]' \
  :patterns '["dfdb.generative-stress-test"]'
```

### Run all tests (unit + generative)
```bash
./run-tests.sh
```

## Test Statistics

- **Total property-based tests**: 5 (executing 190 generated scenarios)
- **Total stress tests**: 6 (specific scenarios)
- **Total assertions**: 19
- **Typical execution time**: ~400ms for all tests

## Coverage

The generative tests provide coverage for:

- ✓ Basic CRUD operations with random data
- ✓ Query engine with various patterns
- ✓ Differential dataflow operators
- ✓ Subscription system under various load patterns
- ✓ Transaction atomicity and ordering
- ✓ Retraction correctness
- ✓ Aggregation accuracy
- ✓ Join correctness (simple and complex)
- ✓ Predicate evaluation
- ✓ Large dataset handling
- ✓ High-churn scenarios

## Future Enhancements

Potential additions to the test suite:

1. **Recursive query stress tests**: Generate random hierarchical data, test recursive queries
2. **Multi-dimensional time**: Generate transactions with different time dimensions
3. **Storage backend stress**: Test RocksDB backend with generative scenarios
4. **Concurrent transaction stress**: Multiple threads applying transactions simultaneously
5. **Memory pressure tests**: Test behavior with large datasets approaching memory limits
6. **Schema evolution**: Generate transactions that add new attributes over time
