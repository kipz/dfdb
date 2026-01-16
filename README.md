# dfdb - Multi-Dimensional Temporal Database with Differential Dataflow

**Clojure implementation with TRUE differential dataflow subscriptions**

A temporal database combining the best ideas from [Datomic](https://www.datomic.com), [DataScript](https://github.com/tonsky/datascript), [Datalevin](https://github.com/juji-io/datalevin), and [Differential Dataflow](https://github.com/TimelyDataflow/differential-dataflow).

dfdb builds on the foundation of these excellent projects:
- **Datomic**: Pioneered immutable databases with time-based queries and the elegant Datalog query language
- **DataScript**: Brought Datomic's query semantics to ClojureScript with a clean, accessible implementation
- **Datalevin**: Extended DataScript with durable storage and LMDB integration
- **Differential Dataflow**: Frank McSherry's groundbreaking work on incremental computation with multisets and timestamps

**Test Command**: `./run-tests.sh`

---

## Features

### Core Database
- Complete EAV storage with 4 Datomic-style indexes
- Full Datalog query engine (patterns, joins, aggregates, recursive, NOT)
- Multi-dimensional time (N dimensions)
- DataScript compatible query semantics
- Temporal queries with hybrid semantics
- Cardinality-one and cardinality-many support
- Collection operations (`:db/assoc`, `:db/conj`)
- Expression bindings in find clauses
- Temporal delta generation

### TRUE Differential Dataflow
- Multisets, differences, timestamps
- DD operators (map, filter, aggregate, join)
- O(changes) incremental execution
- NO re-execution fallback
- Incremental transitive closure

**Differential dataflow subscriptions for**:
- Simple pattern subscriptions
- Multi-pattern joins
- Predicate filtering
- Aggregate subscriptions (grouped & ungrouped)
- Recursive queries

**Performance** (vs naive re-execution):
- Large-scale joins (5000+ nodes): **12.2x faster**
- Complex multi-way joins: **2.6-2.7x faster**
- Join+aggregates: **2.0-2.3x faster**
- Multi-join+aggregates: **2.4x faster**
- Pure joins: **1.9-2.5x faster**

### Advanced Aggregates
- **Statistical**: `median`, `variance`, `stddev`
- **Distinct counting**: `count-distinct`
- **Collection aggregates**: `collect`, `sample`, `rand`
- All incremental with O(1) or O(log n) updates
- Full Datomic aggregate compatibility

---

## 🚀 Usage

```clojure
(require '[dfdb.core :refer :all])

;; Database operations
(def db (create-db))
(transact! db [{:user/name "Alice" :user/age 30}])
(query db '[:find ?name (avg ?age) :where [?e :name ?name] [?e :age ?age]])

;; Collection operations
(transact! db [[:db/assoc [:user/id 1] :settings :theme "dark"]])
(transact! db [[:db/conj [:cart/id "C1"] :items "item-123"]])
(transact! db [[:db/add [:user/id 1] :roles :admin]])

;; Multi-dimensional time
(transact! db {:tx-data [{:order/id "O1" :order/status :shipped}]
               :time-dimensions {:time/shipped #inst "2026-01-15"}})

;; Time-series queries
(query db '[:find ?time ?value
            :where
            [?s :sensor/id "TEMP-1"]
            [?s :sensor/value ?value :at/measured ?time]])

;; Subscriptions with TRUE DD
(subscribe db {:query '[:find ?name :where [?e :user/name ?name]]
               :callback (fn [diff]
                          ;; {:additions #{["Alice"]} :retractions #{}}
                          (update-ui diff))})

;; Aggregates
(subscribe db {:query '[:find ?customer (sum ?total)
                        :where
                        [?order :order/customer ?customer]
                        [?order :order/total ?total]]
               :callback update-totals})

;; Advanced aggregates
(query db '[:find (median ?price) (stddev ?price) (variance ?price)
            :where [?product :product/price ?price]])

(query db '[:find ?category (count-distinct ?product)
            :where
            [?product :product/category ?category]
            [?product :product/id ?product]])

(query db '[:find ?user (collect ?tag)
            :where [?user :user/tags ?tag]])

(query db '[:find (sample 10 ?user)
            :where [?user :user/active? true]])

;; Pull API
(require '[dfdb.pull :as pull])

;; Pull all attributes
(pull/pull db 1 '[*])
;; => {:db/id 1 :user/name "Alice" :user/age 30 :user/email "alice@example.com"}

;; Pull specific attributes
(pull/pull db 1 '[:user/name :user/age])
;; => {:db/id 1 :user/name "Alice" :user/age 30}

;; Pull with nesting
(pull/pull db 1 '[:order/id {:order/customer [:customer/name :customer/email]}])
;; => {:db/id 1 :order/id "O1" :order/customer {:db/id 2 :customer/name "Bob" :customer/email "bob@example.com"}}

;; Pull with reverse lookup
(pull/pull db 1 '[:user/name {:user/_manager [:user/name]}])
;; => {:db/id 1 :user/name "Alice" :user/_manager [{:db/id 2 :user/name "Bob"} {:db/id 3 :user/name "Carol"}]}

;; Pull in queries
(query db '[:find (pull ?e [:user/name :user/age])
            :where [?e :user/age ?age] [(> ?age 25)]])
;; => #{[{:db/id 1 :user/name "Alice" :user/age 30}] [{:db/id 2 :user/name "Bob" :user/age 28}]}

;; Rules - Reusable query fragments
(def adult-rules
  '[[(adult? ?person)
     [?person :person/age ?age]
     [(>= ?age 18)]]])

(query db '[:find ?name
            :in $ %
            :where
            (adult? ?p)
            [?p :person/name ?name]]
       adult-rules)
;; => #{["Alice"] ["Carol"]}

;; Multiple rule definitions act like OR
(def contact-rules
  '[[(contact ?person ?info)
     [?person :person/email ?info]]
    [(contact ?person ?info)
     [?person :person/phone ?info]]])

(query db '[:find ?name ?contact
            :in $ %
            :where
            (contact ?p ?contact)
            [?p :person/name ?name]]
       contact-rules)
;; Returns both email AND phone contacts

;; or-join - Logical OR with variable scoping
(query db '[:find ?person ?contact
            :where
            [?person :person/name ?name]
            (or-join [?person ?contact]
              [?person :person/email ?contact]
              [?person :person/phone ?contact])])
;; Returns BOTH email and phone for each person who has them

;; not-join - NOT with explicit variable binding
(query db '[:find ?name
            :where
            [?product :product/name ?name]
            [?product :product/status :available]
            (not-join [?product]
              [?order :order/product ?product]
              [?order :order/status :pending])])
;; Returns available products with NO pending orders

;; With transformation
(subscribe db {:query '[:find ?email ?score
                        :where
                        [?u :user/email ?email]
                        [?u :user/score ?score]]
               :transform-fn (fn [diff]
                              (update diff :additions
                                     #(set (filter (fn [[_ score]] (> score 80)) %))))
               :callback notify-high-scorers})

;; Recursive
(subscribe db {:query '[:find ?name
                        :where
                        [?ceo :emp/name "CEO"]
                        [?report :emp/reports-to+ ?ceo]
                        [?report :emp/name ?name]]
               :callback update-org-chart})
```

---

## License

MIT License - see LICENSE file for details.
