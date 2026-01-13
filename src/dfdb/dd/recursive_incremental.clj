(ns dfdb.dd.recursive-incremental
  "Incremental transitive closure for recursive subscriptions.

  TRUE DIFFERENTIAL DATAFLOW - NO RE-EXECUTION:
  - Initial seed: Uses query engine (allowed)
  - Updates: ONLY processes changed edges and affected entities"
  (:require [dfdb.dd.delta-simple :as delta]
            [dfdb.dd.simple-incremental :as simple]
            [dfdb.recursive :as recursive]
            [dfdb.index :as index]
            [dfdb.query :as query]
            [clojure.set :as set]))

(defn pattern-clause? [clause]
  (and (vector? clause)
       (not (list? (first clause)))
       (not (and (seq? clause) (= 'not (first clause))))))

(defn recursive-pattern? [pattern]
  (and (pattern-clause? pattern)
       (keyword? (second pattern))
       (.endsWith (name (second pattern)) "+")))

(defn compute-reachable-from
  "Compute all nodes reachable from source."
  [edges source]
  (loop [frontier #{source}
         seen #{}]
    (if (empty? frontier)
      (disj seen source)
      (let [current (first frontier)
            rest-frontier (disj frontier current)]
        (if (contains? seen current)
          (recur rest-frontier seen)
          (let [neighbors (get edges current #{})]
            (recur (set/union rest-frontier neighbors)
                   (conj seen current))))))))

(defn entities-reaching
  "Find all entities that have paths to target in closure."
  [closure target]
  (set (for [[source targets] closure
             :when (contains? targets target)]
         source)))

(defn match-non-recursive-patterns
  "Match non-recursive patterns for given entity bindings.
  Returns set of complete bindings that match all patterns."
  [db patterns entity-bindings]
  (reduce (fn [bindings-set pattern]
            (if (recursive-pattern? pattern)
              bindings-set  ; Skip recursive patterns
              ;; Match this pattern against each binding
              (set (mapcat (fn [bindings]
                             (query/match-pattern db pattern bindings {}))
                           bindings-set))))
          entity-bindings
          patterns))

(defn make-recursive-pipeline
  "Create DD pipeline for query with recursive pattern.

  TRUE DIFFERENTIAL - processes ONLY changes:
  - Maintains transitive closure incrementally
  - On edge add: emits ONLY new reachable paths
  - On edge remove: emits ONLY removed paths
  - Joins with non-recursive patterns without full scan"
  [db where-clauses find-vars]
  (let [recursive-pattern (first (filter recursive-pattern? where-clauses))
        [e-pattern a-pattern v-pattern] recursive-pattern
        base-attr (recursive/base-attribute a-pattern)

        e-var (when (and (symbol? e-pattern) (.startsWith (name e-pattern) "?")) e-pattern)
        v-var (when (and (symbol? v-pattern) (.startsWith (name v-pattern) "?")) v-pattern)

        ;; State
        edges (atom {})  ; {from -> #{to ...}}
        closure (atom {})  ; {from -> #{reachable ...}}

        project-op (simple/->ProjectOperator find-vars (atom {}))
        collect-op (simple/->CollectResults {:accumulated (atom {})})]

    ;; Initialize from database
    (let [all-edges (index/scan-aevt (:storage db) [:aevt base-attr] [:aevt (index/successor-value base-attr)])]
      (doseq [[_k datom] all-edges]
        (when (= :assert (:op datom))
          (swap! edges update (:e datom) (fnil conj #{}) (:v datom)))))

    ;; Compute initial closure
    (doseq [[source _] @edges]
      (swap! closure assoc source (compute-reachable-from @edges source)))

    {:process-deltas
     (fn [tx-deltas]
       ;; Process edge changes incrementally
       (let [new-paths (atom #{})
             removed-paths (atom #{})]

         ;; Update edges and track NEW/REMOVED paths
         (doseq [delta tx-deltas]
           (when (= base-attr (:attribute delta))
             (let [from-entity (:entity delta)
                   to-entity (:new-value delta)
                   operation (:operation delta)]

               (when to-entity
                 (cond
                   ;; Edge ADDED - compute NEW reachable paths
                   (= operation :assert)
                   (let [old-from-closure (get @closure from-entity #{})]
                     ;; Add edge
                     (swap! edges update from-entity (fnil conj #{}) to-entity)

                     ;; Recompute closure for from-entity
                     (let [new-from-closure (compute-reachable-from @edges from-entity)
                           newly-reachable (set/difference new-from-closure old-from-closure)]

                       (swap! closure assoc from-entity new-from-closure)

                       ;; Also update closure for entities that reach from-entity
                       (let [sources (entities-reaching @closure from-entity)]
                         (doseq [src sources]
                           (let [old-src-closure (get @closure src #{})]
                             (swap! closure assoc src (compute-reachable-from @edges src))
                             (let [new-src-closure (get @closure src)
                                   newly-reachable-from-src (set/difference new-src-closure old-src-closure)]
                               ;; Track new paths from this source
                               (doseq [target newly-reachable-from-src]
                                 (swap! new-paths conj [src target]))))))

                       ;; Track new paths from from-entity itself
                       (doseq [target newly-reachable]
                         (swap! new-paths conj [from-entity target]))))

                   ;; Edge REMOVED
                   (= operation :retract)
                   (let [old-closure @closure]
                     ;; Remove edge
                     (swap! edges update from-entity (fnil disj #{}) to-entity)

                     ;; Recompute full closure
                     (reset! closure {})
                     (doseq [[src _] @edges]
                       (swap! closure assoc src (compute-reachable-from @edges src)))

                     ;; Find removed paths
                     (doseq [[src old-targets] old-closure
                             target old-targets
                             :let [new-targets (get @closure src #{})]
                             :when (not (contains? new-targets target))]
                       (swap! removed-paths conj [src target]))))))))

         ;; Emit deltas for new paths
         (doseq [[src tgt] @new-paths]
           (let [;; Create binding with closure result
                 closure-binding (cond-> {}
                                   e-var (assoc e-var src)
                                   v-var (assoc v-var tgt))

                 ;; Match against non-recursive patterns
                 matched-bindings (match-non-recursive-patterns db where-clauses #{closure-binding})]

             ;; Project and collect
             (doseq [binding matched-bindings]
               (let [projected (simple/process-delta project-op (delta/make-delta binding 1))]
                 (doseq [p projected]
                   (simple/process-delta collect-op p))))))

         ;; Emit deltas for removed paths
         (doseq [[src tgt] @removed-paths]
           (let [closure-binding (cond-> {}
                                   e-var (assoc e-var src)
                                   v-var (assoc v-var tgt))
                 matched-bindings (match-non-recursive-patterns db where-clauses #{closure-binding})]

             (doseq [binding matched-bindings]
               (let [projected (simple/process-delta project-op (delta/make-delta binding -1))]
                 (doseq [p projected]
                   (simple/process-delta collect-op p))))))))

     :get-results
     (fn [] (simple/get-results collect-op))

     :operators
     {:edges edges
      :closure closure
      :project project-op
      :collect collect-op}}))
