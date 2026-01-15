#!/usr/bin/env clojure
(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(defn add-sleep-after-transact
  "Add Thread/sleep after transact! calls in a test file."
  [file-path]
  (let [content (slurp file-path)
        lines (str/split-lines content)
        result (atom [])
        inside-transact? (atom false)]

    (doseq [[idx line] (map-indexed vector lines)]
      ;; Add current line
      (swap! result conj line)

      ;; Check if this line contains (transact! db
      (when (and (re-find #"\(transact!" line)
                 (not (re-find #"Thread/sleep" (get lines (inc idx) ""))))
        ;; Check if the transact! is complete on this line or needs to look ahead
        (let [paren-count (- (count (re-seq #"\(" line))
                             (count (re-seq #"\)" line)))]
          (if (<= paren-count 0)
            ;; Complete on this line, add sleep immediately
            (swap! result conj "      (Thread/sleep 100)")
            ;; Multi-line transact!, mark we're inside one
            (reset! inside-transact? true))))

      ;; If we were inside a multi-line transact! and this closes it
      (when @inside-transact?
        (let [paren-count (- (count (re-seq #"\(" line))
                             (count (re-seq #"\)" line)))]
          (when (< paren-count 0)  ; Closing paren
            (reset! inside-transact? false)
            (swap! result conj "      (Thread/sleep 100)")))))

    (str/join "\n" @result)))

(let [test-file (first *command-line-args*)]
  (when test-file
    (let [updated (add-sleep-after-transact test-file)]
      (spit test-file updated)
      (println "Updated" test-file))))
