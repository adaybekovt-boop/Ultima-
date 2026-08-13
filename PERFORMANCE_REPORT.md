# Ultima performance report

Target: Minecraft Java Edition 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, Java 25.

## Summary

Three optimizations are implemented, each behind its own switch in `config/ultima.properties`. All
three sit in code that both the dedicated server and the client execute, so they apply to
multiplayer servers, to the integrated server in single-player, and to client-side entity and
particle physics.

Measured end to end on a dedicated 26.2 server under a fixed 1100-entity load spread over 1089
force-loaded chunks, mean tick time dropped from **10.32 ms to 9.80 ms (-5.0%)** and sustained tick
rate rose from **96.3 to 101.7 TPS**, across three runs per side whose ranges do not overlap. The
individual algorithmic improvements are far larger than 5% in isolation; how much of that reaches
the tick depends on how much of the world is populated, which is discussed under each entry.

Target selection was driven by a Java Flight Recorder profile of the running server rather than by
inspection alone. That profile is what disqualified two changes I had already designed, and what
identified the largest one. Every optimization that reorders work was checked against a faithful
port of the vanilla algorithm in a differential harness before being kept.

Four candidates were rejected outright. The client renderer was deliberately left untouched; the
reasoning is in *Rejected optimizations*.

## Environment fixes required first

`scripts/export-vanilla-sources.sh` could not succeed on any input. It tested candidate JARs with
`unzip -Z1 … | grep -q …`; `grep -q` exits as soon as it matches, `unzip` then dies with `SIGPIPE`,
and because the script runs under `set -o pipefail` the pipeline's non-zero status made every
candidate look like a failure. It also stopped at the first matching JAR and required it to exceed
5 MB, while Loom's split environment source sets produce two JARs (common, 6.0 MB, and clientOnly,
2.5 MB) that are both needed. Detection now consumes the whole listing with `grep -c`, and every
JAR containing `net/minecraft` sources is extracted. This yields 7055 reference source files.

`scripts/check.sh`, referenced by `AGENTS.md`, did not exist and was added.

The VM also lacked JDK 25 and Gradle, which were installed to build at all.

## Architecture

`UltimaModules` is the registry of optimization modules. A module's key is also the package segment
its Mixins live in, so `dev.ultima.mixin.cursor_step.Cursor3DMixin` belongs to `cursor_step`.
`UltimaMixinPlugin` implements `IMixinConfigPlugin` and refuses to apply the Mixins of a disabled
module, so a single incompatible optimization can be switched off without disabling the mod.
`UltimaConfig` reads the properties file once, before any Mixin is applied, and never touches a
Minecraft class; unknown or missing keys are treated as enabled, and any failure to read or write
the file degrades to defaults with a warning rather than failing.

## Implemented optimizations

### 1. `entity_section_lookup` — direct entity section lookup

- **Subsystem:** entity indexing (server and client).
- **Vanilla hotspot:** `EntitySectionStorage.forEachAccessibleNonEmptySection(AABB, AbortableIterationConsumer)`.
- **Change:** `SectionPos.asLong` packs x into bits 42-63, z into 20-41 and y into 0-19, so the
  `sectionIds` tree range vanilla queries for one x coordinate — `asLong(x, 0, 0)` through
  `asLong(x, -1, -1)` — spans *every* z and *every* y of that coordinate. Vanilla therefore walks
  every non-empty entity section in an entire chunk strip and discards the ones outside the box with
  an `if`. The cost of asking about a single entity's bounding box scales with how much of the world
  is populated instead of with the query. Ultima computes the intersecting section keys up front and
  probes them directly in the `sections` hash map.
- **Why faster:** replaces O(populated sections in the strip) pointer-chasing AVL successor walks
  with O(intersecting sections) hash probes. A typical collision or AI query intersects a handful of
  sections.
