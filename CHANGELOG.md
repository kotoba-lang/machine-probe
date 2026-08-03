# Changelog

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
