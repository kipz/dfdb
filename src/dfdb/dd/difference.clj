(ns dfdb.dd.difference
  "Difference represents changes to a multiset - additions and retractions."
  (:require [dfdb.dd.multiset :as ms]))

(deftype Difference [additions retractions]
  ;; additions: {value -> +count}
  ;; retractions: {value -> -count}

  Object
  (toString [_this]
    (str "#<Difference +{" additions "} -{" retractions "}>")))

(defn difference
  "Create a difference from additions and retractions."
  ([]
   (Difference. {} {}))
  ([additions retractions]
   (Difference. additions retractions)))

(defn empty-difference
  "Create an empty difference (no changes)."
  []
  (Difference. {} {}))

(defn add-element
  "Add an element to the difference (mark as added)."
  [diff value multiplicity]
  (Difference.
   (update (.additions diff) value (fnil + 0) multiplicity)
   (.retractions diff)))

(defn retract-element
  "Retract an element from the difference (mark as removed)."
  [diff value multiplicity]
  (Difference.
   (.additions diff)
   (update (.retractions diff) value (fnil + 0) multiplicity)))

(defn apply-difference
  "Apply a difference to a multiset, returning new multiset."
  [ms diff]
  (let [;; Add all additions
        with-adds (reduce (fn [m [value count]]
                            (ms/add m value count))
                          ms
                          (.additions diff))
        ;; Remove all retractions
        with-retracts (reduce (fn [m [value count]]
                                (ms/remove-elem m value count))
                              with-adds
                              (.retractions diff))]
    with-retracts))

(defn compact-difference
  "Compact a difference by canceling out additions and retractions.
  If value is both added and retracted, compute net change."
  [diff]
  (let [all-values (set (concat (keys (.additions diff))
                                (keys (.retractions diff))))
        compacted (reduce (fn [acc value]
                            (let [added (get (.additions diff) value 0)
                                  retracted (get (.retractions diff) value 0)
                                  net (- added retracted)]
                              (cond
                                (pos? net) (update acc :additions assoc value net)
                                (neg? net) (update acc :retractions assoc value (- net))
                                :else acc)))
                          {:additions {} :retractions {}}
                          all-values)]
    (Difference. (:additions compacted) (:retractions compacted))))

(defn merge-differences
  "Merge two differences."
  [d1 d2]
  (compact-difference
   (Difference.
    (merge-with + (.additions d1) (.additions d2))
    (merge-with + (.retractions d1) (.retractions d2)))))

(defn to-subscription-diff
  "Convert internal Difference to subscription diff format.
  Returns {:additions #{...} :retractions #{...}}"
  [diff]
  {:additions (set (mapcat (fn [[value count]]
                             (repeat count value))
                           (.additions diff)))
   :retractions (set (mapcat (fn [[value count]]
                               (repeat count value))
                             (.retractions diff)))})

(defn from-deltas
  "Create a difference from transaction deltas.
  Deltas are maps with :operation :assert or :retract."
  [deltas extract-fn]
  (reduce (fn [diff delta]
            (let [value (extract-fn delta)
                  op (:operation delta)]
              (case op
                :assert (add-element diff value 1)
                :retract (retract-element diff value 1))))
          (empty-difference)
          deltas))
