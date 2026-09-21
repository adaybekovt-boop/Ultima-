# Killer modules v1: exact A/B protocol

## Rule zero

The three modules are experimental and default off. Do not enable them in a release default, quote
a speedup, or call a synthetic/unit result a player-visible win until this protocol passes on an
actual client with Sodium + Iris + Lithium. The primary cache metric is loading/reload wall time;
the primary broker and warmup metrics are tail latency and visibility/first-use guardrails, not
average FPS.

## Required environment record

For every run, archive the benchmark JSON and record:

- CPU, GPU, RAM, OS, power plan, thermals, display refresh/VRR, Java build, and JVM flags;
- Minecraft, Fabric Loader/API, Ultima git SHA, Sodium, Iris, Lithium, and optional mod versions;
- GPU driver and OpenGL renderer;
- shader pack name/version/settings, resource packs, resolution, VSync/FPS cap, render/simulation
  distance, graphics settings, and Sodium/Iris settings;
- world copy hash/seed, dimension, start pose, replay rates, and route label;
- whether this is a cold process, a cold Ultima artifact cache, or a warm Ultima artifact cache.

Keep the normal driver shader cache in its ordinary state. “Cold artifact cache” means only Ultima's
`<gameDir>/cache/ultima/iris-frontend-v1` directory is absent. The supplied launcher moves that
directory to a uniquely named backup; it does not delete it or touch a driver cache.

Before client testing, verify the exact external artifacts offline:

```bash
scripts/check-killer-adapters.sh /path/to/iris-fabric-1.11.4+mc26.2.jar \
  /path/to/sodium-fabric-0.9.2+mc26.2.jar
```

Reject a run if diagnostics show an inactive requested adapter, fail-open state, verify mismatch,
changed settings, different route endpoints, missing samples, thermal throttling, or a different
world/mod/pack set between a pair.

## Test matrix

Run at least these environments. A row is not evidence until the relevant real-client smoke and A/B
steps pass.

| # | Environment | Purpose |
|---:|---|---|
| 1 | Ultima only | Vanilla-renderer regression/safety baseline; killer adapters that require Iris/Sodium stay inactive |
| 2 | Sodium + Iris + Lithium, no Ultima | Ecosystem baseline, measured with the same external capture method if in-mod JSON is unavailable |
| 3 | S+I+L + Ultima, all three OFF | Measures base Ultima/client-benchmark overhead |
| 4 | S+I+L + artifact cache only | Cold and warm persistent-cache comparisons |
| 5 | S+I+L + broker only | trace overhead, trace versus control, and trace versus static budget |
| 6 | S+I+L + warmup only | profile-only versus profile+warm |
| 7 | S+I+L + all three | Interaction test; run trace first, then explicit broker control |

Repeat relevant rows with C2ME, ImmediatelyFast, ModernFix, EntityCulling, and FerriteCore one at a
time or in a known-compatible pack. Do not manufacture an incompatible combination for table
coverage. C2ME remains observer-only and ModernFix/GeckoLib warmup adapters remain off.

## Deterministic route

Use a cloned single-player world and tick replay (`REPLAY_MODE=tick`). Build a straight, immutable
route along the configured Z axis so the same tick reaches the same location. A warmup acceptance
route must include, in a fixed order:

1. opaque/cutout/translucent blocks and animated textures;
2. a water/fluid segment;
3. repeatable particle emitters;
4. multiple vanilla entity renderer/model types;
5. a shader-heavy view;
6. hotbar/inventory-visible items and many block models;
7. mod entities only when the exact optional mod is installed on both sides.

Do not use random mob spawning, weather, uncontrolled redstone, network players, or a different
camera route. Prefer a slow route such as `CAMERA_Z_PER_TICK=0.25`; the default chunk-flight rate is
intended for backlog stress, not visual warmup coverage. Capture start/end screenshots and verify
the recorded start/end poses.

Tick replay makes camera pose FPS independent. It does not make asynchronous world generation
deterministic. Pre-generate the route for stationary/render warmup tests; use a separate cloned,
identically generated flight world for chunk-admission tests.

