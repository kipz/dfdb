;; Debug script to understand the multipattern join flow
(require '[clojure.pprint :refer [pprint]])

(println "=== For 4-pattern query: P1, P2, P3, P4 ===\n")
(println "Expected join structure:")
(println "  join[0]: P1 ⋈ P2")
(println "  join[1]: (P1 ⋈ P2) ⋈ P3")
(println "  join[2]: ((P1 ⋈ P2) ⋈ P3) ⋈ P4\n")

(println "Current flow for P1 (idx=0):")
(println "  1. P1 output tagged as :left → join[0]")
(println "  2. join[0] output tagged as :right → join[1]")  
(println "  3. join[1] output tagged as :right → join[2]")
(println "  ❌ WRONG! After join[0], result should be :left to join[1]\n")

(println "Current flow for P2 (idx=1):")
(println "  1. P2 output tagged as :right → join[0]")
(println "  2. join[0] output tagged as :left → join[1]")
(println "  3. join[1] output tagged as :left → join[2]")
(println "  ❌ WRONG! P2 shouldn't chain through join[1]\n")

(println "Current flow for P3 (idx=2):")
(println "  1. P3 output tagged as :right → join[1]")
(println "  2. join[1] output tagged as :left → join[2]")
(println "  ❌ Wrong tagging - should be :left to join[2]\n")

(println "CORRECT flow should be:")
(println "  P1 → join[0].left")
(println "  P2 → join[0].right")
(println "  join[0] output → join[1].left")
(println "  P3 → join[1].right")
(println "  join[1] output → join[2].left")
(println "  P4 → join[2].right")
