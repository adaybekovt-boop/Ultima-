# REAL_PERFORMANCE_REPORT.md

Date: 2026-08-15  
Branch: `cursor/forensic-review-9efc`  
Minecraft 26.2, Fabric Loader 0.19.3, Fabric API `0.156.0+26.2`, Java 25, Gradle 9.5.1, Ultima 0.1.0

This pass exists to produce a **measurable** improvement on real work, not a prettier allocation counter. Previous vanilla-terrain client micro-opts were deleted when an RTX 3090 A/B showed no useful FPS gain. The work that remains was chosen from Java Flight Recorder on a running 26.2 server, then kept or rejected with paired A/B.

## 1. Hardware / software environment

### This measurement host (dedicated-server A/B and JFR)

| Item | Value |
|---|---|
| CPU | 4× Intel Xeon (KVM guest), 1 thread/core |
| RAM | 15 GiB, `-Xmx6G` on Loom runs |
| GPU | none (no NVIDIA device) |
| OS | Linux 6.12.94+ x86_64 |
| JDK | Temurin 25.0.4+7 |
| Display | `:1` TigerVNC + xfce; Mesa llvmpipe present |
| Mods in the measured server | Fabric API + Ultima only (no Sodium, Iris, Lithium) |

Software-GL `runClient` on this host started, created atlases, then sat at ~150% CPU without writing a benchmark JSON. **No FPS number from this VM is a release metric.**

### Prior real-PC GPU A/B (historical, different module set)

Recorded 2026-08-14 on NVIDIA GeForce RTX 3090, driver 610.88, 1280×720 Fancy, render distance 16, simulation distance 12, VSync off, stationary camera, 1200 warmup + 12000 frames, n=3 pairs. That run compared **disabled vs the three client chunk modules that this pass deleted**. Mean average FPS **571.08 → 569.48 (−0.28%)**, 1% low **+0.68%**, both inconclusive. It is **not** a measurement of the current defaults.

## 2. Benchmark scenarios

| ID | Scenario | Used for | Result |
|---|---|---|---|
| S1 | Dedicated-server entity farm: superflat seed `ultima`, view/sim 10, 1156 force-loaded chunks, 1100 entities (700 mobs of 8 kinds + 400 items) spread over ±260 blocks, 250 warmup + 800 measured ticks, `/tick sprint` | JFR + A/B | Primary measured workload |
| S2 | Same as S1, `full_cube_move` isolated on top of the other defaults | Keep/reject | −2.36% mean tick time, n=6, CI excludes 0 |
| S3 | Combined disabled vs current defaults | Final claim | −21.78% mean tick time, n=6 |
| S4 | CPU-bound stationary Fancy RD16 1280×720 RTX 3090 | Historical client FPS | Failed for the deleted chunk modules |
| S5 | llvmpipe 640×360 quickPlay `ultima-bench` | Attempted client smoke | Hung after atlas creation; discarded |
| S6 | `chunk_flight` camera (0.8 blocks/frame + yaw sweep) | Heavier client CPU path for a future GPU run | Harness only; not executed on a GPU this pass |

Identical conditions for every A/B pair: world recreated from the same generator, same load function (starts with `kill @e[type=!minecraft:player]`), same force-loaded region, same JVM heap, alternating OFF→ON / ON→OFF. Every measured run killed **88 cows**.

## 3. Profiler results

Tooling: JFR `settings=profile`, `stackdepth=128`, dumped on exit. Recordings are local under `run/` and are not committed.

### Baseline (all Ultima modules off) — `ultima-server-jfr_off.jfr`

Entity farm, invalid first attempt aside, the fair off-side profile:

| Method | Samples | % |
|---|---:|---:|
| `BlockCollisions.computeNext` | 634 | **15.26%** |
| `ServerChunkCache.getChunk` | 333 | 8.01% |
| `EntitySection.getEntities` | 150 | 3.61% |
| `Long2ObjectOpenHashMap.get` | 142 | 3.42% |
| `LongAVLTreeSet$Entry.next` | 114 | 2.74% |

