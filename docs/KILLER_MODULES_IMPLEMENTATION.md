# Killer modules v1: implementation architecture

## Scope and safety status

This branch adds three independent, client-only, default-off modules:

- `iris_shader_frontend_artifact_cache`
- `cross_pipeline_admission_broker`
- `render_warmup_system`

They do not replace Sodium, Iris, or Lithium, do not fork them, and do not change graphics or
gameplay settings. Each module has its own launch-time Mixin gate. Unknown external bytecode,
unsupported versions, missing mods, unsupported backends, and runtime failures select the
original owner path.

The implementation target is Minecraft 26.2. Version-specific adapters are deliberately narrow:

| Component | v1 support | Adapter policy |
|---|---:|---|
| Minecraft | 26.2 | Fabric mappings used by this build |
| Iris | `1.11.4+mc26.2` | Exact version, complete-jar SHA-256, and `TransformPatcher.class` SHA-256 |
| Sodium | `0.9.2+mc26.2` | Exact version, complete-jar SHA-256, and `RenderSectionManager.class` SHA-256 |
| Lithium | No direct adapter | Recorded in diagnostics; simulation algorithms are never intercepted |
| C2ME | Observer state only | Presence is recorded; no active admission control or private API access |

Supported official artifact fingerprints:

| Artifact | SHA-256 |
|---|---|
| Iris jar | `f1f7ab57c974d193ba33aa285864a0ded949216f402116fceaf4dc7739b4dd7c` |
| Iris `TransformPatcher.class` | `101fb251c68034e4c8346de1c550616059d896dbfbb3fae56e5982a89877c25f` |
| Sodium jar | `16a5e91db49750f8c046ecca3b8f2af5a28ea6f9b6581b42f123ca8fda20864f` |
| Sodium `RenderSectionManager.class` | `5a409c73d5e4a30853a5b4896db8610607d07b3a111b2cf80ffc82a81d11b443` |

`scripts/check-killer-adapters.sh` performs the same hash checks offline and verifies the method
descriptors used by the Mixins. A fork that reuses a version string does not inherit an adapter.

## Module gating and diagnostics

All three keys are registered in `UltimaModules` with `enabledByDefault=false`. They appear in the
`Experimental Killer Modules` settings category. The UI reports Requested, Active, the disabled
reason, adapter/version state, and the runtime fail-open state.

The client command below prints a summary and atomically writes the complete JSON export to
`<gameDir>/ultima-killer-modules-diagnostics.json`:

```text
/ultima debug killer-modules
```

The export includes Minecraft, Ultima, Ultima git SHA, Iris, Sodium, Lithium, and C2ME versions;
adapter fingerprints; requested/applied/runtime state; and module-specific counters. The opt-in
client benchmark embeds the same object as `killerModules`.

## A. Iris shader frontend artifact cache

### Confirmed boundary

Iris 1.11.4 routes graphics transforms through the private
`TransformPatcher.transform(name, vertex, geometry, tessControl, tessEval, fragment, parameters)`
method and compute transforms through `TransformPatcher.transformCompute(name, compute,
parameters)`. The adapter intercepts the method at HEAD and RETURN:

1. build a complete key before Iris mutates its parameter scratch fields;
2. on hit, return the transformed `Map<PatchShaderType, String>` to the normal caller;
3. on miss, let the original Iris method execute and store only its returned stage/source map;
4. leave driver compilation and program linking unchanged.

The payload is transformed source text, including explicit null values for absent shader stages.
It never contains a GL handle, program binary, live AST, or driver cache data.

### Key completeness

Key schema 1 hashes, with typed and length-delimited encoding:

- transform kind and program name;
- every relevant stage name and its nullable raw source;
- exact supported `Parameters` implementation name;
- every validated field in the Iris parameter inheritance graph: patch, texture map, texture
  overrides, geometry/tessellation flags, texture stage, alpha/shadow context, and vanilla
  attribute/line/cloud/chunk-offset inputs as applicable;
- canonicalized map and set contents, independent of iteration order;
- Iris debug-transform option and the active depth convention (`zZeroToOne`);
- Minecraft and Iris versions;
- adapter revision and the complete jar/class fingerprint;
- Ultima key schema.

Iris' mutable `Parameters.type` and `Parameters.name` fields are transformer scratch. They are
excluded because the program name is hashed separately and the fields are mutated during a
transform. The encoder validates the exact declared field sets of all supported parameter classes.
An unknown class, field, record, map value, cycle, or inaccessible field makes the request
unkeyable and therefore a miss. There is no partial key.

### Disk format and bounds

Entries live at `<gameDir>/cache/ultima/iris-frontend-v1/<key>.uifa`. Disk schema 2 contains:

| Field | Validation |
|---|---|
| Magic `UIFA` | Exact match |
| Schema | Exact match; unknown schema is a miss/invalidation |
| Key | Constant-time SHA-256 comparison with requested key |
| Creation time | Diagnostic only |
| Measured transform time | Used only for saved-time estimate |
| Payload length | Exact file-size match, maximum 64 MiB |
| Payload checksum | SHA-256 |
| Payload | At most eight named nullable stage sources |