## Balanced launcher

The launcher alternates OFF→ON and ON→OFF to reduce order/thermal drift and requires six pairs for
the primary summary:

```bash
GAME_DIR=/path/to/test-instance \
WORLD=KillerRoute \
CAMERA_MODE=chunk_flight \
CAMERA_X=0 CAMERA_Y=96 CAMERA_Z=0 \
CAMERA_YAW=0 CAMERA_PITCH=0 \
CAMERA_Z_PER_TICK=0.25 \
CAPTURE_SCREENSHOTS=1 \
scripts/bench-killer-modules-ab.sh broker-trace 6
```

Available profiles:

| Profile | OFF side | ON side |
|---|---|---|
| `artifact-cold` | cache module off | cache module on, Ultima cache moved aside before every ON launch |
| `artifact-warm` | cache module off | cache module on after one excluded priming launch |
| `broker-trace` | broker off | broker trace-only; measures observer overhead |
| `broker-control` | broker trace-only | same instrumentation, adaptive control |
| `broker-static` | broker trace-only | same instrumentation, static admission budget |
| `warmup` | warmup module in `profile` mode | same profiler in `warm` mode |
| `all-trace` | all three off | all three on, broker trace-only |
| `all-control` | all three off | all three on, broker adaptive control |

`scripts/bench-client.sh` writes the requested module state and JVM properties, invokes `runClient`,
and fails if no JSON is produced. `scripts/summarize-client-bench.py` reports every pair, paired
deltas, SD, a 95% t interval, tail outliers, killer-module counters, and broker guardrail failures.
It never drops a tail outlier.

The old `REPLAY_MODE=frame` remains diagnostic-only. Do not use it for a performance conclusion,
because camera distance would again depend on achieved FPS.

## Module A: artifact cache protocol

### Correctness/verify pass

Run a non-performance session with:

```bash
IRIS_CACHE_VERIFY=1 \
GAME_DIR=/path/to/test-instance WORLD=KillerRoute \
scripts/bench-killer-modules-ab.sh artifact-warm 1
```

Exercise initial load, F3+T/resource reload, shader pack reload/change, world enter/leave, and a
second process launch. Require `verifyMismatches=0`, no corruption/read/write failure, identical
rendering/screenshots, and hits after priming. Verify mode intentionally performs the fresh
transform and must not be used for timing.

Also test source/pack-setting changes, a read-only cache location, and an unavailable/write-failing
cache location. The expected result is a miss/original Iris path and a recorded failure, not a game
failure. Unit tests simulate format corruption, truncation, unknown schema, and write denial; true
ENOSPC and OS-specific read-only behavior still require a machine/VM test.

### Performance pass

Run `artifact-cold` and `artifact-warm` as separate six-pair experiments with verify mode off. Use
the same shader pack and ordinary driver-cache state. Compare:

- top-level `shaderReload.lastNs`/`maximumNs` from the symmetric full `ShaderManager.apply` timer;
- artifact requests/hits/misses and `frontendTransformNs`;
- cache read/write time/bytes and `transformNsSavedEstimate`;
- total launch/reload wall time captured externally if possible.

Cold cache should preserve output and may add bounded write cost. Warm cache must show real hits.
Accept a loading claim only if warm-cache reload wall time improves outside noise across balanced
pairs. If frontend transforms are a negligible part of reload wall time, record that result and make
no loading-speed claim. FPS is not the acceptance metric.

## Module B: broker protocol

### Phase 1 must precede control

Run `broker-trace` in stationary, chunk-flight, camera-teleport, GPU-bound, CPU-bound, and
intentionally overloaded-CPU scenes; remote and integrated-server sessions; render distance
8/16/32; and low/high worker counts. Also repeat with C2ME absent/present.

