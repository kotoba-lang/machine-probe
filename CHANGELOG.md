# Changelog

## 0.4.0 — 2026-08-03

`machine.bench/sum-*-unrolled` and `clojure -M:unrolled` — get the loop out of
the memory system's way.

Three experiments had failed because the per-element loop cost dwarfed the
per-element memory cost. The cause was not the machine: a serial summation
loop runs at floating-point add *latency*, because every add waits on the
previous one. Four independent accumulators break the chain.

| | ns/element | AoS/SoA ratio |
|---|---|---|
| serial | 2.87 | 2.12x |
| unrolled x4 | 0.87 | **6.63x** |

At n=8e6 the unrolled ratio reaches 20.2x, above the byte model's 16x, because
a 1 GiB array exhausts TLB reach.

**Still not enough.** SoA remains loop-bound at 0.87 ns/element against a
0.36 ns memory cost, so `both-memory-bound?` is false at the sizes where the
measurement is stable. Reaching it needs roughly one cycle per element, which
means SIMD the JVM will not reliably emit — the next lever is a cheaper
runtime, not a cheaper loop.

Every gate here still refuses on `:too-noisy`: these arrays are 256 MiB to
1 GiB and page-fault variance dominates. The loop speedup is reproducible
across runs; the sealed claim is not yet.


## 0.3.0 — 2026-08-03

`machine.bench/run-tiling` and `clojure -M:tiling` — is `traversal/tile-plan`'s
tile the fast one?

Unanswerable here, and the CLI now says so before spending twenty minutes
finding out. A pilot goes through `perfgate/detectable?` first: on this machine
it reports a **133% minimum detectable improvement**, so a 10% tiling effect
cannot pass whatever the sweep does. The sweep then confirms it — every tile
from 8 to 768 lands within 1.2x of every other, and two runs of the same sweep
named different winners (384, then 48).

The cause is the loop-bound harness again. `traversal` records the finding as
`:model/validation {:status :unvalidated}` — not `:wrong`, because nothing here
shows the capacity rule is wrong; it shows this machine plus this harness
cannot tell.


## 0.2.0 — 2026-08-03

`machine.bench/run-scaling` and `clojure -M:scaling` — does contention widen
the layout gap?

The answer is that this harness cannot say. Three consecutive runs put the
1-thread to 8-thread change at +3%, +31% and -22%, and every point was refused
by `perfgate` as `:too-noisy`. On a heterogeneous CPU the scheduler moves
threads between performance and efficiency cores mid-run, and nothing here
pins them.

**The first version of the CLI printed a SUPPORTED / NOT-SUPPORTED verdict
straight off the means**, which is how two runs of refused data produced
opposite conclusions. That is precisely the failure `perfgate` exists to
prevent, committed by the tool that consumes `perfgate`. The verdict is now
gated on the points: any point that does not qualify makes the trend
INCONCLUSIVE, and the percentage is printed with an explicit instruction not
to read it as a result.

What *was* stable across all three runs, and is worth more than the trend:
SoA peaks near 6 GB/s while AoS reaches 35-42 GB/s. SoA is bound by the
per-element loop, not by memory — which is the observation that produced
`layout/achievable-ratio` in layout 0.3.0.


## 0.1.0 — 2026-08-03

The effect half of the `machine` contract, which declares
`:must-not [:probe-hardware]` precisely so this could exist separately.

- `machine.probe` — Darwin `sysctl` and Linux `/sys/devices/system/cpu`
  parsers. Pure, with the effect injected, so every parser is testable against
  captured output on machines nobody has in the room.
- `machine.bench` — the measurement half: AoS vs SoA as real `double-array`s,
  warmup then raw samples, no verdict of its own.
- `clojure -M:probe --summary` and `clojure -M:bench [n] [width] [warmup] [reps]`.

First run on an Apple M1 Max forced two changes upstream in `machine` (0.2.0):
optional `:ways`, because macOS does not report associativity at all, and
heterogeneous CPU clusters, because one flat `:cache` cannot describe 8
performance cores beside 2 efficiency ones.

10 tests, 35 assertions.
