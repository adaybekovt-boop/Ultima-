# Ultima client performance report

Date: 2026-08-14  
Target: Minecraft Java 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, Java 25

## Executive summary

This pass audited Minecraft 26.2's client extraction, terrain preparation, entity/block-entity
render-state preparation, particle extraction, chunk rebuild pipeline, and frame submission paths.
It retained two high-frequency, same-frame allocation eliminations plus one duplicate invalidation
write elimination in vanilla terrain preparation.

Both produce the same chunk uniform bytes and iterate the same render layers in the same order. They
do not cull geometry, alter materials, change light/shader inputs, reorder transparency, reduce
quality settings, or bypass a render stage.

The terrain preparation surface is replaced or heavily transformed by Sodium/Iris. All three
modules are therefore automatically disabled when either mod is loaded. No attempt is made to
override their renderer hooks.

## Implemented optimizations

### `client_chunk_matrix_reuse`

- **Bottleneck:** `LevelRenderer.prepareChunkRenders` creates
  `new Matrix4f(modelViewMatrix)` for every visible section that has at least one drawable layer.
- **Root cause:** every `DynamicUniforms.ChunkSectionInfo` receives the same frame model-view matrix,
  but vanilla snapshots it independently for each section.
- **Frequency:** once per renderable visible section per frame; potentially thousands of copies at
  high render distance.
- **Saved work:** all but one matrix allocation/copy per invocation.
- **Implementation:** the first wrapped constructor operation creates the normal snapshot, preserving
  its inner MixinExtras wrapper chain. Remaining sections reuse that snapshot for the current
  invocation.
- **Why output is unchanged:** `DynamicUniformStorage.writeUniforms` synchronously serializes every
  `ChunkSectionInfo` before `prepareChunkRenders` returns. The snapshot is not mutated during that
  interval, so every section receives the exact same 16 float values vanilla would have copied.
- **Correctness risk:** low. Cache scope is one method invocation and resets at `HEAD`.
- **Compatibility strategy:** category **B — vanilla-only, automatically disabled for Sodium/Iris**.
- **Shader risk:** low; submitted matrix bytes and section offsets are unchanged.
- **Expected impact:** reduced per-frame allocation volume and copying proportional to visible
  section count; most useful for CPU-bound high-render-distance scenes and allocation-driven spikes.
- **Known limitation:** another mod wrapping the same matrix constructor is invoked once per frame,
  not once per section. This is another reason for the renderer-mod compatibility gate.

### `client_chunk_layer_array_reuse`

- **Bottleneck:** the inner visible-section loop calls `ChunkSectionLayer.values()`. Java clones the
  enum's three-element backing array on every call.
- **Root cause:** the array is only iterated; no caller mutates it, and membership/order cannot change
  during a frame.
- **Frequency:** once for setup plus once per visible section per frame.
- **Saved work:** one cloned reference array per visible section, leaving one normal `values()` call
  per `prepareChunkRenders` invocation.
- **Implementation:** retain the first wrapped result for the method invocation and return it to
  subsequent loops.
- **Why output is unchanged:** the same `SOLID`, `CUTOUT`, `TRANSLUCENT` constants are visited in the
  same order. Draw grouping and translucent reversal are untouched.
- **Correctness risk:** low. Cache scope is one method invocation and resets at `HEAD`.
- **Compatibility strategy:** category **B — vanilla-only, automatically disabled for Sodium/Iris**.
- **Shader risk:** none; no render data or command ordering changes.
- **Expected impact:** removes small but extremely frequent arrays, reducing young-generation pressure
  and frame-time variance in section-heavy views.

### `client_chunk_dirty_dedup`

- **Bottleneck:** `LevelExtractor.setBlockDirty` expands one changed block to a 3×3×3 block volume
  and performs 27 section-storage lookups/writes. Away from a 16-block boundary all 27 target the
  same section. `setBlocksDirty` similarly repeats writes for every block coordinate in a range.
- **Root cause:** invalidation is section-granular, but vanilla deduplicates neither the expanded
  single-block volume nor larger block ranges before writing section state.
- **Frequency:** every client-visible block/model update; bursty redstone, block updates, and chunk
  changes amplify it and can contribute to heavy-frame rebuild scheduling spikes.
- **Saved work:** 26 of 27 writes for a block away from section boundaries; exact unique section
  ranges for boundary/range cases.
- **Implementation:** convert expanded block minima/maxima to section coordinates first, then visit
  each section once in vanilla's first-encounter z/x/y order.
- **Why output is unchanged:** `SectionDirtyState.setDirty` is idempotent for repeated calls with the
  same `playerChanged` value. The exact same section set is dirtied immediately; no rebuild is
  skipped, delayed, or narrowed beyond duplicate writes.
- **Correctness risk:** low for normal world coordinates. Integer-extreme/inverted inputs fall
  through to vanilla.
- **Compatibility strategy:** category **B — vanilla-only, automatically disabled for Sodium/Iris**.
- **Shader risk:** none; this changes only duplicate dirty bookkeeping, not rebuild content/timing.
- **Expected impact:** mostly improved frame-time spikes in update-heavy scenes rather than standing
  average FPS.

## Candidate ranking

