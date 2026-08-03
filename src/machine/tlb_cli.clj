(ns machine.tlb-cli
  "`clojure -M:tlb` — measure this machine's address-translation penalty.

  `machine` could validate a `:tlb` fact and `traversal` could consume one,
  but nothing could produce one, so the numbers in both were hand-copied from
  a session transcript. That is the same 'declared but unfillable' state the
  fact itself was added to fix. This closes it: the output is a `:tlb` map
  that goes straight into a descriptor, and the last thing this prints is
  whether `machine/valid?` accepts the descriptor it just built."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [machine.bench :as b]
            [machine.core :as m]
            [machine.probe :as p]))

(defn- platform []
  (let [os (str/lower-case (System/getProperty "os.name" ""))]
    (cond (str/includes? os "mac") :darwin
          (str/includes? os "linux") :linux
          :else :unknown)))

(defn -main [& args]
  (let [reps (or (some-> (first args) parse-long) 7)
        probed (p/probe {:platform (platform)
                         :run (fn [c a] (:out (apply shell/sh c a)))})
        base (if (m/heterogeneous? probed) (m/for-cluster probed :performance) probed)
        page (get-in base [:page :base-bytes])
        result (try {:tlb (b/translation-curve base {:reps reps})}
                    (catch clojure.lang.ExceptionInfo e {:refused (ex-data e)}))
        tlb (:tlb result)
        with (when tlb (assoc base :tlb tlb))]
    (println (:machine/id base) "·" page "-byte pages · reps" reps)
    (println)
    (when-let [r (:refused result)]
      ;; The useful outcome. An earlier version of this probe emitted a curve
      ;; whatever the noise, and two runs produced 2.74x and 1.31x for the
      ;; same arm -- with the summary line flipping its verdict between them.
      (println "  REFUSED — this machine is too noisy right now to state a fact.")
      (println)
      (doseq [{:keys [regime pages relative-stdev allowed]} (:refusals r)]
        (println (format "    %-10s %6d pages  rel-stdev %.3f  (allowed %.2f)"
                         (name regime) pages relative-stdev allowed)))
      (println)
      (println "  " (:note r))
      (flush)
      (System/exit 1))
    (doseq [regime [:dependent :streaming]]
      (println (format "  %s" (name regime)))
      (doseq [[pages penalty] (get-in tlb [:penalty-by-pages regime])]
        (println (format "    %6d pages  %6.2fx" pages penalty)))
      (println))
    (let [d (m/translation-penalty with 512 :dependent)
          s (m/translation-penalty with 512 :streaming)]
      (println (format "  at 512 pages: dependent %.2fx · streaming %.2fx · ratio %.2fx"
                       d s (/ d s)))
      (println (str "  => " (if (< 1.3 (/ d s))
                              "the regimes disagree enough that one number would be a lie"
                              "the regimes agree here; on this machine one number would do"))))
    (println)
    ;; The point of the whole exercise: a fact something can actually use.
    (println "  machine/valid? on the descriptor carrying it:" (m/valid? with))
    (when-not (m/valid? with)
      (println "  errors:" (pr-str (m/validation-errors with))))
    (flush)))
