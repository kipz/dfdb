(ns dfdb.dd.join-incremental
  "Incremental join operator for multi-pattern queries."
  (:require [dfdb.dd.delta-simple :as delta]))

(defrecord IncrementalJoin [left-state right-state join-vars]
  ;; Maintains state for both sides of join
  ;; On input to one side, probes other side
  ;; Emits only newly joined results

  delta/DeltaOperator
  (process-delta [_this delta]
    ;; Determine which side this delta is for
    ;; For now: check source tag
    (let [source (:source delta :left)  ; Default to left
          binding (:binding delta)
          mult (:mult delta)]

      (case source
        :left
        (do
          ;; Update left state
          (swap! left-state update binding (fnil + 0) mult)

          ;; Probe right state for matches
          (let [join-key (select-keys binding join-vars)
                matching-right (filter (fn [[right-binding _]]
                                         (= join-key (select-keys right-binding join-vars)))
                                       @right-state)]

            ;; Emit joined results
            (mapcat (fn [[right-binding right-mult]]
                     ;; Emit join with combined multiplicity
                      (let [joined (merge binding right-binding)
                            combined-mult (* mult right-mult)]
                        (when (not (zero? combined-mult))
                          [(delta/make-delta joined combined-mult)])))
                    matching-right)))

        :right
        (do
          ;; Update right state
          (swap! right-state update binding (fnil + 0) mult)

          ;; Probe left state for matches
          (let [join-key (select-keys binding join-vars)
                matching-left (filter (fn [[left-binding _]]
                                        (= join-key (select-keys left-binding join-vars)))
                                      @left-state)]

            (mapcat (fn [[left-binding left-mult]]
                      (let [joined (merge left-binding binding)
                            combined-mult (* left-mult mult)]
                        (when (not (zero? combined-mult))
                          [(delta/make-delta joined combined-mult)])))
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
  (let [vars1 (filter #(and (symbol? %) (.startsWith (name %) "?")) pattern1)
        vars2 (filter #(and (symbol? %) (.startsWith (name %) "?")) pattern2)]
    (vec (filter (set vars1) vars2))))
