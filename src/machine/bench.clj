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

;; ── bandwidth saturation ─────────────────────────────────────────────────
;;
;; The single-threaded run said 2.02x against a predicted 16x. The obvious
;; hypothesis for the gap is that one core cannot ask for enough bandwidth to
;; make bandwidth the bottleneck: the prefetcher hides the latency, and a
;; machine with hundreds of GB/s to give has plenty left over. If that is
;; right, adding cores should push the measured ratio toward the byte ratio,
;; because every added core competes for the same memory system.
;;
;; If it is wrong — if the ratio stays flat as cores are added — then the gap
;; is somewhere else and the hypothesis has to be thrown away rather than
;; explained around.

(defn sum-aos-range ^double [^doubles a ^long from ^long to ^long width]
  (loop [i from acc 0.0]
    (if (< i to)
      (recur (inc i) (+ acc (aget a (* width i))))
      acc)))

(defn sum-soa-range ^double [^doubles xs ^long from ^long to]
  (loop [i from acc 0.0]
    (if (< i to)
      (recur (inc i) (+ acc (aget xs i)))
      acc)))

(defn- parallel
  "Split `n` into `threads` contiguous slices and sum them concurrently.

  Contiguous rather than interleaved on purpose: interleaving would put
  several threads on the same cache lines and measure false sharing instead
  of bandwidth."
  ^double [threads ^long n slice-fn]
  (let [chunk (quot n threads)
        fs (mapv (fn [t]
                   (let [from (* t chunk)
                         to (if (= t (dec threads)) n (* (inc t) chunk))]
                     (future (slice-fn from to))))
                 (range threads))]
    (reduce + 0.0 (map deref fs))))

(defn run-scaling
  "Measure both layouts at each thread count in `thread-counts`.

  Returns one entry per thread count, each with raw samples for both arms, so
  every point can be handed to `perfgate` separately. A scaling curve whose
  points were never individually gated is a shape, not a result."
  [machine {:keys [n width thread-counts] :or {n 2000000 width 16 thread-counts [1 2 4 8]}
            :as opts}]
  (let [aos (make-aos n width)
        soa (make-soa n)]
    {:n n
     :width width
     :machine-id (:machine/id machine)
     :prediction (predicted machine n width)
     :points (mapv (fn [t]
                     {:threads t
                      :aos-samples (samples #(parallel t n (fn [from to]
                                                             (sum-aos-range aos from to width)))
                                            opts)
                      :soa-samples (samples #(parallel t n (fn [from to]
                                                             (sum-soa-range soa from to)))
                                            opts)})
                   thread-counts)}))

;; ── blocked matrix multiply ──────────────────────────────────────────────
;;
;; `traversal/tile-plan` picks a tile from a cache capacity. Whether that tile
;; is the fastest one is a different question from whether it fits, and the
;; roofline lesson says to check before believing: a model can be arithmetically
;; right about bytes and still be answering a question the machine does not ask.
;;
;; Loop order is fixed to blocked `ikj` — the order `traversal/loop-order`
;; derives — so that what varies across the sweep is the tile and nothing else.

(defn matmul-tiled
  "C += A*B for n x n row-major f64, blocked at `tile`. `tile` >= n is unblocked."
  ;; No primitive hints on n/tile: Clojure caps primitive-taking fns at four
  ;; args, and the arrays have to stay hinted or every aget boxes.
  [^doubles a ^doubles b ^doubles c n tile]
  (let [n (long n) tile (long tile)]
   (loop [ii 0]
    (when (< ii n)
      (loop [kk 0]
        (when (< kk n)
          (loop [jj 0]
            (when (< jj n)
              (let [imax (min n (+ ii tile))
                    kmax (min n (+ kk tile))
                    jmax (min n (+ jj tile))]
                (loop [i ii]
                  (when (< i imax)
                    (let [irow (* i n)]
                      (loop [k kk]
                        (when (< k kmax)
                          (let [aik (aget a (+ irow k))
                                krow (* k n)]
                            (loop [j jj]
                              (when (< j jmax)
                                (aset c (+ irow j)
                                      (+ (aget c (+ irow j)) (* aik (aget b (+ krow j)))))
                                (recur (inc j)))))
                          (recur (inc k)))))
                    (recur (inc i)))))
              (recur (+ jj tile))))
          (recur (+ kk tile))))
      (recur (+ ii tile))))))

(defn- filled ^doubles [^long n]
  (let [a (double-array (* n n))]
    (dotimes [i (* n n)] (aset a i (double (mod i 97))))
    a))

(defn run-tiling
  "Time `matmul-tiled` across `tiles`, everything else held fixed.

  C is zeroed inside the timed region for every arm, so the memset is a
  constant that cannot favour one tile over another."
  [{:keys [n tiles] :or {n 768} :as opts}]
  (let [a (filled n) b (filled n) c (double-array (* n n))
        tiles (or tiles [8 16 32 48 64 128 256 384 n])]
    {:n n
     :flops (* 2.0 n n n)
     :points (mapv (fn [t]
                     {:tile t
                      :samples (samples (fn []
                                          (java.util.Arrays/fill c 0.0)
                                          (matmul-tiled a b c n t)
                                          (aget c 0))
                                        opts)})
                   tiles)}))