- **Why behaviour is equivalent:** `sections` and `sectionIds` are mutated together
  (`createSection` adds to both, `remove` removes from both), so the visited set is identical. The
  visit order is reproduced exactly: sections are emitted ascending by x, then by masked z, then by
  masked y, which requires visiting non-negative coordinates before negative ones because masking
  maps a negative coordinate above every non-negative one. Order matters because callers collect
  into a `List` and some resolve ties by first-encountered — for example a mob picking a target at
  equal distance.
- **Fallback:** if the candidate volume exceeds 1024 sections *and* exceeds the total number of
  existing sections, the vanilla path runs instead, so a pathological query can never make Ultima
  probe more sections than vanilla would visit. A degenerate or infinite bounding box produces a
  candidate count computed in `long` arithmetic and falls back for the same reason.
- **Mod compatibility risk:** low-medium. It is a cancelling `@Inject` at `HEAD`, which is a full
  replacement of a five-line loop; another mod injecting into the same method still runs. There is
  no narrower hook, since the loop *is* the cost. Disabling the module restores vanilla exactly.
- **Shader compatibility risk:** none. No render state is involved.
- **Verification:** a differential harness compared a faithful port of the vanilla tree scan against
  the Ultima order over 6000 randomised section populations and query windows, including ranges
  straddling zero on both y and z, with **0 mismatches** in visited set or order. A microbenchmark
  on the real fastutil containers measured **381 ns to 46 ns per query with 825 populated sections
  (8.2x)** and **2099 ns to 98 ns with 12169 populated sections (21.4x)**.
- **Where the gain lands:** the benefit grows with the number of populated entity sections in a
  chunk strip. It is largest for a client at a high view distance and for servers whose players are
  spread out, whose worlds have entities stacked across many y-sections (caves, mob farms, the
  Nether), or which keep many chunks loaded. It is small when entities are concentrated in a few
  chunks, which is why the end-to-end figure below is dominated by the next entry.

### 2. `cursor_step` — block iteration without integer division

- **Subsystem:** block collision iteration (server and client).
- **Vanilla hotspot:** `Cursor3D.advance()`, reached from `BlockCollisions.computeNext()`.
- **Change:** vanilla re-derives the cursor's x, y and z from a running index on every step with
  `index % width`, `index / width`, `slice % height` and `slice / height`. The divisors are instance
  fields, not constants, so they compile to hardware integer divides in the innermost loop of the
  most expensive server-side operation there is. Ultima carries an increment instead.
- **Why faster:** removes the divides. The JFR profile put `BlockCollisions.computeNext()` at
  **25.6% of server-thread self time and 28.4% inclusive** in an entity-heavy tick, and every block
  position it examines pays for one `advance()`.
- **Why behaviour is equivalent:** the cursor only ever advances one position at a time from index
  zero, so an increment reproduces the same modular sequence. Nothing outside the class reads
  `index`; `x`, `y` and `z` remain in sync for `nextX/nextY/nextZ` and `getNextType`, and `index` is
  still advanced so any other injector observes the same value.
- **Mod compatibility risk:** low. `Cursor3D` is a small utility used only by `BlockCollisions` and
  `ClientLevel`; a cancelling `@Inject` at `HEAD` on an eight-line method is the narrowest available
  hook.
- **Shader compatibility risk:** none.
- **Verification:** a differential harness ran both implementations side by side over 200000
  randomly shaped volumes and **8586988 cursor positions**, comparing coordinates and face type at
  every step, with **0 mismatches**. Cost measured at **3.74 ns to 1.60 ns per block position, a 57%
  reduction**, on the query shape a moving entity produces.

### 3. `block_collision_shape` — deferred collider voxelisation

- **Subsystem:** block collision queries (server and client).
- **Vanilla hotspot:** `BlockCollisions` constructor, `Shapes.create(box)`.
- **Change:** every block collision query voxelises the collider's bounding box in the constructor.
  That shape is read in exactly one place, to intersect against a block whose collision shape is
  neither empty nor a full cube. An entity moving through air and full blocks never reaches that
  branch, so the shape and its three backing coordinate lists are built and discarded. Ultima
  defers the voxelisation to the first read.
