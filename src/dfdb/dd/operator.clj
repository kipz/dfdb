(ns dfdb.dd.operator
  "Differential dataflow operator protocol and base implementations."
  (:require [dfdb.dd.multiset :as ms]))

(defprotocol Operator
  "Differential dataflow operator protocol."
  (input [_this collection timestamp]
    "Send input collection at given timestamp to operator.")
  (step [_this]
    "Advance computation one step. Returns true if made progress.")
  (output [_this]
    "Get output collection. Returns sequence of [timestamp collection] pairs.")
  (frontier [_this]
    "Get current frontier (timestamps of completed work)."))

(defprotocol Stateful
  "Operators that maintain state."
  (state [_this]
    "Get current operator state (for debugging/inspection)."))

(defrecord OperatorState
           [inputs        ; Atom: map of timestamp -> collection
            outputs       ; Atom: map of timestamp -> collection
            frontier-ts   ; Atom: current frontier timestamp
            pending])     ; Atom: queue of work to do

(defn create-operator-state
  "Create initial operator state."
  []
  (map->OperatorState
   {:inputs (atom {})
    :outputs (atom {})
    :frontier-ts (atom nil)
    :pending (atom clojure.lang.PersistentQueue/EMPTY)}))

;; =============================================================================
;; MapOperator
;; =============================================================================

(defrecord MapOperator [f state downstream]
  Operator
  (input [_this coll timestamp]
    ;; Apply function to each element in collection
    (let [mapped (ms/multiset
                  (into {}
                        (map (fn [[value count]]
                               [(f value) count])
                             (seq coll))))]
      ;; Forward to downstream
      (when downstream
        (input downstream mapped timestamp))))

  (step [_this]
    ;; Map is stateless - work happens in input
    false)

  (output [_this]
    ;; Map forwards immediately - check downstream
    (when downstream
      (output downstream)))

  (frontier [_this]
    (when downstream
      (frontier downstream))))

(defn make-map-operator
  "Create a map operator that applies function f to each value."
  [f downstream]
  (->MapOperator f (create-operator-state) downstream))

;; =============================================================================
;; FilterOperator
;; =============================================================================

(defrecord FilterOperator [pred state downstream]
  Operator
  (input [_this coll timestamp]
    ;; Filter collection by predicate
    (let [filtered (ms/multiset
                    (into {}
                          (filter (fn [[value _count]]
                                    (pred value))
                                  (seq coll))))]
      ;; Forward filtered results
      (when downstream
        (input downstream filtered timestamp))))

  (step [_this]
    false)

  (output [_this]
    (when downstream
      (output downstream)))

  (frontier [_this]
    (when downstream
      (frontier downstream))))

(defn make-filter-operator
  "Create a filter operator."
  [pred downstream]
  (->FilterOperator pred (create-operator-state) downstream))

;; =============================================================================
;; DistinctOperator
;; =============================================================================

(defrecord DistinctOperator [state downstream]
  Operator
  (input [_this coll timestamp]
    ;; Distinct: set all counts to 1
    (let [distinct-coll (ms/multiset
                         (into {}
                               (map (fn [[value _count]]
                                      [value 1])
                                    (filter (fn [[_v count]] (pos? count))
                                            (seq coll)))))]
      (when downstream
        (input downstream distinct-coll timestamp))))

  (step [_this]
    false)

  (output [_this]
    (when downstream
      (output downstream)))

  (frontier [_this]
    (when downstream
      (frontier downstream))))

(defn make-distinct-operator
  "Create a distinct operator."
  [downstream]
  (->DistinctOperator (create-operator-state) downstream))

;; =============================================================================
;; CollectOperator (terminal - collects output)
;; =============================================================================

(defrecord CollectOperator [state]
  Operator
  (input [_this coll timestamp]
    ;; ACCUMULATE multisets (merge, handling negative counts)
    (swap! (:accumulated state)
           (fn [current]
             (ms/merge-multisets current coll)))
    (swap! (:frontier-ts state) (fn [current]
                                  (if current
                                    (max current timestamp)
                                    timestamp))))

  (step [_this]
    false)

  (output [_this]
    ;; Return accumulated results as VECTOR to preserve multiplicities
    (let [accumulated @(:accumulated state)]
      (when accumulated
        (vec (mapcat (fn [[value count]]
                       (when (pos? count)  ; Only include count > 0
                         (repeat count value)))
                     (seq accumulated))))))

  (frontier [_this]
    @(:frontier-ts state))

  Stateful
  (state [_this]
    {:accumulated @(:accumulated state)
     :frontier @(:frontier-ts state)}))

(defn make-collect-operator
  "Create a terminal collect operator that accumulates results."
  []
  (->CollectOperator
   (assoc (create-operator-state)
          :accumulated (atom (ms/empty-multiset)))))