Allocation pressure: `AABB` 9.37%, `int[]` 7.59%, `OffsetDoubleList` 6.72%, `Vec3` 6.49%, `CubePointRange` 4.59%, `ArrayVoxelShape` 4.06%, `BlockCollisions` 2.56%.

### After the collision stack, before `full_cube_move` — `ultima-server-jfr_on.jfr`

`computeNext` **15.26% → 2.66%**. Remaining leaves were random-tick fluid checks, `getChunk`, `getEntities`, `CompletableFuture.reportJoin`. `OffsetDoubleList` was still **5.38%** of allocation.

### After current defaults including `full_cube_move` — `ultima-server-final_on.jfr`

| Method | % |
|---|---:|
| `ServerChunkCache.getChunk` | 3.31% |
| `LevelChunkSection.isRandomlyTickingFluids` | 3.31% |
| `Level.lambda$getEntities$0` | 2.51% |
| `BlockCollisions.computeNext` | **2.21%** |
| `CompletableFuture.reportJoin` | 2.15% |

`OffsetDoubleList`, `CubePointRange`, and `ArrayVoxelShape` are gone from the top allocation list. Their replacements appear as `OffsetCubeVoxelShape` 1.58% + `OffsetCubeCoords` 1.37%.

## 4. Top hotspots before optimization

| Rank | Cost center | % CPU (off JFR) | Scales with | Sodium/Iris replace? | Parent |
|---|---|---:|---|---|---|
| 1 | `BlockCollisions.computeNext` | 15.3% | entities × movement × queried blocks | No | `Entity.move` / `findSupportingBlock` |
| 2 | `ServerChunkCache.getChunk` | 8.0% | collision + AI + spawn | No | collision, pathfinding, `NaturalSpawner` |
| 3 | Entity section AVL scan | ~10% cluster | loaded entity sections, not query size | Lithium, not Sodium | `Level.getEntities` |
| 4 | `VoxelShape.move` allocations | 6.7% alloc `OffsetDoubleList` | colliding full cubes | Lithium | `computeNext` |
| 5 | Eager `Shapes.create(entity AABB)` | constructor of every query | collision queries | Lithium | `BlockCollisions.<init>` |
| 6 | Shell positions (~87% of cursor visits) | included in #1 | query volume, not interior | Lithium | `Cursor3D` + `computeNext` |
| 7 | Vanilla `prepareChunkRenders` matrix/`values()` copies | RTX 3090 counters ~613/frame | visible sections | **Yes (Sodium)** | client extract |

#7 was implemented, measured on an RTX 3090, and **deleted** because FPS did not move.

## 5. Optimizations attempted

1. Delete the three vanilla-only client chunk modules (failed GPU A/B).
2. Default-on the already-written collision/entity-index stack (`cursor_step`, `entity_section_lookup`, `block_collision_shape`, `collision_shell_skip`).
3. `supporting_block_shape_skip`: skip `VoxelShape.move` in `findSupportingBlock` for `Shapes.block()`.
4. `full_cube_move`: replace integer `Shapes.block().move` with `OffsetCubeVoxelShape` (same coords, `findIndex`, and `collide()` as vanilla; forensic differential test).
5. Heavier client camera (`chunk_flight`) for a future GPU run.
6. llvmpipe client FPS attempt — aborted.

## 6. Rejected optimizations and why