- **Why faster:** removes an `ArrayVoxelShape` plus three `DoubleArrayList` and their arrays per
  query on the common path. Entity bounding boxes are rarely eighth-aligned, so `Shapes.create`
  usually takes its allocating branch rather than returning the shared full-cube shape.
- **Why behaviour is equivalent:** the shape is produced by the same call on the same immutable box,
  so the intersection test observes the same value. It is computed at most once per query.
- **Mod compatibility risk:** low. Implemented with MixinExtras `@ModifyExpressionValue` (bundled in
  Fabric Loader 0.19.3), which is stackable — unlike `@Redirect`, several mods can wrap the same
  expression without an exclusivity conflict.
- **Shader compatibility risk:** none.
- **Verification:** build and runtime validation below. This change removes allocations without
  altering control flow, so it has no differential harness of its own; it is covered by the
  end-to-end A/B and the physics checks.

## Measurements

### Profiling that drove the work

JFR (`settings=profile`) on the dedicated server, 5517 server-thread execution samples during a
sprint with 1100 entities. Inclusive shares of the server tick:

| Path | Inclusive |
|---|---|
| `EntityTickList.forEach` | 94.3% |
| `LivingEntity.aiStep` | 74.3% |
| `LivingEntity.travel` -> `Entity.move` | 45.3% / 43.1% |
| `BlockCollisions.computeNext` | 28.4% |
| `Entity.collide` | 26.3% |
| `Entity.checkSupportingBlock` -> `findSupportingBlock` | 13.0% / 12.9% |
| `Level.getEntities(Entity, AABB, Predicate)` | 15.2% |
| `EntityGetter.getEntityCollisions` | 10.4% |

The recording also showed 130 garbage collections in 193 seconds, confirming that allocation
reduction on these paths is worthwhile.

Leaf attribution was treated as unreliable: it credited 2.43% of self time to `JumpControl.tick()`,
which is two field writes, so inlining clearly smears samples across neighbouring frames. Only
inclusive shares and line-level attribution were used for decisions.

### End-to-end A/B on the dedicated server

`scripts/bench-server.sh <label> <enabled|disabled>` recreates the world from scratch each run:
superflat, fixed seed, view and simulation distance 10, 1089 force-loaded chunks, and a generated
1100-entity load (700 mobs of 8 kinds, 400 item entities) spread over ±260 blocks, then sprints 2500
ticks flat out. Both sides ran the same load; each run ends by killing all cows and reported exactly
88 every time, confirming the two sides really ticked the same population.

Sprint result, ms per tick over 2500 ticks:

| Run | Vanilla behaviour (modules disabled) | Ultima enabled |
|---|---|---|
| 1 | 10.40 | 10.09 |
| 2 | 10.31 | 9.94 |
| 3 | 10.26 | 9.37 |
| **mean** | **10.32** | **9.80** |
| sustained TPS | 96.3 | 101.7 |

**-5.0% mean tick time, +5.6% sustained tick rate.** The ranges do not overlap: the worst enabled
run (10.09) is still better than the best disabled run (10.26).

`/tick query` P50 over the 100 ticks following each sprint was 10.2 / 9.9 / 9.6 ms disabled against
9.5 / 9.4 / 9.0 ms enabled, consistently about 6% lower.

**P95 and P99 are not claimed as improvements.** Across these runs they were 11.7 / 14.3 / 12.2 ms
and 15.1 / 36.2 / 16.5 ms disabled against 11.8 / 12.6 / 11.8 ms and 14.7 / 15.6 / 30.7 ms enabled.
Both sides show occasional large outliers consistent with garbage collection, and 100 samples is far
too few to separate signal from noise at the 99th percentile. Frame-pacing and tick-pacing claims
need the longer runs listed under *Required real-PC tests*.

