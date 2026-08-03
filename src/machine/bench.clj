(ns machine.bench
  "Running a measurement on the machine the probe just described.

  The other half of the same host effect. `machine.probe` reads the static
  facts; this runs the dynamic ones. Both exist because `machine` is a
  contract that may not probe and `perfgate` is an assurance layer that may
  not benchmark — somebody has to actually touch the device, and it is this
  namespace, in one place, on purpose.

  The point is not to produce a fast number. It is to close the loop:
  `layout` *predicts* a cache-line count from a descriptor, this *measures*
  wall time on the machine that descriptor describes, and `perfgate` decides
  whether the difference survives the noise. A prediction nobody checked
  against a device is the thing this whole stack was built to stop shipping.

  JVM-only, and honest about that: the JIT, the GC and a laptop's thermal
  governor are all in the measurement. Warmup runs before samples, samples are
  reported raw, and `perfgate` is what decides whether they mean anything."
  (:require [layout.core :as l]
            [machine.core :as m]))

;; ── the two layouts, as actual arrays ────────────────────────────────────
;;
;; A particle with x, y, z, mass. AoS is one array with the four values
;; adjacent per element; SoA is four arrays. Both hold identical values, and
;; both passes sum only `x` — the access pattern where the layouts diverge.

(def fields
  [{:name :x :bytes 8 :align 8}
   {:name :y :bytes 8 :align 8}
   {:name :z :bytes 8 :align 8}
   {:name :mass :bytes 8 :align 8}])

(defn make-aos ^doubles [^long n ^long width]
  (let [a (double-array (* width n))]
    (dotimes [i n] (aset a (* width i) (double i)))
    a))

(defn make-soa ^doubles [^long n]
  (let [xs (double-array n)]
    (dotimes [i n] (aset xs i (double i)))
    xs))

(defn sum-aos ^double [^doubles a ^long n ^long width]
  (loop [i 0 acc 0.0]
    (if (< i n)
      (recur (inc i) (+ acc (aget a (* width i))))
      acc)))

(defn sum-soa ^double [^doubles xs ^long n]
  (loop [i 0 acc 0.0]
    (if (< i n)
      (recur (inc i) (+ acc (aget xs i)))
      acc)))

;; ── sampling ─────────────────────────────────────────────────────────────

(defn- time-ns
  "Nanoseconds for one call of `f`, with the result consumed so the JIT cannot
  delete the loop it was asked to time."
  [f]
  (let [t0 (System/nanoTime)
        r (f)
        t1 (System/nanoTime)]
    (when (Double/isNaN r) (throw (ex-info "impossible" {:r r})))
    (- t1 t0)))

(defn samples
  "`reps` timings of `f`, after `warmup` untimed calls.

  Warmup exists because the first few passes measure the JIT rather than the
  memory system. It is not a way to make the numbers look better — the raw
  samples still go to `perfgate`, which refuses them if they are too noisy to
  mean anything."
  [f {:keys [warmup reps] :or {warmup 5 reps 12}}]
  (dotimes [_ warmup] (f))
  (vec (repeatedly reps #(time-ns f))))

;; ── the loop that closes ─────────────────────────────────────────────────

(defn fields-of
  "`width` doubles per element, only the first of which the pass reads."
  [width]
  (mapv (fn [i] {:name (keyword (str "f" i)) :bytes 8 :align 8}) (range width)))

(defn predicted
  "What `layout` says the two layouts cost on this machine, before running.

  Note what the descriptor changes: on a 128-byte line an 8-byte field gives
  16 elements per line, so a 32-byte AoS element wastes three quarters of
  every line. On the 64-byte line of `machine.core/portable-64` the same
  computation gives the same ratio, but every absolute line count halves —
  which is why a prediction has to name the machine it was made on."
  [machine n width]
  (let [fs (fields-of width)
        access {:access/fields #{:f0} :access/stride 1}
        aos (l/cost machine (l/aos machine fs) n access)
        soa (l/cost machine (l/soa machine fs) n access)]
    {:aos aos
     :soa soa
     :line-bytes (m/line-bytes machine)
     :predicted-ratio (double (/ (:cost/lines aos) (:cost/lines soa)))}))

(defn run
  "Measure both layouts on `machine` and return raw samples plus the prediction.

  `n` defaults to two million elements: 64 MiB as an array of structs, well
  past this machine's 12 MiB L2, so the pass is memory-bound and the layout is
  what is actually being measured. A working set that fits in cache measures
  the loop overhead instead, and reports a ratio near 1.0 that means nothing."
  ([machine] (run machine {}))
  ([machine {:keys [n width] :or {n 2000000 width 4} :as opts}]
   (let [aos (make-aos n width)
         soa (make-soa n)]
     {:n n
      :width width
      :machine-id (:machine/id machine)
      :prediction (predicted machine n width)
      :aos-samples (samples #(sum-aos aos n width) opts)
      :soa-samples (samples #(sum-soa soa n) opts)})))
