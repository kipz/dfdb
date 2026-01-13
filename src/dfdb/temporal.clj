(ns dfdb.temporal
  "Temporal query support for multi-dimensional time."
  (:require [dfdb.dimensions :as dims]))

(defn parse-as-of
  "Parse as-of clause into dimension filters.
  {:time/system #inst '...' :time/valid #inst '...'} -> filters by both"
  [db as-of-map]
  (when as-of-map
    ;; Validate all dimensions exist
    (doseq [[dim-name _] as-of-map]
      (dims/validate-dimension-exists db dim-name))
    as-of-map))

(defn datom-matches-temporal-filter?
  "Check if datom matches temporal filter.
  PERMISSIVE SEMANTICS: Only filter by dimensions present in datom.
  For strict filtering, use :at/ modifier on patterns."
  [datom as-of-map]
  (if (empty? as-of-map)
    true  ; No temporal filter
    (every? (fn [[dim-name dim-value]]
              (if-let [datom-dim-value (get datom dim-name)]
                ;; Datom has this dimension - check if value <= filter
                (<= (.getTime datom-dim-value) (.getTime dim-value))
                ;; Datom doesn't have this dimension - ALLOW IT (permissive)
                true))
            as-of-map)))

(defn filter-datoms-temporal
  "Filter datoms by temporal as-of constraints.
  Only returns datoms that match ALL specified time dimensions."
  [datoms as-of-map]
  (if (or (nil? as-of-map) (empty? as-of-map))
    datoms
    (filter (fn [[_k datom]]
              (datom-matches-temporal-filter? datom as-of-map))
            datoms)))

(defn parse-temporal-pattern-modifier
  "Parse :at/<dimension> modifier from pattern.
  Pattern: [?e :attr ?v :at/shipped ?time]
  Returns: {:bind-var ?time :dimension :time/shipped}"
  [modifiers]
  (when-let [at-clause (some (fn [[k v]]
                               (when (and (keyword? k)
                                          (= "at" (namespace k)))
                                 [k v]))
                             modifiers)]
    (let [[at-key bind-var] at-clause
          dim-name (keyword "time" (name at-key))]
      {:bind-var bind-var
       :dimension dim-name})))

(defn bind-temporal-value
  "Bind temporal dimension value to variable if pattern has :at/dimension.
  Returns nil if dimension required but not present (for filtering)."
  [bindings datom temporal-spec]
  (if temporal-spec
    (if-let [dim-value (get datom (:dimension temporal-spec))]
      (assoc bindings (:bind-var temporal-spec) dim-value)
      nil)  ; Dimension required but not present - filter out
    bindings))
