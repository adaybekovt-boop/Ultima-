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

A real RTX 3090 A/B of the shipped defaults versus all optimizations disabled did **not** produce a
reliable FPS gain in a stationary CPU-bound Fancy scene. Counters show the targeted copies/writes
are avoided; that work reduction did not become a stable frame-time win. Do not advertise 2× FPS or
a noticeable FPS improvement from the current data.

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
  section count. A stationary RD16 Fancy scene on an RTX 3090 did not convert that into a reliable
  average-FPS gain; a high-render-distance or 1440p scene has not been measured.
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
- **Expected impact:** removes small but extremely frequent arrays. The RTX 3090 stationary A/B did
  not isolate a stable FPS effect from this change.

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
  average FPS. The measured stationary scene had only ~2.5 duplicate dirty writes avoided per frame
  and is the wrong workload to judge this module.

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
The existing simulation/collision modules retain their independent defaults. Automatic disable is
implemented in `UltimaConfig` and confirmed by registry metadata; it has **not** been runtime-tested
against Sodium or Iris on Minecraft 26.2 because those mods were absent from the measured profile.

## Client benchmark mode

`client_benchmark` is an opt-in instrumentation module and is disabled in normal play.
`scripts/bench-client.sh <label> <disabled|default|enabled>`:

1. writes every Ultima module state explicitly, keeping `client_benchmark` on for both A/B sides;
2. converts Windows/`cygpath` game and output paths to mixed Java paths;
3. supports `--quickPlaySingleplayer` via `WORLD` / `QUICKPLAY=1`;
4. passes identical width/height, camera, scene, and recorder flags to both sides;
5. waits until a world is loaded, discards warmup frames, then records sample frames;
6. writes schema-2 JSON (metrics, environment, resolved module reasons, raw frame times);
7. closes the client **only after** that JSON is written (`EXIT_AFTER_WRITE=1` by default).

`scripts/bench-client-ab.sh` runs the primary protocol: **disabled versus default**, alternating
OFF/ON and ON/OFF, defaulting to 6 balanced pairs. `mode=enabled` is refused unless
`ALLOW_EXPERIMENTAL_ON=1` and must not be reported as the release result.

`scripts/summarize-client-bench.py` prints per-pair deltas, sample SD, and a 95% t-interval. It
flags 0.1% low outliers instead of burying them in the mean and will not call a result a gain when
n<6 or the interval includes zero.

JSON now records:

- Minecraft/Fabric/Java/LWJGL/GPU/driver;
- framebuffer, window, and graphics options;
- loaded mods and resource packs;
- requested world id, level name, dimension;
- requested and actual camera pose/path;
- resolved state, request flag, and disable reason for every Ultima module;
- screenshot paths when `CAPTURE_SCREENSHOTS=1`.

Camera modes:

- `stationary` — default; optional held `CAMERA_X/Y/Z` and look angles;
- `yaw_sweep` — deterministic yaw increment each world-ready frame at a held position.

Required A/B controls remain: copied identical world, identical camera path, resolution,
render/simulation distance, frame cap/vsync, graphics, shader/resource packs, and entity/particle
population.

## Real RTX 3090 A/B (2026-08-14)

This is a real hardware run, not VM or software rendering. It is valid only for the scene below.

### Target install

- gameDir: `C:\Users\tamer\AppData\Roaming\ModrinthApp\profiles\Fabric 26.2`
- Minecraft 26.2, Fabric Loader 0.19.3, OpenJDK/Zulu 25.0.4+7
- GPU: NVIDIA GeForce RTX 3090, driver 610.88, LWJGL 3.4.1-snapshot
- Modrinth memory cap: 15872 MB; no extra JVM/launch args
- Profile mods folder empty (no Sodium, Iris, Lithium, shaderpacks, or resourcepacks)
- Original `latest.log` had no Ultima/Mixin WARN/ERROR; crash-reports directory empty
- Recurring offline-auth `Failed to retrieve profile key pair, HTTP 401` is unrelated to Ultima

### Tested artifact

Dev client with Fabric API `0.156.0+26.2` and Ultima 0.1.0.

- OFF: 1/8 modules — `client_benchmark` only
- ON/default: 5/8 — `cursor_step`, `client_chunk_matrix_reuse`,
  `client_chunk_layer_array_reuse`, `client_chunk_dirty_dedup`, `client_benchmark`
- Opt-in modules remained off: `entity_section_lookup`, `block_collision_shape`,
  `collision_shell_skip`

Six launches, zero crashes, zero Mixin/`ultima.client.mixins.json` errors, zero Ultima conflicts.

### Method

One source world, fresh copy per launch; standing camera at 70.5853, 69.1213, 242.1139;
1280×720; render distance 16; simulation distance 12; Fancy; VSync off; 1200 warmup + 12000
measured frames; order OFF/ON, ON/OFF, OFF/ON. The scene is stationary and mostly CPU-bound.

### Pair results

| Pair | Avg FPS OFF→ON | Median | 1% low | 0.1% low |
|---|---:|---:|---:|---:|
| 1 | 566.15 → 552.20 (−2.46%) | 598.01 → 601.94 (+0.66%) | 256.48 → 254.05 (−0.95%) | 137.50 → 113.26 (−17.63%) |
| 2 | 554.84 → 582.14 (+4.92%) | 593.86 → 631.67 (+6.37%) | 270.99 → 271.10 (+0.04%) | 129.65 → 127.87 (−1.38%) |
| 3 | 592.26 → 574.09 (−3.07%) | 638.28 → 619.85 (−2.89%) | 275.46 → 283.29 (+2.84%) | 143.97 → 143.93 (−0.03%) |

