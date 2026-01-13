(ns dfdb.dd.delta-simple
  "Simplified delta model based on xtflow.
  Each delta is {:binding {...} :mult +1/-1}"
  (:require [dfdb.dd.multiset :as ms]))

(defprotocol DeltaOperator
  "Operator that processes deltas incrementally (xtflow-style)."
  (process-delta [this delta]
    "Process a delta and return output deltas."))

(defn make-delta
  "Create a delta for a binding with multiplicity."
  [binding mult]
  {:binding binding :mult mult})

(defn add-delta
  "Create addition delta (+1)"
  [binding]
  (make-delta binding 1))

(defn remove-delta
  "Create removal delta (-1)"
  [binding]
  (make-delta binding -1))

(defn deltas-to-multiset
  "Convert sequence of deltas to multiset."
  [deltas]
  (ms/multiset
   (reduce (fn [acc {:keys [binding mult]}]
             (update acc binding (fnil + 0) mult))
           {}
           deltas)))

(defn multiset-to-deltas
  "Convert multiset to sequence of deltas."
  [multiset]
  (mapcat (fn [[binding count]]
            (cond
              (pos? count) (repeat count (add-delta binding))
              (neg? count) (repeat (- count) (remove-delta binding))
              :else []))
          (seq multiset)))

(defn transaction-deltas-to-binding-deltas
  "Convert transaction deltas to binding deltas for a pattern.
  Returns sequence of {:binding {...} :mult +1/-1}"
  [tx-deltas pattern]
  (let [[e-sym a-sym v-sym] pattern
        binding-deltas (atom [])]

    (doseq [tx-delta tx-deltas]
      (let [{:keys [entity attribute new-value old-value operation]} tx-delta]

        ;; If delta matches pattern's attribute
        (when (= attribute a-sym)

          ;; Retract old binding
          (when old-value
            (swap! binding-deltas conj
                   (remove-delta {e-sym entity, v-sym old-value})))

          ;; Add new binding
          (when (and new-value (= operation :assert))
            (swap! binding-deltas conj
                   (add-delta {e-sym entity, v-sym new-value}))))))

    @binding-deltas))