| Candidate | Why rejected |
|---|---|
| `client_chunk_matrix_reuse` | RTX 3090 n=3: −0.28% avg FPS, CI includes 0. Adds Mixin surface. **Removed.** |
| `client_chunk_layer_array_reuse` | Same A/B. Avoided ~614 tiny arrays/frame; no FPS. **Removed.** |
| `client_chunk_dirty_dedup` | Same A/B; stationary scene only ~2.5 duplicate writes/frame. **Removed.** |
| Pooling entity render states | High invalidation risk across many subclasses; no GPU to A/B |
| Narrowing chunk dirty fan-out beyond duplicates | Would skip rebuilds; observable geometry risk |
| Shared per-tick block/shape cache across the 3 collision traversals | Palette read is cheaper than a correct cache |
| Multi-entry `BlockCollisions` chunk cache | Earlier JFR: almost no `getChunk` samples from that cache miss |
| Caching `Entity.getOnPos` across `travel`/`move` | Position changes between the two calls |
| Rewriting `Profiler.get()` ThreadLocal | Touches Tracy / vanilla profiler for <2% |
| Fast-path `isRandomlyTickingFluids` | Would be a gameplay/tick-semantics change if it skipped real ticks; the remaining cost is the vanilla empty check itself |
| Approximate frustum / skipping entity extract | Forbidden: changes what is drawn |
| Software-GL FPS numbers | Not real hardware |

`supporting_block_shape_skip` vs the stack without it was **within noise** on a 3-pair run (ON ~6.67 vs ~6.79 ms). It is kept as an allocation skip on a path that discards the shape, not as a proven extra 5% tick win.

## 7. Accepted optimizations

All six are simulation/collision. They run on dedicated servers, the integrated server (singleplayer hitch), and client-side entity/particle physics. They do **not** lower render distance, resolution, particles, lighting, mipmaps, animation rate, or shader quality. Lithium/Canary/Radium auto-disable the overlapping ones.

| Module | What work is deleted | Equivalence |
|---|---|---|
| `cursor_step` | Integer divides in `Cursor3D.advance` | Same x/y/z/index/type sequence (200k volumes, 0 mismatches) |
| `collision_shell_skip` | ~87% of collision cursor positions when palettes say no large-shape/piston block exists | Interior subsequence of vanilla, same order; fail-open on debug worlds / global palettes |
| `entity_section_lookup` | AVL strip scan of every populated section in a chunk column | Same set and visit order (10k random populations) |
| `block_collision_shape` | Eager `Shapes.create` of the entity box | Built on first non-cube read via the same MixinExtras operation |
| `supporting_block_shape_skip` | `VoxelShape.move` for full cubes in `findSupportingBlock` | That caller keeps only `BlockPos`; full-cube hits already used `AABB.intersects` |
| `full_cube_move` | Allocating `ArrayVoxelShape`+offset lists for integer `Shapes.block().move` | Same coord lists, `findIndex`, and `collide()` as vanilla `move` |

No production client-renderer Mixin remains except opt-in `client_benchmark`.

## 8. Per-patch A/B results

Tick times are the **second** `/tick sprint` line (measured 800 ticks). Lower is better.

### 8a. Collision stack without `full_cube_move` (3 pairs)

| Pair | OFF | ON | Δ |
|---|---:|---:|---:|
| 1 | 8.10 | 6.73 | −16.9% |
| 2 | 8.20 | 6.64 | −19.0% |
| 3 | 8.36 | 6.65 | −20.5% |

Mean 8.22 → 6.67 ms/tick (**−18.8%**). All 88 cows.

### 8b. Isolation of `full_cube_move` (6 pairs, other defaults on)

OFF = defaults with `full_cube_move=false`. ON = defaults with it true. Order OFF/ON, ON/OFF, …

| Pair | OFF | ON | Δ |
|---|---:|---:|---:|
| cube 1 | 6.67 | 6.59 | −1.20% |
| cube 2 | 6.72 | 6.34 | −5.65% |
| cube 3 | 6.47 | 6.39 | −1.24% |
| cubeB 1 | 6.67 | 6.62 | −0.75% |
| cubeB 2 | 6.57 | 6.37 | −3.04% |
| cubeB 3 | 6.60 | 6.45 | −2.27% |

Mean 6.617 → 6.460 ms/tick. Mean paired Δ **−2.36%**, SD 1.82 pp, 95% t-interval **[−4.27%, −0.45%]**. All six pairs same sign. Meets the 2–5% “keep if repeatable” rule. Not a 5% patch by itself.