Writes use a same-directory temporary file, complete writes, `FileChannel.force(true)`, close,
atomic replace when supported, and best-effort directory fsync. Sixty-four key stripes serialize
same-key races. Read, write, permission, and cleanup failures never escape into Iris.

The default bounds are 256 MiB and 2,048 entries, configurable with:

```text
-Dultima.irisShaderFrontendArtifactCache.maxBytes=<positive bytes>
-Dultima.irisShaderFrontendArtifactCache.maxEntries=<positive count>
```

The directory is indexed once on first cache use. Hot reads use the in-memory metadata index and
load one payload; payloads are not retained in the store. Last-use metadata is touched at most once
per minute and startup/overflow cleanup is approximate LRU.

### Verify mode and backend policy

`-Dultima.irisShaderFrontendArtifactCache.verify=true` turns every would-be hit into a fresh Iris
transform and compares the complete nullable stage map. A mismatch removes the entry and disables
the cache for the process. Verify mode is a correctness tool, not a performance configuration.

The v1 runtime adapter is enabled only on an identified OpenGL backend. A Vulkan or unknown backend
fails closed even though a future CPU-only adapter may be possible. Iris absent, a different Iris
version, an unknown jar/class fingerprint, reflection failure, or an unrecognized transform graph
also leaves the original Iris path active.

Metrics include requests, hits, misses, invalidations, corruptions, read/write failures, estimated
transform time saved, measured frontend-transform time, read/write time and bytes, cache size and
entry count, verify matches/mismatches, and full `ShaderManager.apply` reload wall time. Safe,
stable Iris hooks for separately attributing downstream driver compile and link time were not found;
those fields are explicitly `null` rather than estimated.

## B. Cross-pipeline admission broker

### Phase 1: trace-only

The default runtime mode is `trace`, even when the module is requested. Trace mode never denies an
admission. It observes:

- frame wall time, render-thread CPU time supplied by the benchmark, Minecraft render time, and an
  existing GPU timer result when available;
- Sodium deferred queue depth and busy/total worker counts;
- submission age, task starts/completions, mesh-ready results, uploads, bytes, and upload duration;
- initial-build request-to-renderable-state latency and outstanding initial-build backlog;
- integrated-server average tick time, cheap periodic GC collection correlation, and camera
  teleports;
- C2ME presence only. There is no claimed stable public C2ME pressure API in this adapter.

Latency samples use bounded primitive rings; worker starts/completions use thread-local counters
aggregated only for diagnostics. No string lookup, config lookup, log formatting, `AtomicLong`, or
`ConcurrentHashMap` is added to the per-task decision path.

`firstVisibleProxy` has a deliberately conservative name: it is measured from Sodium's
`INITIAL_BUILD` request to the section becoming a built/renderable CPU state. It occurs before the
corresponding upload can be proven visible on screen. `visibleHoleProxy` is the number of requested
initial-build sections not yet completed or cancelled; it is not a pixel-occlusion measurement.

### Phase 2: one-sided Sodium control

The exact integration point is the HEAD of Sodium 0.9.2
`RenderSectionManager.submitDeferredSectionTasks`, before its loop calls
`DeferredTaskList.dequeueNextSectionPos`. Sodium has already submitted zero-frame, one-frame, and
important tasks before this method. Cancelling at this boundary therefore does not dequeue, transfer
ownership, cancel, duplicate, or reschedule a job.

Runtime modes are selected with:

```text
-Dultima.crossPipelineAdmissionBroker.mode=trace|control|static
```

`control` uses a 128-frame window, recalculates percentiles every eight frames, and applies simple
hysteresis around the configured frame target. Inputs are p95/latest CPU frame time, an available
GPU result, integrated-server pressure, queue depth, and worker utilization. It admits normally
under low pressure and temporarily skips only the deferred dequeue loop under high pressure.

Safety exits are structural or explicit:

- update-immediately/synchronous work bypasses;
- important, blocking, and main-thread-awaited work is submitted in Sodium's earlier owner paths;
- a next deferred task aged at least two seconds bypasses;
- a permit is forced at the minimum interval and after the maximum defer interval;
- empty queue, stale/missing telemetry, reset, unknown mode, or runtime exception restores the
  original owner behavior;
- world change, disconnect, resource reload, and a camera jump greater than 128 blocks reset the
  controller.

Defaults can be changed only for controlled experiments:

```text
-Dultima.crossPipelineAdmissionBroker.targetFrameNanos=16666667
-Dultima.crossPipelineAdmissionBroker.staleTelemetryNanos=500000000
-Dultima.crossPipelineAdmissionBroker.minimumPermitIntervalNanos=50000000
-Dultima.crossPipelineAdmissionBroker.maximumDeferNanos=250000000
-Dultima.crossPipelineAdmissionBroker.starvationAgeNanos=2000000000
-Dultima.crossPipelineAdmissionBroker.staticPermitEveryFrames=2
```