| Candidate | Frequency / saved work | Correctness / compatibility / shader risk | Decision |
|---|---|---|---|
| Per-section model-view copies | visible renderable sections every frame; matrix allocation + 16-float copy | low / medium / low | implemented, vanilla-only gate |
| Per-section `ChunkSectionLayer.values()` | every visible section every frame; cloned array | low / medium / none | implemented, vanilla-only gate |
| Duplicate section-dirty writes | 27 writes for common single-block updates; potentially many for ranges | low / medium / none | implemented, vanilla-only gate |
| Pool all entity render states | every visible entity; large allocation opportunity | high reset/invalidation risk across hundreds of state subclasses | rejected |
| Reuse particle quaternions | every visible quad particle; one object allocation | custom facing modes can retain/mutate identity; constructor wrappers become observable | rejected |
| Cache chunk draw objects/maps across frames | many section layers; potentially large | GPU buffer relocation, UBO indices, translucency, and frame lifetime make invalidation broad | rejected |
| Hoist `Util.getMillis()` out of section loop | every visible section; clock read | changes per-section fade timing, however slightly | rejected |
| Narrow chunk dirty propagation | burst rebuild reduction | model/neighbor dependency proof is incomplete | rejected |
| Reuse entity renderer lookup from culling | visible entities; one map/type lookup | cross-call mutable state and competing entity mods; likely small impact | rejected |
| Reuse particle frustum copy | one allocation per frame | low risk but too little expected impact alone | rejected |
| Submission/frame-graph restructuring | potentially macro | renderer/shader ordering and resource-lifetime risk | rejected |

## Sodium, Iris, and shaders

All three optimization modules declare `sodium` and `iris` as incompatible module IDs.
`UltimaConfig` disables them before their Mixins apply. The modules remain available on the vanilla
renderer and do not touch scene visibility, geometry, materials, light, transparency ordering,
depth state, shader inputs, or frame-graph passes.

With Sodium/Iris installed, this pass contributes no terrain optimization and makes no FPS claim.
The existing simulation/collision modules retain their independent defaults.

## Client benchmark mode

`client_benchmark` is an opt-in instrumentation module and is disabled in normal play.
`scripts/bench-client.sh <label> <disabled|default|enabled>`:

1. writes every Ultima module state explicitly;
2. keeps benchmark recorder overhead present on both A/B sides;
3. waits until a world is loaded;
4. discards a configurable warmup (`WARMUP_FRAMES`, default 1200);
5. records a configurable sample (`SAMPLE_FRAMES`, default 12000);
6. writes raw frame times and summary metrics to
   `run/ultima-client-benchmark-<label>.json`.

Recorded metrics:

- average and median FPS;
- 1% and 0.1% low FPS (mean of the slowest 1%/0.1% frame times);
- average, P95, and P99 frame time;
- raw nanosecond frame times;
- actual chunk matrix copies and layer arrays avoided;
- duplicate section-dirty writes avoided;
- explicit `cpuFrameTimeAvailable=false` when only whole-frame wall time is available.

For a credible A/B, both runs must use a copied identical world and identical camera path,
resolution, render/simulation distance, frame cap/vsync, graphics settings, shader/resource packs,
entity population, and particle population. Run order should be alternated across at least three
pairs. Do not compare a standing camera against a moving route.

## Verification

- Vanilla 26.2 source was inspected for `LevelExtractor`, `LevelRenderer`,
  `DynamicUniformStorage`, chunk compiler/dispatcher/cache, entity and block-entity dispatchers,
  particle groups, frustum behavior, and chunk submission.
- Main and client source sets compile.
- `forensicRegressionTest` covers module defaults, dependency behavior, and renderer compatibility
  metadata in addition to existing arithmetic/order checks.
- A dedicated-server smoke run reported 1 of 4 common modules enabled and completed two sprints,
  confirming client resources/Mixins are excluded from server initialization.
- A graphical client smoke test and short benchmark-recorder validation are recorded below.

A graphical dev client with the final client Mixin set reported 5 of 8 applicable modules enabled,
loaded the `ultima-bench` world, and rendered normally for more than 30 seconds. Terrain, lighting,
entities, UI, and debug rendering showed no visible artifact; no Mixin failure, crash, or gameplay
exception occurred. An earlier in-world software-rendered smoke sample completed 60 warmup + 300
measured frames and wrote valid JSON without a crash.

That short VM sample is not performance evidence, but its counters confirm the targeted frequency:

- 143,113 matrix copies avoided, **477.0 per measured frame**;
- 151,022 layer-array clones avoided, **503.4 per measured frame**.

The recorded sample's 8.75 average FPS / 4.44 1% low and the final smoke's observed 28–33 FPS reflect
different VM scenes/display conditions and must not be compared or treated as an A/B. The final
dirty-range module is additionally covered by 10,000 randomized old-loop vs unique-section
set/order comparisons.

## Measurement status

This environment does not expose a physical GPU suitable for release FPS claims. Any VM/software
rendering number is a functional check only. Real-PC A/B data is still required before claiming an
average FPS or 1% low improvement.

## Final status

CLIENT OPTIMIZATIONS IMPLEMENTED: 3

DEFAULT-ENABLED: [`client_chunk_matrix_reuse`, `client_chunk_layer_array_reuse`,
`client_chunk_dirty_dedup` on vanilla renderer; automatically disabled with Sodium/Iris]

OPT-IN: [`client_benchmark` instrumentation only]

CLIENT BUILD: PASS

REAL GPU BENCHMARK AVAILABLE: NO

MEASURED FPS GAIN: NOT YET MEASURED

MEASURED 1% LOW GAIN: NOT YET MEASURED

READY FOR REAL-PC A/B TEST: YES