Pair 1's 0.1% low drop is a real observed outlier. It is not discarded; averaging it with the other
pairs would hide a tail regression that did occur.

### Means (n=3, below the 6-pair protocol)

| Metric | OFF | ON | Mean-of-means | Mean paired Δ | Paired SD |
|---|---:|---:|---:|---:|---:|
| Average FPS | 571.08 | 569.48 | −0.28% | −0.20% | 4.45 pp |
| Median FPS | 610.05 | 617.82 | +1.27% | | |
| 1% low | 267.65 | 269.48 | +0.68% | | 1.96 pp |
| 0.1% low | 137.04 | 128.35 | −6.34% | | 9.80 pp |
| Avg frame time | 1.7524 ms | 1.7569 ms | +0.26% worse | | |
| P95 | 2.5671 ms | 2.6108 ms | +1.70% worse | | |
| P99 | 2.9428 ms | 2.9445 ms | +0.06% worse | | |

Average FPS 95% t-interval on three paired deltas is tens of percentage points wide and includes
zero. Sign flips between pairs. Effect < run-to-run scatter.

### ON counters (12000 frames)

| Counter | Pair 1 | Pair 2 | Pair 3 | Mean | Per frame |
|---|---:|---:|---:|---:|---:|
| Matrix copies avoided | 7,356,000 | 7,368,000 | 7,332,000 | 7,352,000 | ~612.7 |
| Layer arrays avoided | 7,368,000 | 7,380,000 | 7,344,000 | 7,364,000 | ~613.7 |
| Dirty writes avoided | 29,844 | 29,024 | 30,005 | 29,624 | ~2.47 |

The optimizations fire. In this scene they do not produce a stable FPS gain.

### Visual check

Ordinary vanilla terrain screenshots did not show missing geometry, bad lighting, shadow errors,
foliage/transparency breakage, or an obvious draw-order bug. Dynamic differences were animals,
particles, and the offline skin — not Ultima terrain submission.

Visual equivalence is still **FAIL**: there is no controlled water/transparency pair, no block-entity
pair, no matched particle population, and no shader-on capture (Iris and a shader pack were absent).

### Sodium / Iris matrix (Minecraft 26.2)

| Gate | Static | Runtime 26.2 |
|---|---|---|
| `sodium` incompatibleMods | yes | not tested; mods folder empty |
| `iris` incompatibleMods | yes | not tested; mods folder empty |
| Shader OFF/ON visual | n/a | not tested |

## Verification

- Vanilla 26.2 source was inspected for `LevelExtractor`, `LevelRenderer`,
  `DynamicUniformStorage`, chunk compiler/dispatcher/cache, entity and block-entity dispatchers,
  particle groups, frustum behavior, and chunk submission.
- Main and client source sets compile.
- `forensicRegressionTest` covers module defaults, dependency behavior, disable reasons, and
  renderer compatibility metadata in addition to existing arithmetic/order checks.
- A dedicated-server smoke run reported 1 of 4 common modules enabled and completed two sprints,
  confirming client resources/Mixins are excluded from server initialization.
- A graphical dev client with the final client Mixin set reported 5 of 8 applicable modules enabled,
  loaded the `ultima-bench` world, and rendered normally for more than 30 seconds with no Mixin
  failure or crash.
- Real-PC A/B: six launches, JSON written for three OFF/ON pairs, no Ultima crash or Mixin error.

VM/software-rendered FPS numbers from earlier smokes are functional checks only and must not be
compared with the RTX 3090 A/B.

## Remaining measurement

The next credible FPS dataset needs:

1. at least 6 balanced disabled-vs-default pairs and a 95% interval;
2. a deterministic moving-camera/`yaw_sweep` route (harness support is in);
3. an update-heavy/redstone scene for dirty dedup;
4. high render distance and 1440p for matrix/layer reuse;
5. controlled entity, block-entity, particle, and water/transparency scenes;
6. Sodium/Iris runtime confirmation on 26.2;
7. shader OFF/ON visual captures (`CAPTURE_SCREENSHOTS=1` once Iris is present);
8. investigation of the pair-1 0.1% low outlier rather than dropping it.

Production module defaults and Sodium/Iris gates are unchanged. No production optimization semantics
were altered on the basis of this A/B.

## Final status

CLIENT OPTIMIZATIONS IMPLEMENTED: 3

DEFAULT-ENABLED: [`client_chunk_matrix_reuse`, `client_chunk_layer_array_reuse`,
`client_chunk_dirty_dedup` on vanilla renderer; automatically disabled with Sodium/Iris]

OPT-IN: [`client_benchmark` instrumentation only]

CLIENT BUILD: PASS

ULTIMA CLIENT COMPATIBILITY: PASS

VISUAL EQUIVALENCE: FAIL

REAL GPU A/B VALID: YES (stationary CPU-bound Fancy RD16 scene on RTX 3090)

MEASURED FPS GAIN: −0.28% (INCONCLUSIVE; NO RELIABLE GAIN)

MEASURED 1% LOW GAIN: +0.68% (INCONCLUSIVE)

READY FOR RELEASE: NO