`static` admits one deferred batch every configured number of calls and exists only as the required
static-tuning control. C2ME is not actively regulated. Iris scheduling, Lithium simulation, and
shader quality/resolution are never changed. If the exact Sodium artifact is absent or different,
the module does not apply.

## C. Render warmup system

### First-use profiler

The profiler measures the current Minecraft 26.2 surfaces that can expose rare render setup work:

- `RenderType.create`;
- `GpuDevice.precompilePipeline`;
- `EntityRenderers.createEntityRenderers`;
- `ParticleResources.registerProviders`;
- only the first `FluidRenderer.tesselate` invocation;
- frame duration around frames containing a first touch.

It records first, repeat, and warmup duration, subsystem, frame, whether warmup preceded natural
use, hitch counts, maximum and p99 first-use frame, and an optional stack fingerprint. Stack walking
and detailed logging require `-Dultima.renderWarmupSystem.detailedProfiler=true`.

Source inspection disproved a generic lazy-Iris-program premise for this target: Minecraft's shader
reload precompiles static pipelines, and Iris creates/compiles its programs during pipeline
construction. Therefore v1 has no fake Iris `warmupEverything` adapter.

### Warmup plan and adapters

Two modes allow symmetric measurement:

```text
-Dultima.renderWarmupSystem.mode=profile  # instrument, execute no warmup
-Dultima.renderWarmupSystem.mode=warm     # instrument and run supported adapters (default)
```

An adapter must implement `supports`, `discover`, `warm(deadline)`, timeout behavior, and fail-open
reporting. Work is pumped only from the render thread. The default total budget is 100 ms, the
per-frame slice is 2 ms, and the current adapter timeout is 25 ms. Remaining work becomes deferred
when the total budget expires, and disconnect cancels it.

The only active v1 adapter is `vanilla_static_render_types`: 18 curated, no-argument static
`RenderTypes` accessors. It creates no world or entity, sends no packet, changes no inventory,
plays no sound, advances no animation, and performs no arbitrary model preload.

Explicitly inactive adapters are part of diagnostics:

| Adapter | State | Reason |
|---|---|---|
| Iris programs | Off | Target path is eager; no verified lazy program set to warm |
| GeckoLib | Off even when present | No versioned state-safe public surface was established; fake entity/world calls are forbidden |
| ModernFix dynamic resources | Off even when present | No exact selective public API was established; preloading every model would defeat its memory/startup design |

Any adapter exception cancels remaining work and fails open. Metrics expose plan progress, discovered
and warmed items, failures, wall time, memory delta, adapter coverage, first-use hitch counts, max/p99
first-use frame, and the slowest operations. GPU resource delta is `-1` when a trustworthy count is
unavailable.

## Shared benchmark changes

The primary replay clock is now tick based. Camera position and orientation are functions of the
logical tick, so a faster side cannot travel farther merely because it rendered more frames. The
old frame-indexed mode remains available as `replayMode=frame` for diagnostics.

Benchmark schema 5 records median, p95, p99, p99.9, 1% low, 0.1% low, render-thread CPU,
Minecraft render time, GPU time when an existing timer supplies it, GC/memory, integrated-server
MSPT, and all killer-module metrics. A benchmark-only Mixin times the complete
`ShaderManager.apply` boundary on both cache-OFF and cache-ON sides.

See `KILLER_MODULES_BENCHMARK.md` for the complete A/B protocol.

## Verification inventory

Gradle tasks added for pure/contract coverage:

- `benchmarkReplayTest`: tick replay independence, legacy mode, dynamic sample buffer, reload timer;
- `irisArtifactCacheTest`: cold/warm, nullable stages, corruption, truncation, schema rejection,
  write failure, same-key concurrency, and bounds;
- `irisTransformKeyTest`: canonical ordering, all environment inputs, sources, pack-derived maps,
  alpha/shadow context, mutable scratch exclusion, and unknown implementation rejection;
- `admissionBrokerTest`: urgent bypass, stale telemetry, starvation, maximum defer, reset, static
  control, no loss, no double dequeue;
- `brokerMetricsTest`: bounded rings, latency percentiles, backlog completion/cancellation, and
  thread-local aggregation;
- `renderWarmupTest`: continuation, budgets, unsupported adapters, exception fail-open, timeout,
  cancellation, and state restoration;
- `killerDiagnosticsTest`: settings catalog coverage and parseable diagnostics JSON.

These tests establish data-format and control-flow contracts. They do not replace a real OpenGL
client test with Sodium, Iris, Lithium, a shader pack, world reloads, and multiple processes.

## Claims boundary

The code supports the claims that adapters are default off, version/fingerprint gated, bounded,
independently removable, and fail open under the covered faults. It does not yet support a claim of
better FPS, faster shader loading, fewer hitches, or better chunk visibility on real hardware.
Those claims require the protocol and guardrails in the benchmark document.
