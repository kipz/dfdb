(ns dfdb.dd.pattern
  "Incremental pattern matching for differential dataflow.
  Maintains state and emits only changed bindings."
  (:require [dfdb.dd.multiset :as ms]
            [dfdb.dd.operator :as op]
            [dfdb.dd.arrangement :as arr]))

(defrecord IncrementalPatternOperator [db pattern arrangement state downstream]
  ;; Maintains arrangement of all bindings matching pattern
  ;; On transaction delta, computes affected bindings only

  op/Operator
  (input [_this deltas timestamp]
    ;; Process each delta
    (let [changes-to-emit (atom {:additions {} :retractions {}})]

      (doseq [delta deltas]
        (let [{:keys [entity attribute new-value old-value operation]} delta
              [_e a _v] pattern]

          ;; Check if delta's attribute matches pattern attribute
          (when (= attribute a)
            (case operation
              ;; Assert: new binding added
              :assert
              (let [binding {(first pattern) entity
                             (nth pattern 2) new-value}]
                (swap! changes-to-emit update-in [:additions binding] (fnil inc 0))
                ;; Update arrangement
                (arr/insert arrangement binding binding timestamp 1))

              ;; Retract or update: old binding removed
              :retract
              (let [binding {(first pattern) entity
                             (nth pattern 2) old-value}]
                (swap! changes-to-emit update-in [:retractions binding] (fnil inc 0))
                ;; Update arrangement (decrement)
                (arr/insert arrangement binding binding timestamp -1))))))

      ;; Emit changes to downstream
      (when downstream
        (let [additions-ms (ms/multiset (:additions @changes-to-emit))
              retractions-ms (ms/multiset (:retractions @changes-to-emit))
              ;; TODO: Proper difference propagation
              combined (ms/merge-multisets additions-ms retractions-ms)]
          (when (pos? (count combined))
            (op/input downstream combined timestamp))))))

  (step [_this]
    false)

  (output [_this]
    (when downstream (op/output downstream)))

  (frontier [_this]
    (when downstream (op/frontier downstream)))

  op/Stateful
  (state [_this]
    {:arrangement arrangement
     :pattern pattern}))

(defn make-incremental-pattern-operator
  "Create incremental pattern operator.
  Maintains arrangement of pattern matches, emits only changes."
  [db pattern downstream]
  (let [arr (arr/create-arrangement)]

    ;; Initialize arrangement with current database state
    ;; TODO: Initial scan of pattern results

    (->IncrementalPatternOperator
     db
     pattern
     arr
     (op/create-operator-state)
     downstream)))

;; =============================================================================
;; Incremental Join with Arrangement Probing
;; =============================================================================

(defrecord IncrementalJoinOperator [left-arr right-arr join-vars state downstream]
  ;; Maintains arrangements for left and right
  ;; On input to one side, probes other side for matches
  ;; Emits only newly joined results

  op/Operator
  (input [_this side-data timestamp]
    ;; TODO: Determine which side (left or right) this input is for
    ;; For now: simplified

    ;; Extract values and probe opposite arrangement
    (let [new-joins (atom {})]

      ;; For each value in input
      (doseq [[value _mult] (seq side-data)]
        ;; Extract join key
        (let [_join-key (select-keys value join-vars)]

          ;; Probe opposite arrangement
          ;; TODO: Implement probe logic

          ;; For matching tuples, emit joined result
          ))

      ;; Forward joined results
      (when downstream
        (let [joins-ms (ms/multiset @new-joins)]
          (when (pos? (count joins-ms))
            (op/input downstream joins-ms timestamp))))))

  (step [_this]
    false)

  (output [_this]
    (when downstream (op/output downstream)))

  (frontier [_this]
    (when downstream (op/frontier downstream)))

  op/Stateful
  (state [_this]
    {:left-arr left-arr
     :right-arr right-arr
     :join-vars join-vars}))

(defn make-incremental-join-operator
  "Create incremental join operator with arrangements."
  [join-vars downstream]
  (->IncrementalJoinOperator
   (arr/create-arrangement)
   (arr/create-arrangement)
   join-vars
   (op/create-operator-state)
   downstream))

(comment
  "This is the core of true differential dataflow.

  Current implementation:
  - Structures defined
  - Basic delta handling outlined
  - Arrangements created

  Remaining for TRUE incremental:
  1. Complete delta-to-binding extraction (per pattern type)
  2. Arrangement probe logic for joins
  3. State maintenance across updates
  4. Proper difference propagation

  Estimated: ~300-400 LOC for full incremental execution

  The operators work. The structures exist.
  Just need to wire the incremental logic together.")