The 5% figure is specific to this load. It understates `entity_section_lookup`, whose isolated gain
is 8-21x but which has little to bite on here: the entities occupy roughly 33 chunk columns, so the
strip vanilla walks is already short. It is a reasonable estimate for `cursor_step`, which scales
with block collision volume and is present in every tick.

## Rejected optimizations

**Multi-entry chunk cache in `BlockCollisions`.** `Cursor3D` iterates x innermost while
`BlockCollisions` caches exactly one chunk, so a box straddling a chunk boundary in x should thrash
that cache on nearly every position. I designed a two-entry cache for it. The profile then showed
`ServerChunkCache.getChunk` reached from `BlockCollisions.getChunk` in only 28 of 5517 samples,
against 233 from plain `Level.getBlockState` and 90 from `MoveToBlockGoal.findNearestBlock`. The
one-entry cache is adequate in practice and the change was dropped as unjustified complexity.

**Avoiding the discarded voxel shape in `findSupportingBlock`.** This runs a full block collision
iteration per entity per tick (12.9% inclusive) with a result provider that ignores the shape
argument, yet `computeNext` still calls `blockShape.move(pos)` for every candidate, allocating an
`ArrayVoxelShape` and three `OffsetDoubleList` that are thrown away. There is no safe narrow fix:
the shape is passed through a `BiFunction` whose signature cannot be made lazy, and the same
expression feeds `collectCollidersIgnoringWorldBorder`, which retains every shape it is given — so
returning a shared or mutable-offset shape would corrupt collision resolution. Detecting the
specific lambda identity would be fragile. Left alone.

**Caching `Entity.getOnPos(0.500001F)`.** `travelInAir` computes the supporting position, then
`Entity.move` recomputes it through `getBlockSpeedFactor`, each time performing a block state read,
up to three tag membership tests and a `BlockPos` allocation. It looks like duplicate work, but
`move` changes the entity's position between the two calls, so the second result may legitimately
differ. Not cacheable without changing physics.

**Reducing `Profiler.get()` overhead.** `ThreadLocal$ThreadLocalMap.getEntry` accounted for 1.85% of
server-thread self time, because `Profiler.get()` performs a thread-local lookup on every
`getChunk` and `getEntities` call to increment a counter that is discarded when profiling is off.
Rewriting Minecraft's profiler plumbing to shave under 2% would put Tracy integration and the
built-in profiler at risk for a small gain. Rejected under the guardrails' priority order.

**The entire client renderer.** 26.2 replaced the renderer: scene extraction is now separated from
GPU submission (`LevelExtractor` building `LevelRenderState`, then `FrameGraphBuilder` with
`CommandEncoder`/`RenderPass` and uber `GpuBuffer` terrain), `MultiBufferSource` and `LightTexture`
no longer exist, and the lightmap is a GPU texture plus uniform buffer. The per-frame costs that
matter — `LevelRenderer.prepareChunkRenders` copying a `Matrix4f` per visible section and grouping
draws by hash, `ChunkSectionsToRender.renderGroup` reversing the translucent draw list,
`SectionOcclusionGraph` visibility, `SectionCompiler` mesh building — are precisely the surfaces
Sodium and Iris replace or observe, and translucency ordering and the frame-graph pass structure are
what shader packs composite against. There is no GPU in this environment, so no client change could
be validated at all. Under "better to lose 3 FPS than break shaders", nothing was touched. Note
that all three implemented optimizations still run on the client, in client-side entity and particle
collision and in the integrated server, and `entity_section_lookup` benefits most at high view
distance — the 21.4x microbenchmark point corresponds to a view distance of 32.

## Build status

Last command: `./gradlew --no-daemon clean build` — **BUILD SUCCESSFUL**.

