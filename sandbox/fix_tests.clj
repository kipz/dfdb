(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(defn add-flush-after-transact
  "Add (flush-subscriptions! db) after (transact! db ...) calls."
  [content]
  (-> content
      ;; Match transact! calls that are complete on one line and followed by newline
      (str/replace #"(?m)^(\s+)\(transact! db ([^\n]+)\)\n"
                   "$1(transact! db $2)\n$1(flush-subscriptions! db)\n")))

(defn fix-file [file-path]
  (println "Fixing" file-path)
  (let [content (slurp file-path)
        fixed (add-flush-after-transact content)]
    (spit file-path fixed)
    (println "Fixed" file-path)))

(doseq [file ["test/dfdb/usecase_subscriptions_test.clj"
              "test/dfdb/subscription_verification_test.clj"
              "test/dfdb/generative_stress_test.clj"]]
  (fix-file file))

(println "Done!")
