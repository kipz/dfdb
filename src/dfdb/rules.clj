(ns dfdb.rules
  "Datalog rules support - named, reusable query fragments."
  (:require [clojure.walk :as walk]))

(set! *warn-on-reflection* true)

(defrecord Rule [name params body])

(defn parse-rules
  "Parse rule definitions into Rule records.

  Rules format:
  [[(rule-name ?param1 ?param2 ...)
    clause1
    clause2
    ...]
   [(rule-name ?param1 ?param2 ...)  ; Multiple definitions = OR
    other-clause1
    ...]]"
  [rules-vec]
  (when (seq rules-vec)
    (group-by :name
              (for [rule-def rules-vec
                    :let [[head & body] rule-def
                          [rule-name & params] head]]
                (->Rule rule-name params body)))))

(defn expand-rule-invocation
  "Expand single rule invocation by substituting arguments for parameters.

  rule: Rule record
  args: arguments from rule invocation
  Returns: expanded body clauses with arguments substituted"
  [rule args]
  (let [param-map (zipmap (:params rule) args)]
    (walk/postwalk-replace param-map (:body rule))))

(defn rule-invocation?
  "Check if clause is a rule invocation (list starting with symbol, not special form)."
  [clause rules-map]
  (and (list? clause)
       (symbol? (first clause))
       (not (contains? #{'not 'or 'or-join 'not-join} (first clause)))
       (contains? rules-map (first clause))))

(defn expand-rules-in-where
  "Recursively expand all rule invocations in WHERE clause.

  Returns expanded WHERE clause with rules inlined.
  Handles recursive rules by expanding up to max-depth."
  ([where-clauses rules-map]
   (expand-rules-in-where where-clauses rules-map 50))
  ([where-clauses rules-map max-depth]
   (if (or (empty? where-clauses) (zero? max-depth))
     where-clauses
     (let [expanded (mapcat (fn [clause]
                              (cond
                                ;; Rule invocation
                                (rule-invocation? clause rules-map)
                                (let [rule-name (first clause)
                                      args (rest clause)
                                      rule-defs (get rules-map rule-name)]
                                  (if (= 1 (count rule-defs))
                                   ;; Single definition - inline directly
                                    (expand-rule-invocation (first rule-defs) args)
                                   ;; Multiple definitions - wrap in OR clause
                                    ;; Each definition becomes a branch of the OR
                                    (list (cons 'or
                                                (map (fn [rule-def]
                                                       (vec (expand-rule-invocation rule-def args)))
                                                     rule-defs)))))

                                ;; OR clause - recursively expand inside branches
                                (and (seq? clause) (= 'or (first clause)))
                                (let [or-branches (rest clause)
                                      expanded-branches (map (fn [branch]
                                                              ;; Branch is a vector of clauses
                                                               (vec (expand-rules-in-where branch rules-map (dec max-depth))))
                                                             or-branches)]
                                  (list (cons 'or expanded-branches)))

                                ;; NOT clause - recursively expand inside
                                (and (seq? clause) (= 'not (first clause)))
                                (let [not-clauses (rest clause)
                                      ;; NOT body is a sequence of clauses (usually just one pattern/rule)
                                      expanded-not (expand-rules-in-where not-clauses rules-map (dec max-depth))]
                                  (list (cons 'not expanded-not)))

                                ;; Not a rule - keep as is
                                :else
                                [clause]))
                            where-clauses)]
       ;; Recursively expand any rule invocations in the expanded clauses
       (if (= expanded where-clauses)
         expanded  ; No changes, stop recursion
         (expand-rules-in-where expanded rules-map (dec max-depth)))))))

(defn compile-with-rules
  "Compile query with rule expansion.

  Takes query form with :in $ % and rules vector, returns expanded query."
  [query-form rules]
  (if (empty? rules)
    ;; No rules - return query as-is (strip :in clause if present)
    (let [query-vec (if (map? query-form) (:query query-form) query-form)
          has-in? (some #{:in} query-vec)]
      (if has-in?
        (let [find-idx (.indexOf ^java.util.List query-vec :find)
              in-idx (.indexOf ^java.util.List query-vec :in)
              where-idx (.indexOf ^java.util.List query-vec :where)
              find-exprs (subvec query-vec (inc find-idx) in-idx)
              where-exprs (when (>= where-idx 0) (subvec query-vec (inc where-idx)))]
          (vec (concat [:find] find-exprs [:where] where-exprs)))
        query-vec))
    ;; Has rules - expand them
    (let [query-vec (if (map? query-form) (:query query-form) query-form)
          find-idx (.indexOf ^java.util.List query-vec :find)
          in-idx (.indexOf ^java.util.List query-vec :in)
          where-idx (.indexOf ^java.util.List query-vec :where)

          find-exprs (subvec query-vec (inc find-idx) (if (>= in-idx 0) in-idx where-idx))
          where-exprs (subvec query-vec (inc where-idx))

          rules-map (parse-rules rules)
          expanded-where (expand-rules-in-where where-exprs rules-map)]

      ;; Reconstruct query without :in clause
      (vec (concat [:find] find-exprs [:where] expanded-where)))))
