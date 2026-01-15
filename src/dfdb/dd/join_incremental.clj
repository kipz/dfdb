(ns dfdb.dd.join-incremental
  "Incremental join operator for multi-pattern queries.

  PERFORMANCE: Uses indexed state {join-key -> {binding -> mult}} for O(1) lookup
  instead of O(n) filtering through entire state."
  (:require [dfdb.dd.delta-core :as delta]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Indexed State Management (O(1) lookup optimization)
;; =============================================================================

(defn- extract-join-key
  "Extract join key from binding as vector of values for join variables.
  Optimized: uses reduce instead of select-keys for fewer allocations."
  [binding join-vars]
  (reduce (fn [acc var]
            (conj acc (get binding var)))
          []
          join-vars))

(defn- update-indexed-state!
  "Update indexed state with binding and multiplicity.
  State structure: {join-key -> {binding -> mult}}

  Cleans up zero-multiplicity entries to prevent memory leaks."
  [state-atom binding mult join-vars]
  (let [join-key (extract-join-key binding join-vars)]
    (swap! state-atom
           (fn [state]
             (let [new-mult (+ (get-in state [join-key binding] 0) mult)]
               (if (zero? new-mult)
                 ;; Remove binding if multiplicity is zero
                 (let [updated (update state join-key dissoc binding)]
                   ;; Remove join-key if no bindings left
                   (if (empty? (get updated join-key))
                     (dissoc updated join-key)
                     updated))
                 ;; Update with new multiplicity
                 (assoc-in state [join-key binding] new-mult)))))))

(defrecord IncrementalJoin [left-state right-state join-vars]
  ;; Indexed state structure: {join-key -> {binding -> mult}}
  ;; Enables O(1) lookup instead of O(n) filtering
  ;;
  ;; Performance: For 10k entries with 100 join keys:
  ;;   Old: 10k comparisons per delta
  ;;   New: ~100 operations per delta (100x improvement)

  delta/DeltaOperator
  (process-delta [_this delta]
    (let [source (:source delta :left)
          binding (:binding delta)
          mult ^long (:mult delta)
          join-key (extract-join-key binding join-vars)]

      (case source
        :left
        (do
          ;; Update left state with indexed structure
          (update-indexed-state! left-state binding mult join-vars)

          ;; O(1) lookup by join-key in right state
          (let [matching-right (get @right-state join-key {})]
            ;; Emit joined results - only iterate matching bindings
            (keep (fn [[right-binding right-mult]]
                    (let [joined (merge binding right-binding)
                          combined-mult (* mult ^long right-mult)]
                      (when-not (zero? combined-mult)
                        (delta/make-delta joined combined-mult))))
                  matching-right)))

        :right
        (do
          ;; Update right state with indexed structure
          (update-indexed-state! right-state binding mult join-vars)

          ;; O(1) lookup by join-key in left state
          (let [matching-left (get @left-state join-key {})]
            (keep (fn [[left-binding left-mult]]
                    (let [joined (merge left-binding binding)
                          combined-mult (* ^long left-mult mult)]
                      (when-not (zero? combined-mult)
                        (delta/make-delta joined combined-mult))))
                  matching-left)))))))

(defn make-incremental-join
  "Create incremental join operator.
  join-vars: variables to join on (e.g., [?e])"
  [join-vars]
  (->IncrementalJoin
   (atom {})  ; left-state
   (atom {})  ; right-state
   join-vars))

(defn natural-join-variables
  "Find common variables between two patterns for natural join."
  [pattern1 pattern2]
  (let [vars1 (filter #(and (symbol? %) (.startsWith ^String (name %) "?")) pattern1)
        vars2 (filter #(and (symbol? %) (.startsWith ^String (name %) "?")) pattern2)]
    (vec (filter (set vars1) vars2))))