;; ── breaking the dependency chain ────────────────────────────────────────
;;
;; 3.03 ns per element for a sequential f64 sum is about ten cycles on this
;; machine, which is far too many for one load and one add. The memory system
;; is not the reason: 16 MiB in 6 ms is 2.7 GB/s, a small fraction of what the
;; part delivers. The reason is the loop-carried dependency on the accumulator
;; — every add waits for the previous add's result, so the loop runs at
;; floating-point add latency rather than throughput.
;;
;; The fix is the classic one and it is not a micro-optimization: four
;; independent accumulators let four adds be in flight at once. It matters
;; here beyond speed, because a loop this slow makes every memory experiment
;; in this repo unable to see the memory system at all.

(defn sum-soa-unrolled
  "Same sum, four independent accumulators."
  ^double [^doubles xs n]
  (let [n (long n)
        limit (- n 3)]
    (loop [i 0 a0 0.0 a1 0.0 a2 0.0 a3 0.0]
      (if (< i limit)
        (recur (+ i 4)
               (+ a0 (aget xs i))
               (+ a1 (aget xs (+ i 1)))
               (+ a2 (aget xs (+ i 2)))
               (+ a3 (aget xs (+ i 3))))
        (loop [i i acc (+ (+ a0 a1) (+ a2 a3))]
          (if (< i n) (recur (inc i) (+ acc (aget xs i))) acc))))))

(defn sum-aos-unrolled
  "Same, striding by `width` elements per accumulator."
  ^double [^doubles a n width]
  (let [n (long n) width (long width)
        limit (- n 3)
        w2 (* 2 width) w3 (* 3 width) w4 (* 4 width)]
    (loop [i 0 o 0 a0 0.0 a1 0.0 a2 0.0 a3 0.0]
      (if (< i limit)
        (recur (+ i 4) (+ o w4)
               (+ a0 (aget a o))
               (+ a1 (aget a (+ o width)))
               (+ a2 (aget a (+ o w2)))
               (+ a3 (aget a (+ o w3))))
        (loop [i i o o acc (+ (+ a0 a1) (+ a2 a3))]
          (if (< i n) (recur (inc i) (+ o width) (+ acc (aget a o))) acc))))))

(defn run-unrolled
  "Both layouts with the dependency chain broken, alongside the original."
  [machine {:keys [n width] :or {n 2000000 width 16} :as opts}]
  (let [aos (make-aos n width)
        soa (make-soa n)]
    {:n n :width width
     :machine-id (:machine/id machine)
     :prediction (predicted machine n width)
     :serial {:aos (samples #(sum-aos aos n width) opts)
              :soa (samples #(sum-soa soa n) opts)}
     :unrolled {:aos (samples #(sum-aos-unrolled aos n width) opts)
                :soa (samples #(sum-soa-unrolled soa n) opts)}}))

;; ── independent calibration ──────────────────────────────────────────────
;;
;; `layout/achievable-ratio` needs a loop floor and a bandwidth. Taking the
;; first from the candidate arm and the second from the baseline arm makes the
;; formula reproduce those two timings by construction — an identity dressed
;; as a prediction, which is what happened here on 2026-08-03 before anyone
;; noticed. These two measurements exist so the constants come from somewhere
;; else entirely, and the model can then be asked about a configuration nobody
;; has run.

(defn loop-floor-ns
  "Nanoseconds per element with memory effectively free.

  A working set small enough to sit in L1 makes the memory term vanish, so
  what is left is the loop: index arithmetic, bounds check, the add. This is
  the term `achievable-ratio` calls `loop-ns-per-element`, and measuring it
  here rather than reading it off the arm being explained is the whole point."
  [{:keys [elements] :or {elements 2048} :as opts}]
  (let [xs (make-soa elements)
        ;; Many passes over the same small array: the loop cost dominates and
        ;; the timer's own resolution does not.
        passes 2000
        s (samples (fn [] (loop [p 0 acc 0.0]
                            (if (< p passes)
                              (recur (inc p) (+ acc (sum-soa-unrolled xs elements)))
                              acc)))
                   opts)
        mean (/ (reduce + 0.0 s) (count s))]
    {:elements elements
     :passes passes
     :samples s
     :ns-per-element (/ mean (* (double elements) passes))
     :working-set-bytes (* 8 elements)}))

(defn bandwidth-bytes-per-ns
  "Bytes per nanosecond a single thread observes on a streaming read.

  **A contiguous f64 sum cannot measure this, and the first version of this
  function tried to.** Each iteration of that loop touches 8 bytes and costs
  0.766 ns, so it caps at 10.4 GB/s no matter how fast the memory is — what
  comes back is the loop floor wearing different units, and feeding it to
  `achievable-ratio` produced a prediction 65% wrong (it over-predicted the
  AoS arm by 2.9x, because that arm really did reach 31.5 GB/s).

  So the scan is strided by a full cache line: every touched element pulls
  `line-bytes` and the loop cost is amortised over all of them, which puts the
  memory system back in charge. Stride and size are both different from
  anything `achievable-ratio` is asked to predict."
  [machine {:keys [elements] :or {elements 6000000} :as opts}]
  (let [line (or (m/line-bytes machine) 64)
        stride (quot line 8)
        xs (make-soa elements)
        touched (quot elements stride)
        s (samples #(sum-aos-unrolled xs touched stride) opts)
        mean (/ (reduce + 0.0 s) (count s))]
    {:elements elements
     :stride stride
     :touched touched
     :samples s
     ;; Bytes MOVED, not bytes summed: a strided touch pulls a whole line.
     :bytes-per-ns (/ (* (double line) touched) mean)
     :working-set-bytes (* 8 elements)}))