### 8c. Historical RTX 3090 client (deleted modules)

Average FPS −0.28%, 1% low +0.68%, n=3, CI includes 0. **Rejected.**

## 9. Final combined A/B

Disabled (0 of 6) vs current defaults (6 of 6). Balanced 6 pairs, 1100-entity farm, 88 cows every launch.

| Pair | Order | OFF ms/tick | ON ms/tick | Δ |
|---|---|---:|---:|---:|
| 1 | OFF→ON | 8.10 | 6.35 | −21.60% |
| 2 | ON→OFF | 8.53 | 6.81 | −20.16% |
| 3 | OFF→ON | 8.27 | 6.39 | −22.73% |
| 4 | ON→OFF | 8.42 | 6.33 | −24.82% |
| 5 | OFF→ON | 8.39 | 6.54 | −22.05% |
| 6 | ON→OFF | 8.29 | 6.69 | −19.30% |

| Metric | OFF | ON | Δ |
|---|---:|---:|---:|
| Mean tick time | **8.333 ms** | **6.518 ms** | **−21.78%** paired |
| Paired SD | | | 1.95 pp |
| 95% t-interval | | | **[−23.83%, −19.73%]** |
| Uncapped sprint throughput | 120 tick/s | 153 tick/s | +28% (not in-game TPS) |
| Worst ON vs best OFF | 6.81 | 8.10 | ON still faster |
| Cow kills | 88 | 88 | match |

Post-sprint `/tick query` P99 (100 samples) was 16.03 → 15.85 ms (−1.1%) and is **not** a stutter claim: the sample is too small and signs flip. The 800-tick sprint mean is the credible heavy-frame metric.

This is the integrated-server / entity-farm hitch path. In singleplayer the same work runs on the integrated server and shows up as client hitch when the tick overruns. It is **not** a 720p RTX 3090 standing-still FPS claim.

## 10. Visual / gameplay equivalence checks

- Differential tests: cursor carry, interior cursor, section order, packed bounds, `OffsetCubeVoxelShape` vs vanilla `Shapes.block().move` coords/`findIndex`/`collide`. `bash scripts/check.sh` **BUILD SUCCESSFUL**.
- Every A/B run summoned the same load and killed 88 cows.
- No Mixin apply failure (`defaultRequire: 1` would abort). ON logs: `Ultima initialized with 6 of 6 optimization modules enabled`.
- Physics smoke from earlier collision work: cows stand on the superflat surface rather than sinking.
- **No controlled GPU screenshot pair this pass.** The RTX 3090 captures belonged to the deleted terrain modules. Shader ON/OFF visual is untested (Iris absent).

## 11. Compatibility status

| Gate | Status |
|---|---|
| Lithium / Canary / Radium | Declared `incompatibleMods`; modules auto-disable. Not runtime-tested against those jars on 26.2. |
| Sodium / Iris | No production client-renderer Mixins left to conflict. Collision modules are independent of Sodium’s terrain path. Not runtime-tested on 26.2. |
| Shaders | No render-stage / frame-graph / material change. |
| Dedicated server | Client Mixins are in `ultima.client.mixins.json` with `"environment": "client"`. |
| World format / protocol / RNG / game rules | Untouched. |

## 12. Remaining bottlenecks

After the accepted stack, JFR of this farm is no longer collision-dominated. Next leaves are each ~2–3%:

1. `ServerChunkCache.getChunk` (AI, spawn, remaining collision) — Lithium territory; a Ultima cache is easy to get wrong.
2. `LevelChunkSection.isRandomlyTickingFluids` — vanilla empty check; skipping it would change random ticks.
3. `Level.getEntities` collector lambda / predicate composition — possible allocation win; not yet A/B’d.
4. `CompletableFuture.reportJoin` — chunk generation waits; this farm force-loads, so part of the harness.
5. Mob AI (`serverAiStep`, look control, goals) — gameplay.
6. **Vanilla client extract/prepare** (`LevelExtractor` entity render states, `SectionCompiler`, visibility). Sodium replaces terrain. Entity extract is the honest remaining FPS target **on a GPU**. This environment cannot A/B it.

