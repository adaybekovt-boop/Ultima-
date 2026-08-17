# Mesher hardware A/B (first run)

Mesher cost is **section compile time**, not per-frame submit. Do not use
retained-terrain FPS as the primary mesher verdict. Both sides keep
`client_benchmark=true`. Only `mesher_fast_path` changes (`false` vs `true`).
`mesher_fast_path` stays default **OFF** in the shipped config.

This document is a runbook. It is **not** a performance claim.

## Shared JVM / program flags

```
-Dultima.clientBenchmark=true
-Dultima.clientBenchmark.exitAfterWrite=true
-Dultima.clientBenchmark.output=run/mesher_<scene>_pairN_<off|on>.json
-Dultima.modules.client_benchmark=true
-Dultima.modules.mesher_fast_path=<false|true>
-Dultima.modules.retained_terrain=false
```

Primary comparison: shipped defaults on both sides, with only
`mesher_fast_path` flipped (`false` vs `true`). Use
`scripts/bench-mesher-ab.sh` (it sets `FORCE_MODULES=mesher_fast_path=…`
and keeps `retained_terrain=false`). Do not use `mode=enabled` and do
not enable PR #3 lab modules.

Local dry-run of the harness (no client / no GPU):

```
DRY_RUN=1 bash scripts/bench-mesher-ab.sh
```

## Scene 1 — Cold world load (`mesher_cold_load`)

Purpose: how quickly the world appears around the player on first entry.

```
-Dultima.clientBenchmark.scene=mesher_cold_load
-Dultima.clientBenchmark.cameraMode=stationary
```

Start in a freshly generated / unloaded world (delete region files or use a
new seed). Sample window should cover the first compile storm after join.

Collect: `meshBuildNs`, `rebuildCount`, `meshBuildNsPerSection`,
`sectionsPerSecond`, `fastPathCoverageOfNonAir`, `fallbackByReason`,
`meshFastPathFailures`.

## Scene 2 — Forced rebuild storm (`mesher_rebuild_storm`)

Purpose: throughput under a mass remesh (render-distance change or F3+T).

```
-Dultima.clientBenchmark.scene=mesher_rebuild_storm
-Dultima.clientBenchmark.cameraMode=stationary
```

After warmup, trigger a resource-pack reload or bump render distance so a
large set of sections recompiles in one burst.

Collect the same counters. `meshFastPathCircuitBreakerTrips` must stay 0
on a vanilla overworld; non-zero is a correctness flag, not a speed win.

## Scene 3 — Steady-state streaming (`mesher_chunk_flight`)

Purpose: meshing time of newly compiled sections along the same chunk-flight
route used for retained-terrain validation.

```
-Dultima.clientBenchmark.scene=mesher_chunk_flight
-Dultima.clientBenchmark.cameraMode=chunk_flight
-Dultima.clientBenchmark.cameraZPerFrame=0.8
```

Primary metric is still `meshBuildNs` / `rebuildCount` / coverage, **not**
average FPS. FPS may be recorded by the existing frame recorder as context
only.

## Coverage

`fastPathCoverageOfNonAir` is the honest “how much of a real world we
accelerate” number: fast-path blocks / non-air compiled blocks, with
`fallbackByReason` (translucent, complex model, offset, circuit-breaker,
section fail-open, …).

## Summarizer

```
python3 scripts/summarize-mesher-bench.py run/mesher_*_pair1_off.json run/mesher_*_pair1_on.json
```

The summarizer prints CPU meshing counters only and refuses an FPS verdict.

Hardware launch (local GPU agent only):

```
SCENE=mesher_cold_load bash scripts/bench-mesher-ab.sh 6
SCENE=mesher_rebuild_storm bash scripts/bench-mesher-ab.sh 6
SCENE=mesher_chunk_flight bash scripts/bench-mesher-ab.sh 6
```

Cold-load default warmup is 60 frames and metrics are **not** reset at
warmup end, so the first compile storm stays in `mesherMetrics`.
Rebuild-storm and chunk-flight reset `MesherMetrics` at warmup end so
the sample window is the remesh / stream-in work. After warmup on
`mesher_rebuild_storm`, trigger F3+T or a render-distance change.