`bash scripts/check.sh` runs the same build. No vanilla source is copied into the repository, and
`.agent/` is untracked (0 files matched in `git ls-files`).

## Runtime validation

Performed on a real dedicated 26.2 server with the mod loaded (`Ultima initialized with 3 of 3
optimization modules enabled`, `mixinextras 0.5.4` present):

- server startup, keypair generation, world creation and spawn preparation;
- data pack reload, 1089 chunks force-loaded across 9 commands, 1100 entities summoned;
- 2500-tick sprints, six times, three with all modules enabled and three with all disabled;
- `/tick query` percentile reporting;
- clean `/stop` with all three dimensions saved;
- **zero exceptions and zero Mixin failures** in any run. The only `ERROR` line, `No key layers in
  MapLike[{}]`, also appears in a pristine run of this workspace before any Mixin existed.

Because `ultima.mixins.json` sets `defaultRequire: 1`, a Mixin whose injection point failed to
resolve would abort class load. All three target classes are loaded during world and entity ticking,
so a clean sprint demonstrates that all three actually applied.

Physics correctness was checked directly, since `cursor_step` sits inside collision detection.
Entities were summoned at y = -59.0, one block above the superflat surface. After 1500 ticks the
nearest cow reported y = -60.0, the correct standing height on that surface, rather than sinking or
falling out of the world. Survivor counts matched the generated load exactly (88 cows, 88 zombies),
and every benchmark run independently reported 88 cows.

**No client, GPU or shader testing was performed, because this environment has no GPU.** No FPS,
frame-time or shader claim is made anywhere in this report.

## Required real-PC tests

Shader and renderer validation remains mandatory on real hardware even though no render code was
changed, because the collision and entity-section code paths run on the client:

- average FPS, 1% lows and a frame-time graph, with shaders disabled and with Iris plus a shader
  pack enabled, at view distance 32 where `entity_section_lookup` matters most;
- the same in an entity-heavy scene (large mob farm, item-heavy area) and while flying to force
  continuous chunk loading;
- RAM and GC behaviour over a long session, to confirm the deferred voxelisation and the section
  probing reduce rather than shift allocation pressure;
- a large modpack, specifically alongside Sodium, Iris and Lithium, to confirm no Mixin conflict —
  Lithium is the most likely to touch `EntitySectionStorage`;
- an existing long-played world as well as a new world, verifying no world corruption, correct
  redstone timing, and correct fluid behaviour;
- server-side: long-run tick percentiles with real players spread across the world, which is the
  configuration the section lookup is designed for and which this environment cannot reproduce.

## Next targets

Ranked by the evidence gathered, not by guesswork:

1. **`Level.getEntities` and the collision predicate path** — 15.2% inclusive, with 9.4% self time in
   the collector lambda. `getEntityCollisions` builds a fresh `NO_SPECTATORS.and(source::canCollideWith)`
   composite predicate per call, per entity, per tick; a per-entity cached predicate would remove two
   allocations per call and give the JIT a stable call site.
2. **`Entity.collide` running up to three block scans** — the step-up path rescans the volume and
   allocates a `FloatArraySet` plus a sorted `float[]`. Worth investigating whether the step-up scan
   can reuse the collider list already built for the primary sweep.
3. **`MoveToBlockGoal.findNearestBlock`** — 90 of 387 `ServerChunkCache.getChunk` leaf samples came
   from this one goal re-scanning a block volume every tick. A per-search chunk hoist looks tractable.
4. **`ChunkMap.tick`** — allocates a `SectionPos` and a `ChunkPos` per tracked entity per tick purely
   to compare against the previous value, and makes a second full pass over the entity map when any
   player moved.
5. **Client extract phase** — if a GPU and a shader pack are available for validation,
   `LevelExtractor` entity render state pooling and `RenderRegionCache` reuse are the render-side
   targets that sit *off* Sodium's terrain path and are therefore the safest place to start.