Hard limitation for GPU average FPS on this pass: no discrete GPU, llvmpipe did not complete a sample, and the only RTX 3090 dataset is for modules that were deleted because they failed.

---

## 13. Architecture pass (retained opaque terrain)

Date: 2026-08-15. Still no discrete GPU. `bash scripts/check.sh` **BUILD SUCCESSFUL**, including `forensicRegressionTest` (packed 16³ visit order vs `BlockPos.betweenClosed`, visibility bit round-trip, new module defaults).

This is **not** an FPS keep/reject. The opaque prototype is default **off**.

### What landed

| Module | Default | Role |
|---|---|---|
| `terrain_metrics` | client on | Prepare/command/submit ns, draws, sections, rebuilds/uploads; `terrainMetrics` in benchmark JSON |
| `retained_terrain` | off | Opaque SOLID/CUTOUT retained records + section table UBO + multi-draw/indirect; translucent vanilla; fail open |
| `render_snapshot` | off | Intern BE maps per live map identity inside one `RenderRegionCache` |
| `java_mesher` | off | Packed x-fastest compile loop; ThreadLocal tessellators |
| `section_task_queue` | off | Compact cancelled tasks; `parkNanos(50µs)` vs `onSpinWait` |
| `rgss_endpoint` | off | Separate shader-source endpoint specialization; reject if GPU <3% |

Not restored: `client_chunk_matrix_reuse`, `client_chunk_layer_array_reuse`, `client_chunk_dirty_dedup`.

### Experiment verdicts

| ID | Baseline | Patch | Correctness | CPU | GPU | FPS | 1% low | p95/p99 | Allocations | Draw/command | Compatibility | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E1 metrics | uninstrumented | mixins + JSON | no visual change | nanoTime only | 0 on this host | n/a | n/a | n/a | counters | counted | auto-off Sodium/Iris/Canvas | **KEEP** |
| E2 retained opaque | vanilla prepare | opt-in producer | fail-open; compile green | not GPU-measured | not GPU-measured | n/a | n/a | n/a | no opaque Draw/Matrix4f | header + batch table | ultima shaders; vanilla core/terrain untouched | **PENDING GPU A/B** |
| E3 snapshots | per-section BE copy | intern | palettes not shared | n/a | n/a | n/a | n/a | n/a | fewer BE maps | n/a | auto-off replacement renderers | **PENDING** |
| E4 java mesher | betweenClosed | packed loop | visit-order test pass | n/a | n/a | n/a | n/a | n/a | no AbstractIterator | same tessellators | auto-off replacement renderers | **PENDING** |
| E5 task queue | iterator.remove + spin | compact + park | same nearest/quota | n/a | n/a | n/a | n/a | n/a | fewer shifts | n/a | auto-off replacement renderers | **PENDING** |
| E6 RGSS | always nearest+RGSS | endpoint early-out | exact at 0 and 1 | n/a | reject <3% | n/a | n/a | n/a | n/a | n/a | auto-off replacement renderers | **PENDING** |
| E7 temporal Native | none | `temporal` default on | no pixel change; Native size=output | capture only | n/a | n/a | n/a | n/a | none | n/a | auto-off Sodium/Iris/Canvas | **KEEP** (architecture) |
| E8 retained command reuse | refill every walk | fingerprint skip | same draws if set/mesh stable | n/a | n/a | n/a | n/a | n/a | fewer array writes | `commandBatchesReused` | same fail-open | **SUPERSEDED** by E9 |
| E9 retained foundation | map/fence/DrawIDARB path that lost 35–47% FPS | persistent table + dirty writes + BaseInstance | compile/portability tests only | n/a | n/a | n/a | n/a | n/a | no map/unmap | persistent indirect | GL/VK shader variants | **REWORK** (no GPU A/B on this host) |

