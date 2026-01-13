(ns dfdb.dd.join
  "Join operator for differential dataflow."
  (:require [clojure.set :as set]
            [dfdb.dd.multiset :as ms]
            [dfdb.dd.operator :as op]
            [dfdb.dd.arrangement :as arr]))

(defrecord JoinOperator [left-key-fn right-key-fn merge-fn state downstream]
  ;; left-key-fn: extract join key from left value
  ;; right-key-fn: extract join key from right value
  ;; merge-fn: combine left and right values
  ;; state: maintains arrangements for both sides

  op/Operator
  (input [_this coll timestamp]
    ;; This is a simplified join - full DD join would track which side the input came from
    ;; For now, assume inputs are tagged with :left or :right
    ;; Real implementation would have separate input methods

    ;; Build arrangement from collection
    (let [arrangement (arr/arrangement-from-collection coll left-key-fn timestamp)]

      ;; Store for this timestamp
      (swap! (:arrangements state) assoc timestamp arrangement)

      ;; For each value in input, join with opposite side
      ;; (Simplified - real DD would maintain separate left/right arrangements)
      (let [joined (ms/empty-multiset)]
        ;; TODO: Proper join logic with separate left/right arrangements

        (when downstream
          (op/input downstream joined timestamp)))))

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
    {:arrangements @(:arrangements state)}))

(defn make-join-operator
  "Create a join operator.
  left-key-fn: extract join key from left values
  right-key-fn: extract join key from right values
  merge-fn: combine matching left and right values"
  [left-key-fn right-key-fn merge-fn downstream]
  (->JoinOperator
   left-key-fn
   right-key-fn
   merge-fn
   (assoc (op/create-operator-state)
          :arrangements (atom {}))
   downstream))

;; =============================================================================
;; Helper: Natural Join (for Datalog patterns)
;; =============================================================================

(defn natural-join
  "Natural join of two collections on shared variables.
  Returns collection of merged bindings."
  [coll1 coll2]
  (let [;; Convert collections to sets of binding maps
        bindings1 (set (mapcat (fn [[value count]]
                                 (repeat count value))
                               (seq coll1)))
        bindings2 (set (mapcat (fn [[value count]]
                                 (repeat count value))
                               (seq coll2)))]

    (if (empty? bindings1)
      bindings2
      (if (empty? bindings2)
        bindings1
        ;; Find common variables
        (let [vars1 (set (keys (first bindings1)))
              vars2 (set (keys (first bindings2)))
              common-vars (set/intersection vars1 vars2)]

          (if (empty? common-vars)
            ;; Cross product
            (set (for [b1 bindings1
                       b2 bindings2]
                   (merge b1 b2)))
            ;; Join on common variables
            (set (for [b1 bindings1
                       b2 bindings2
                       :when (every? (fn [v] (= (get b1 v) (get b2 v))) common-vars)]
                   (merge b1 b2)))))))))
