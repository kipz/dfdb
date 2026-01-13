(ns dfdb.query
  "Basic Datalog query engine."
  (:require [dfdb.index :as index]
            [dfdb.temporal :as temporal]
            [dfdb.recursive :as recursive]
            [clojure.set :as set]))

(def predicate-fns
  "Static map of supported predicate functions."
  {'= =
   '!= not=
   'not= not=
   '< <
   '> >
   '<= <=
   '>= >=
   '+ +
   '- -
   '* *
   '/ /
   'mod mod
   'quot quot
   'rem rem
   'inc inc
   'dec dec
   'max max
   'min min
   'str str
   'count count
   'empty? empty?
   'nil? nil?
   'some? some?
   'true? true?
   'false? false?
   'boolean boolean
   'not not
   'and (fn [& args] (every? identity args))
   'or (fn [& args] (some identity args))})

(defn variable?
  "Check if symbol is a query variable (starts with ?)."
  [x]
  (and (symbol? x) (.startsWith (name x) "?")))

(defn wildcard?
  "Check if symbol is a wildcard (_)."
  [x]
  (= x '_))

(defn parse-pattern
  "Parse a query pattern [e a v] into components."
  [pattern]
  (let [[e a v & rest] pattern]
    {:e e :a a :v v :modifiers (apply hash-map rest)}))
(defn match-pattern
  "Match a single pattern against the database.
  Returns set of binding maps."
  [db pattern bindings as-of-map]
  (let [{:keys [e a v modifiers]} (parse-pattern pattern)
        temporal-spec (temporal/parse-temporal-pattern-modifier modifiers)
        storage (:storage db)

        ;; If pattern has :at/ modifier, filter by THAT dimension only (not all dimensions)
        ;; This allows cross-attribute joins while still respecting temporal constraints
        effective-as-of (if temporal-spec
                          (select-keys as-of-map [(:dimension temporal-spec)])
                          as-of-map)

        ;; Resolve from bindings if bound
        e-bound? (contains? bindings e)
        v-bound? (contains? bindings v)

        e-val (if (and (variable? e) e-bound?) (get bindings e) e)
        a-val a  ; attributes are always constants in our queries
        v-val (if (and (variable? v) v-bound?) (get bindings v) v)

        ;; Check types after resolution
        e-is-var? (variable? e-val)
        v-is-var? (variable? v-val)
        v-is-wildcard? (wildcard? v)]

    ;; Sanity check: e-val should be number, variable, or string (entity ID)
    (when (and (not e-is-var?) (or (list? e-val) (seq? e-val)))
      (throw (ex-info "Invalid entity value - got list/seq (possible clause ordering issue)"
                      {:pattern pattern :e-val e-val :type (type e-val)})))

    (cond
      ;; Case 1: [1 :attr "value"] - all constants, just verify
      (and (not e-is-var?) (not v-is-var?) (not v-is-wildcard?))
      (let [start-key [:eavt e-val a-val v-val]
            end-key [:eavt e-val a-val (index/successor-value v-val)]
            datoms (index/scan-eavt storage start-key end-key)
            filtered (temporal/filter-datoms-temporal datoms effective-as-of)]
        (if (seq (filter (fn [[_k d]] (and (= (:v d) v-val) (= :assert (:op d)))) filtered))
          #{bindings}
          #{}))

      ;; Case 2: [1 :attr ?v] or [1 :attr _] - e constant, v variable/wildcard
      (and (not e-is-var?) (or v-is-var? v-is-wildcard?))
      (let [start-key [:eavt e-val a-val]
            end-key [:eavt e-val (index/successor-value a-val)]
            datoms (index/scan-eavt storage start-key end-key)
            filtered (temporal/filter-datoms-temporal datoms effective-as-of)
            ;; If :at/dimension specified, filter to datoms that HAVE that dimension
            with-dimension (if temporal-spec
                             (filter (fn [[_k d]] (get d (:dimension temporal-spec))) filtered)
                             filtered)
            ;; Get ALL datoms after temporal filtering
            all-datoms (map second with-dimension)]

        ;; Decision: If :at/ modifier present, return ALL values with dimension
        ;; (time-series use case). Otherwise, return only the LATEST value
        (if (seq all-datoms)
          (if temporal-spec
            ;; Time-series mode: group by value and return ALL asserted values with dimension
            (let [by-value (group-by :v all-datoms)
                  asserted-values (keep (fn [[val value-datoms]]
                                          (let [sorted (sort index/datom-comparator value-datoms)
                                                latest (first sorted)]
                                            (when (= :assert (:op latest))
                                              {:value val :datom latest})))
                                        by-value)]
              (set (mapcat (fn [{:keys [value datom]}]
                             ;; Handle multi-valued attributes (sets)
                             (if (and (set? value) v-is-var?)
                               (map (fn [elem]
                                      (let [new-bindings (assoc bindings v elem)]
                                        (temporal/bind-temporal-value new-bindings datom temporal-spec)))
                                    value)
                               (let [new-bindings (if v-is-wildcard?
                                                    bindings
                                                    (assoc bindings v value))
                                     result (temporal/bind-temporal-value new-bindings datom temporal-spec)]
                                 [result])))
                           asserted-values)))
            ;; Normal/temporal mode: group by value, take latest for each, return all from latest tx
            (let [by-value (group-by :v all-datoms)
                  latest-per-value (keep (fn [[val value-datoms]]
                                           (let [sorted (sort index/datom-comparator value-datoms)
                                                 latest (first sorted)]
                                             (when (= :assert (:op latest))
                                               {:value val :datom latest :tx-id (:tx-id latest)})))
                                         by-value)]
              (if (seq latest-per-value)
                (let [latest-tx (apply max (map :tx-id latest-per-value))
                      latest-values (filter #(= latest-tx (:tx-id %)) latest-per-value)]
                  (set (mapcat (fn [{:keys [value datom]}]
                                 ;; Handle multi-valued attributes (sets)
                                 (if (and (set? value) v-is-var?)
                                   (map (fn [elem]
                                          (let [new-bindings (assoc bindings v elem)]
                                            (temporal/bind-temporal-value new-bindings datom temporal-spec)))
                                        value)
                                   (let [new-bindings (if v-is-wildcard?
                                                        bindings
                                                        (assoc bindings v value))
                                         result (temporal/bind-temporal-value new-bindings datom temporal-spec)]
                                     [result])))
                               latest-values)))
                #{})))
          #{}))

      ;; Case 3: [?e :attr "value"] - e variable, v constant
      (and e-is-var? (not v-is-var?) (not v-is-wildcard?))
      (let [start-key [:avet a-val v-val]
            end-key [:avet a-val (index/successor-value v-val)]
            datoms (index/scan-avet storage start-key end-key)
            filtered (temporal/filter-datoms-temporal datoms effective-as-of)]
        (set (keep (fn [[_k datom]]
                     (when (and (= (:a datom) a-val)
                                (= (:v datom) v-val)
                                (= :assert (:op datom)))
                       (temporal/bind-temporal-value
                        (assoc bindings e (:e datom))
                        datom
                        temporal-spec)))
                   filtered)))

      ;; Case 4: [?e :attr ?v] or [?e :attr _] - e variable, v variable/wildcard
      e-is-var?
      (let [start-key [:aevt a-val]
            end-key [:aevt (index/successor-value a-val)]
            datoms (index/scan-aevt storage start-key end-key)
            filtered (temporal/filter-datoms-temporal datoms effective-as-of)
            ;; If :at/dimension specified, filter to datoms that HAVE that dimension
            with-dimension (if temporal-spec
                             (filter (fn [[_k d]] (get d (:dimension temporal-spec))) filtered)
                             filtered)
            all-datoms (map second with-dimension)]

        (if (seq all-datoms)
          (if temporal-spec
            ;; Time-series mode: return ALL asserted values with dimension
            (let [result-list (mapcat (fn [datom]
                                        (when (and (= (:a datom) a-val)
                                                   (= :assert (:op datom)))
                                          (let [new-bindings (assoc bindings e (:e datom))
                                                datom-value (:v datom)]
                                            (if (and (set? datom-value) v-is-var?)
                                              (map (fn [elem]
                                                     (let [with-value (assoc new-bindings v elem)]
                                                       (temporal/bind-temporal-value with-value datom temporal-spec)))
                                                   datom-value)
                                              (let [with-value (if (or v-is-wildcard? v-is-var?)
                                                                 (if v-is-wildcard?
                                                                   new-bindings
                                                                   (assoc new-bindings v datom-value))
                                                                 new-bindings)
                                                    final-binding (temporal/bind-temporal-value with-value datom temporal-spec)]
                                                [final-binding])))))
                                      all-datoms)]
              (set result-list))
            ;; Normal/temporal mode: group by EAV, take latest for each, then group by entity and take latest tx per entity
            (let [by-eav (group-by (juxt :e :a :v) all-datoms)
                  latest-per-eav (keep (fn [[_eav eav-datoms]]
                                         (let [sorted (sort index/datom-comparator eav-datoms)
                                               latest (first sorted)]
                                           (when (= :assert (:op latest))
                                             latest)))
                                       by-eav)
                  ;; Group by entity and for each entity, take only datoms from latest tx for that entity
                  by-entity (group-by :e latest-per-eav)
                  latest-per-entity (mapcat (fn [[_eid entity-datoms]]
                                              (let [latest-tx-for-entity (apply max (map :tx-id entity-datoms))]
                                                (filter #(= latest-tx-for-entity (:tx-id %)) entity-datoms)))
                                            by-entity)
                  result-list (mapcat (fn [datom]
                                        (when (= (:a datom) a-val)
                                          (let [new-bindings (assoc bindings e (:e datom))
                                                datom-value (:v datom)]
                                            (if (and (set? datom-value) v-is-var?)
                                              (map (fn [elem]
                                                     (let [with-value (assoc new-bindings v elem)]
                                                       (temporal/bind-temporal-value with-value datom temporal-spec)))
                                                   datom-value)
                                              (let [with-value (if (or v-is-wildcard? v-is-var?)
                                                                 (if v-is-wildcard?
                                                                   new-bindings
                                                                   (assoc new-bindings v datom-value))
                                                                 new-bindings)
                                                    final-binding (temporal/bind-temporal-value with-value datom temporal-spec)]
                                                [final-binding])))))
                                      latest-per-entity)]
              (set result-list)))
          #{}))

      :else
      (throw (ex-info "Unsupported pattern" {:pattern pattern})))))

(defn to-comparable
  "Convert value to comparable form (Dates to millis)."
  [v]
  (if (instance? java.util.Date v)
    (.getTime v)
    v))

(defn eval-predicate
  "Evaluate a predicate clause like [(> ?age 30)]."
  [pred-form bindings]
  (let [[pred-fn & args] pred-form
        resolved-args (map (fn [arg]
                             (if (variable? arg)
                               (get bindings arg)
                               arg))
                           args)
        ;; Convert Dates to millis for arithmetic/comparison
        comparable-args (map to-comparable resolved-args)]
    (try
      (if-let [f (get predicate-fns pred-fn)]
        (apply f comparable-args)
        (throw (ex-info "Unknown predicate function"
                        {:pred pred-fn
                         :supported-predicates (keys predicate-fns)})))
      (catch Exception _e
        false))))

(defn apply-predicate
  "Apply predicate to bindings, either filtering or binding new variables.
  Predicate can be:
  - Filter: [(> ?age 30)] - vector wrapping list
  - Binding: [(- ?a ?b) ?result] - vector with computation and output var"
  [bindings-set pred-form]
  ;; Check if binding predicate in vector form first
  (if (and (vector? pred-form) (variable? (last pred-form)))
    ;; Binding predicate: [(- ?a ?b) ?result]
    (let [output-var (last pred-form)
          computation-form (first pred-form)]  ; The list inside
      (set (map (fn [bindings]
                  (let [result (eval-predicate computation-form bindings)]
                    (assoc bindings output-var result)))  ; Bind even if false/nil
                bindings-set)))
    ;; Filter predicate or list form
    (let [pred-list (if (vector? pred-form) (first pred-form) pred-form)]
      (if (variable? (last pred-list))
        ;; List binding: (- ?a ?b ?result)
        (let [output-var (last pred-list)
              computation-form (butlast pred-list)]
          (set (keep (fn [bindings]
                       (let [result (eval-predicate computation-form bindings)]
                         (when result
                           (assoc bindings output-var result))))
                     bindings-set)))
        ;; Filter predicate
        (set (filter #(eval-predicate pred-list %) bindings-set))))))

(defn join-bindings
  "Join two sets of bindings on common variables using hash join.
  O(n+m) instead of O(n×m) for better performance on large result sets.
  Uses INNER JOIN semantics - if either input is empty, result is empty."
  [bindings1 bindings2]
  (if (or (empty? bindings1) (empty? bindings2))
    #{}  ;; Inner join: empty input produces empty output
    (let [common-vars (set/intersection (set (keys (first bindings1)))
                                        (set (keys (first bindings2))))]
      (if (empty? common-vars)
          ;; No common variables - Cartesian product (rare case)
        (set (for [b1 bindings1 b2 bindings2]
               (merge b1 b2)))
          ;; Hash join: build hash table from smaller side, probe with larger side
        (let [;; Choose smaller relation for build phase
              [build-side probe-side] (if (<= (count bindings1) (count bindings2))
                                        [bindings1 bindings2]
                                        [bindings2 bindings1])
                ;; Build phase: create hash table join-key -> [bindings]
              hash-table (reduce (fn [ht binding]
                                   (let [join-key (select-keys binding common-vars)]
                                     (update ht join-key (fnil conj []) binding)))
                                 {}
                                 build-side)]
            ;; Probe phase: look up each probe-side binding and merge matches
          (set (mapcat (fn [probe-binding]
                         (let [join-key (select-keys probe-binding common-vars)]
                           (when-let [matching-bindings (get hash-table join-key)]
                             (map #(merge % probe-binding) matching-bindings))))
                       probe-side)))))))

(defn predicate-clause?
  "Check if clause is a predicate (function call) vs pattern.
  Predicate: [(> ?age 30)] - first element is operator/function
  Pattern: [?e :attr ?v] - first element is entity (variable or constant)"
  [clause]
  (and (vector? clause)
       (let [first-elem (first clause)]
         (or (list? first-elem)  ; Nested list like [(> ?age 30)]
             ;; Symbol but NOT a variable (operators like >, <, etc.)
             (and (symbol? first-elem)
                  (not (variable? first-elem)))))))

(defn process-where-clause
  "Process a single where clause (pattern or predicate)."
  [db clause bindings-set as-of-map]
  (cond
    ;; Not clause: (not [pattern]) - CHECK THIS FIRST!
    (and (seq? clause) (= 'not (first clause)))
    (let [not-pattern (second clause)
          matching (set (mapcat (fn [bindings]
                                  (match-pattern db not-pattern bindings as-of-map))
                                bindings-set))
          ;; Project matching to only keys in original bindings before difference
          original-keys (when (seq bindings-set) (set (keys (first bindings-set))))
          matching-projected (if original-keys
                               (set (map #(select-keys % original-keys) matching))
                               matching)]
      (set/difference bindings-set matching-projected))

    ;; Predicate: [(> ?age 30)] - check before pattern
    (predicate-clause? clause)
    (apply-predicate bindings-set clause)

    ;; List predicate: (> ?age 30) without vector wrapper
    (list? clause)
    (apply-predicate bindings-set clause)

    ;; Pattern: [e a v] or [e a+ v] (recursive)
    (vector? clause)
    (let [[_e a _v] clause]
      (if (recursive/recursive-attribute? a)
        ;; Recursive pattern - still uses mapcat for now
        (if (empty? bindings-set)
          (recursive/match-recursive-pattern db clause {} as-of-map)
          (set (mapcat (fn [bindings]
                         (recursive/match-recursive-pattern db clause bindings as-of-map))
                       bindings-set)))
        ;; Normal pattern - optimized with hash join
        (if (empty? bindings-set)
          (match-pattern db clause {} as-of-map)
          ;; OPTIMIZATION: Instead of mapcat (O(n × pattern-cost)),
          ;; get all pattern results once and hash join (O(n + m))
          (let [pattern-results (match-pattern db clause {} as-of-map)]
            (join-bindings bindings-set pattern-results)))))

    :else
    (throw (ex-info "Unknown where clause type" {:clause clause}))))

(defn aggregate?
  "Check if find expression is an aggregate like (count ?e)."
  [expr]
  (and (list? expr)
       (contains? #{'count 'sum 'avg 'min 'max} (first expr))))

(defn parse-query
  "Parse query form into components."
  [query-form]
  (let [query-vec (if (map? query-form)
                    (:query query-form)
                    query-form)
        find-idx (.indexOf query-vec :find)
        where-idx (.indexOf query-vec :where)
        find-exprs (subvec query-vec (inc find-idx) where-idx)
        where-clauses (subvec query-vec (inc where-idx))

        ;; Separate aggregate from non-aggregate find expressions
        aggregates (filter aggregate? find-exprs)
        group-vars (remove aggregate? find-exprs)]
    {:find find-exprs
     :group-vars group-vars
     :aggregates aggregates
     :where where-clauses
     :as-of (when (map? query-form) (:as-of query-form))}))

(defn apply-aggregate
  "Apply aggregate function to values."
  [agg-fn values]
  (case agg-fn
    count (count values)
    sum (reduce + 0 values)
    avg (if (empty? values) 0.0 (/ (reduce + 0 values) (double (count values))))
    min (when (seq values) (apply min values))
    max (when (seq values) (apply max values))))

(defn project-with-aggregates
  "Project bindings with aggregation support."
  [bindings-set find-exprs group-vars aggregates]
  (if (empty? aggregates)
    ;; No aggregation - simple projection
    (set (map (fn [bindings]
                (vec (map (fn [expr]
                            (cond
                              ;; Variable - lookup in bindings
                              (variable? expr)
                              (get bindings expr)

                              ;; Expression binding - evaluate
                              (or (list? expr) (and (vector? expr) (list? (first expr))))
                              (let [expr-list (if (vector? expr) (first expr) expr)]
                                (eval-predicate expr-list bindings))

                              ;; Constant
                              :else
                              expr))
                          find-exprs)))
              bindings-set))
    ;; With aggregation - group and aggregate
    (let [grouped (if (empty? group-vars)
                    {nil bindings-set}  ; No grouping - aggregate all
                    (group-by (fn [bindings]
                                (vec (map (fn [expr]
                                            (cond
                                              (variable? expr)
                                              (get bindings expr)

                                              (or (list? expr) (and (vector? expr) (list? (first expr))))
                                              (let [expr-list (if (vector? expr) (first expr) expr)]
                                                (eval-predicate expr-list bindings))

                                              :else
                                              expr))
                                          group-vars)))
                              bindings-set))]
      (set (for [[group-key group-bindings] grouped]
             (let [agg-values (for [agg-expr aggregates]
                                (let [[agg-fn var] agg-expr
                                      values (map #(get % var) group-bindings)
                                      result (apply-aggregate agg-fn values)]
                                  result))]
               (vec (concat (or group-key []) agg-values))))))))

(defn project-bindings
  "Project bindings to find variables."
  [bindings-set find-vars]
  (set (map (fn [bindings]
              (vec (map #(get bindings %) find-vars)))
            bindings-set)))

(defn query
  "Execute Datalog query against database.
  Query form: [:find ?x ?y :where [?x :attr ?y]]
  Or with map: {:query [:find ...] :as-of {...}}"
  [db query-form]
  (let [{:keys [find where as-of group-vars aggregates]} (parse-query query-form)
        as-of-map (temporal/parse-as-of db as-of)

        ;; Process all where clauses
        bindings (reduce (fn [acc clause]
                           (process-where-clause db clause acc as-of-map))
                         #{}
                         where)]

    ;; Project with or without aggregation
    (project-with-aggregates bindings find group-vars aggregates)))