### Next measurement (GPU host)

1. `terrain_metrics=true` on both A/B sides.
2. TERRAIN-SUBMISSION: Fancy, high RD, stationary, `retained_terrain=false` vs `true`.
3. Keep only if ≥5% avg FPS **or** ≥10% prepare/submit CPU.
4. If per-draw submission is not the bottleneck, do not expand the prototype; re-profile.

---

## 14. Temporal Native passthrough (architecture; no FPS claim)

Date: 2026-08-15. Still no discrete GPU.

Implemented the required temporal-ready layer **before** any DLSS/FSR work:

- Module `temporal` (client, default on, auto-off Sodium/Iris/Canvas)
- `TemporalFrameData` + `TemporalBackend` + `NativePassthroughBackend` (`evaluate()` is a no-op)
- Reset on world load/unload, dimension change, resize, resource reload, renderer close, camera cut (>32 blocks), FOV jump (>5°)
- Forensic tests: static camera → zero NDC velocity; Native recommended size equals output; unsupported DLSS/FSR modes do not lower resolution
- Retained opaque batches skip CPU refill when the visible command fingerprint is unchanged (`commandBatchesReused`)
- `RetainedSectionRecord.temporalFlags` marks static terrain (previous world == current world)

Not implemented: DLSS/FSR backends, jitter, MV textures, Frame Generation, graphics-menu options.

See `ULTIMA_TEMPORAL_ARCHITECTURE.md`.

---

## 15. Retained foundation rework (architecture; no new GPU A/B)

Date: 2026-08-16. Still no discrete GPU.

The prior RTX 3090 retained path is **not** this code. That path mapped a 256-record UBO, fence-waited a ring buffer, rewrote indirect commands every frame, and used `gl_DrawIDARB` (Vulkan compile failed). Stationary AVG FPS 328.86 → 213.60 (−34.50%); yaw n=6 381.33 → 202.77 (−46.70%).

This rework replaces that with a persistent texel section table, persistent indirect commands, `writeToBuffer` dirty ranges, and `gl_BaseInstance` / `gl_BaseInstanceARB`. See `ULTIMA_FOUNDATION_REWORK_REPORT.md`.

Verdict remains **REWORK** until a GPU host repeats the OpenGL and Vulkan A/Bs. No FPS number from this VM.

---

REAL PERFORMANCE PASS COMPLETE

ACCEPTED CLIENT OPTIMIZATIONS: 0 shipped (retained terrain is opt-in / unmeasured)

ACCEPTED SIMULATION OPTIMIZATIONS: 6

REMOVED USELESS OPTIMIZATIONS: 3

AVERAGE FPS:
OFF n/a (no GPU dataset for current defaults or retained_terrain; prior RTX 3090 of deleted modules: 571.08)
ON n/a (prior RTX 3090 of deleted modules: 569.48)
DELTA n/a (prior: −0.28%, inconclusive)

1% LOW:
OFF n/a (prior RTX 3090 of deleted modules: 267.64)
ON n/a (prior: 269.48)
DELTA n/a (prior: +0.69%, inconclusive)

P99 FRAME TIME:
OFF n/a
ON n/a
DELTA n/a

TERRAIN DRAW/SUBMISSION CPU:
OFF n/a (harness ready)
ON n/a
DELTA n/a

CHUNK-STREAM P99: n/a

ENTITY-FARM MEAN TICK TIME (heavy-frame / integrated-server stutter, n=6):
OFF 8.333 ms
ON 6.518 ms
DELTA −21.78% (95% CI [−23.83%, −19.73%])

VISUAL PARITY: FAIL OPEN TO VANILLA (no screenshot pair this host)

STABILITY: `scripts/check.sh` BUILD SUCCESSFUL

>=25% REAL AVG FPS: FAIL

TARGET >=5% AVG FPS OR >=10% TERRAIN PREP CPU (prototype keep): NOT MEASURED

