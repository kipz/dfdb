(ns dfdb.dd.scan
  "Scan operators that incrementally maintain pattern results."
  (:require [dfdb.dd.multiset :as ms]
            [dfdb.dd.operator :as op]
            [dfdb.dd.arrangement :as arr]))

(defrecord PatternScanOperator [db pattern arrangement-atom state downstream]
  ;; Maintains arrangement of all bindings matching pattern
  ;; On delta, computes affected bindings and emits changes

  op/Operator
  (input [_this deltas timestamp]
    ;; Process transaction deltas
    (let [_changes (atom {:additions [] :retractions []})]

      ;; For each delta, determine if it affects this pattern
      (doseq [delta deltas]
        (let [{:keys [_entity _attribute _new-value _old-value _operation]} delta]

          ;; Check if delta matches pattern
          ;; TODO: Smart matching based on pattern structure
          ;; For now: re-scan affected entity

          ;; If entity is relevant, re-evaluate pattern for that entity
          ;; Compute what changed and emit difference
          ))

      ;; Forward changes to downstream
      (when downstream
        (let [diff-ms (ms/multiset {})]  ; TODO: Build from changes
          (op/input downstream diff-ms timestamp)))))

  (step [_this]
    false)

  (output [_this]
    (when downstream
      (op/output downstream)))

  (frontier [_this]
    (when downstream
      (op/frontier downstream)))

  op/Stateful
  (state [_this]
    {:arrangement @arrangement-atom}))

(defn make-pattern-scan-operator
  "Create a pattern scan operator that incrementally maintains pattern results."
  [db pattern downstream]
  (->PatternScanOperator
   db
   pattern
   (atom (arr/create-arrangement))
   (op/create-operator-state)
   downstream))

;; =============================================================================
;; Delta-Driven Incremental Updates
;; =============================================================================

(defn affected-bindings-for-delta
  "Compute which bindings are affected by a transaction delta.
  Returns {:additions [...] :retractions [...]} of binding changes."
  [_db _pattern _delta]
  ;; Simplified for now - would need sophisticated logic per pattern type
  ;; For pattern [?e :attr ?v]:
  ;;   - If delta is for :attr, affected entity is delta's entity
  ;;   - Compute old bindings (with old value)
  ;;   - Compute new bindings (with new value)
  ;;   - Return difference

  {:additions []
   :retractions []})

(defn incremental-pattern-update
  "Update pattern results incrementally based on delta.
  Returns difference (what changed in pattern results)."
  [_db pattern _arrangement delta]
  ;; This is the core of true differential dataflow
  ;; Instead of re-scanning everything, we:
  ;; 1. Identify which bindings are affected by this delta
  ;; 2. Remove old bindings from arrangement
  ;; 3. Add new bindings to arrangement
  ;; 4. Return the difference

  (let [{:keys [entity attribute new-value old-value _operation]} delta]

    ;; Example for pattern [?e :attr ?v]:
    (cond
      ;; Pattern matches this attribute
      (= attribute (second pattern))
      (let [old-binding (when old-value {(first pattern) entity (nth pattern 2) old-value})
            new-binding (when new-value {(first pattern) entity (nth pattern 2) new-value})]

        {:additions (if new-binding [new-binding] [])
         :retractions (if old-binding [old-binding] [])})

      ;; Pattern doesn't match - no change
      :else
      {:additions [] :retractions []})))

(comment
  "True incremental pattern scanning requires:
  - Pattern analysis to determine which deltas are relevant
  - Efficient arrangement updates
  - Change tracking per pattern
  - ~200-300 LOC for full implementation

  Current re-execution model is simpler and works correctly.
  This optimization matters for large datasets with many subscribers.")
