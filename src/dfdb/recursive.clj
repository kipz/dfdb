(ns dfdb.recursive
  "Recursive query support for transitive closure."
  (:require [dfdb.index :as index]
            [clojure.set :as set]))

(set! *warn-on-reflection* true)

(defn recursive-attribute?
  "Check if attribute is recursive (ends with +)."
  [attr]
  (and (keyword? attr)
       (.endsWith (name attr) "+")))

(defn base-attribute
  "Get base attribute from recursive attribute.
  :user/reports-to+ -> :user/reports-to"
  [attr]
  (keyword (namespace attr)
           (subs (name attr) 0 (dec (count (name attr))))))

(defn transitive-closure
  "Compute transitive closure for recursive attribute.
  Starting from initial-bindings, follow attribute relationships.
  Returns set of all reachable bindings."
  [db initial-bindings recursive-attr entity-var value-var _as-of-map max-depth]
  (let [base-attr (base-attribute recursive-attr)
        storage (:storage db)]
    (loop [frontier initial-bindings
           seen #{}
           depth 0]
      (if (or (empty? frontier)
              (and max-depth (>= depth max-depth)))
        seen
        (let [;; For each binding in frontier, find next hops
              ;;  For first iteration: follow from entity-var
              ;;  For subsequent: follow from value-var (the reached node)
              next-hops (set
                         (for [binding frontier
                               :let [;; On first iteration, follow from entity-var
                                     ;;  On subsequent, follow from value-var
                                     current-entity (if (zero? depth)
                                                      (get binding entity-var entity-var)  ; entity-var might be constant
                                                      (get binding value-var))]
                               :when current-entity
                               ;; Scan for [current-entity base-attr ?next]
                               :let [start-key (if (string? current-entity)
                                                 [:eavt current-entity base-attr]
                                                 [:eavt current-entity base-attr])
                                     end-key (if (string? current-entity)
                                               [:eavt (str current-entity "\uFFFF") base-attr]
                                               [:eavt (inc current-entity) base-attr])
                                     datoms (index/scan-eavt storage start-key end-key)]
                               [_k datom] datoms
                               :when (and (= (:a datom) base-attr)
                                          (= :assert (:op datom)))]
                           (assoc binding value-var (:v datom))))
              new-frontier (set/difference next-hops seen)]
          (recur new-frontier
                 (set/union seen next-hops)
                 (inc depth)))))))

(defn inverse-transitive-closure
  "Compute inverse transitive closure - find all entities that reach target.
  For [?source :attr+ target], finds all ?source that have path to target."
  [db target-bindings recursive-attr source-var target-var _as-of-map max-depth]
  (let [base-attr (base-attribute recursive-attr)
        storage (:storage db)
        target-val (get (first target-bindings) target-var)]
    (loop [frontier #{target-val}  ; Start from target, work backwards
           seen #{}
           depth 0]
      (if (or (empty? frontier)
              (and max-depth (>= depth max-depth)))
        ;; Convert seen entities to bindings
        (set (for [entity seen]
               (assoc (first target-bindings) source-var entity)))
        (let [;; Find all entities that point TO current frontier
              prev-hops (set
                         (for [target-entity frontier
                               ;; Scan VAET index for [target-entity base-attr ?source]
                               :let [start-key [:vaet target-entity base-attr]
                                     end-key [:vaet target-entity (index/successor-value base-attr)]
                                     datoms (index/scan-vaet storage start-key end-key)]
                               [_k datom] datoms
                               :when (and (= (:a datom) base-attr)
                                          (= (:v datom) target-entity)  ; Points to target
                                          (= :assert (:op datom)))]
                           (:e datom)))  ; The source entity
              new-frontier (set/difference prev-hops seen)]
          (recur new-frontier
                 (set/union seen prev-hops)
                 (inc depth)))))))

(defn match-recursive-pattern
  "Match recursive pattern like [?report :user/reports-to+ ?ceo] or [1 :node/next+ ?node].
  Computes transitive closure starting from bound variables or constants."
  [db pattern bindings as-of-map]
  (let [[e a v & rest] pattern
        modifiers (apply hash-map rest)
        max-depth (:max-depth modifiers)

        e-bound? (contains? bindings e)
        v-bound? (contains? bindings v)
        e-is-var? (and (symbol? e) (.startsWith ^String (name e) "?"))
        v-is-var? (and (symbol? v) (.startsWith ^String (name v) "?"))

        ;; Determine starting point
        e-constant? (and (not e-is-var?) (not e-bound?))
        v-constant? (and (not v-is-var?) (not v-bound?))]

    (cond
      ;; Starting from value (bound or constant)
      (or v-bound? v-constant?)
      (let [initial-bindings (if v-bound? #{bindings} #{(assoc bindings v v)})]
        (inverse-transitive-closure db initial-bindings a e v as-of-map max-depth))

      ;; Starting from entity (bound or constant)
      (or e-bound? e-constant?)
      (let [initial-bindings (if e-bound? #{bindings} #{(assoc bindings e e)})]
        (transitive-closure db initial-bindings a e v as-of-map max-depth))

      :else
      (throw (ex-info "Recursive pattern requires at least one bound variable or constant"
                      {:pattern pattern})))))