Use the trace to establish temporal correlation between bad frames and avoidable deferred
admission. Inspect frame p95/p99/p99.9, CPU/GPU timing availability, queue depth, worker occupancy,
pending-age p95/p99, starts/completions, mesh-ready results, upload time/bytes, GC correlation,
initial-build backlog, and request-to-renderable proxy latency. If the correlation is absent, do not
proceed to a broker performance claim.

### Control and static control

Run `broker-control` and `broker-static` against their trace-only sides. Required simultaneous
metrics are:

- frame p95/p99/p99.9, 1% low, and 0.1% low;
- task-completion throughput and mesh-ready count;
- deferred queue/backlog and pending age;
- p95/p99/max request-to-renderable proxy;
- current/max initial-build “visible hole” proxy;
- upload latency/bytes and integrated-server MSPT.

Reject the adaptive result if p99 request-to-renderable proxy or maximum initial-build backlog
worsens by more than 10%, or task-completion throughput falls by more than 5%, unless a larger
predeclared uncertainty interval proves the apparent change is noise. The summarizer flags these
raw pair guardrails. Also reject task loss, a queue that never drains, unbounded age, or a failure to
recover after teleport/world change/disconnect/reload.

If a well-chosen `static` budget matches adaptive frame tails and visibility/throughput, prefer the
simpler static tuning or Sodium default; the adaptive broker has not justified itself.

## Module C: warmup protocol

Run the exact cold-process curated route as `warmup` (profile-only versus warm). Do not reuse a
process: class initialization is part of the question. Require identical screenshots/state and no
entity spawn, inventory/world mutation, sounds, packets, gameplay events, or animation advancement.

Compare hitch count, max/p99/p99.9 frame, 1%/0.1% low, `firstUseHitchesBefore` on the profile side,
`firstUseHitchesAfter` on the warm side, slow-operation identity, warmed item count, warmup time,
memory delta, and adapter coverage. Treat `gpuResourceDelta=-1` as unavailable, not zero.

Accept only when the warmed operations were actual later first-use costs on the scripted route and
tail hitches improve without unacceptable startup time or memory growth. The current static
RenderType adapter may legitimately produce no measurable benefit; that result must disable any
warmup performance claim. Do not infer an Iris, GeckoLib, or ModernFix benefit from an inactive
adapter.

## Resource lifecycle smoke checklist

For each relevant profile, manually complete:

- launch twice (persistent cache);
- enter world, leave to title, re-enter;
- disconnect/reconnect to a remote server;
- F3+T/resource reload and shader-pack reload;
- camera teleport and sustained chunk flight;
- stationary GPU-bound and CPU-bound views;
- cache directory read-only/write failure where the OS test setup permits it;
- close during/after warmup and reconnect;
- integrated server and remote server.

After every run execute `/ultima debug killer-modules`, archive the JSON, and confirm requested,
active, adapter, and fail-open states before accepting measurements.

## Server-side companion record

The three modules are client focused and must not be credited with server gains. On integrated
server runs, record the existing server telemetry: MSPT median/p95/p99/p99.9, allocation, GC, and
worker contention. A server regression invalidates the client result. Dedicated-server results are
compatibility checks, not expected performance wins for these modules.

## Allowed and forbidden claims

Allowed before hardware A/B:

- “production-testable, default-off prototype”;
- “exact Iris/Sodium artifact gated”;
- “persistent transformed-source cache; normal compile/link retained”;
- “trace-only by default; control gates only Sodium deferred dequeue admission”;
- “warmup limited to a curated state-safe adapter; unsupported adapters are explicit.”

Allowed after a passing experiment: only the measured effect, on the recorded hardware/modpack,
shader pack, scene, mode, and confidence interval.

Forbidden without new evidence:

- a general FPS, reload, hitch, MSPT, or memory improvement;
- “GPU shader cache” or “program binary cache”;
- exact pixel-visible latency from `firstVisibleProxy`;
- C2ME scheduling control;
- Iris lazy-program warmup;
- GeckoLib/ModernFix warmup coverage;
- Vulkan support;
- compatibility with an unrecognized Iris/Sodium fork or version.
