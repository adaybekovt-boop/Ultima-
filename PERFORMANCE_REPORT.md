# Ultima performance report

> **Forensic status (2026-08-14): historical evidence, not a release claim.**
> `REVIEW_GPT56.md` found that the original `block_collision_shape` injector still executed
> `Shapes.create`, the checked-in server harness paused before measurement on Minecraft 26.2, and
> its force-load commands covered 2304 chunks rather than the claimed 1089. The implementation and
> harness have since been hardened, but the measurements below describe the pre-review revision and
> must not be attributed to the reviewed code. See `REVIEW_GPT56.md` for the accepted findings,
> rerun results, and merge recommendation.

Target: Minecraft Java Edition 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, Java 25.

## Release-candidate configuration

| Module | Enabled by default | Reason |
|---|---|---|
| `cursor_step` | yes | bounded arithmetic substitution with differential coverage |
| `entity_section_lookup` | no | cancelling whole-method replacement overlaps entity optimization mods |
| `block_collision_shape` | no | deferred call cannot compose perfectly with every constructor-time wrapper |
| `collision_shell_skip` | no | lazy palette snapshot and pre-check trade-offs require explicit opt-in |
| `client_chunk_matrix_reuse` | vanilla client only | same-frame uniform matrix reuse; auto-disabled for Sodium/Iris |
| `client_chunk_layer_array_reuse` | vanilla client only | enum-array allocation reuse; auto-disabled for Sodium/Iris |
| `client_chunk_dirty_dedup` | vanilla client only | duplicate section invalidation writes; auto-disabled for Sodium/Iris |

The common/server default is `cursor_step` only. A vanilla client additionally enables the three
client modules; Sodium/Iris clients automatically keep them off. “All modules enabled” is an
experimental configuration used to bound the synthetic workload's potential, not the behavior users
receive. See `CLIENT_PERFORMANCE_REPORT.md`.

A real RTX 3090 client A/B of disabled versus default measured **−0.28% average FPS** in a
stationary Fancy RD16 scene. That result is inconclusive (pair deltas flip sign; n=3; paired SD
4.45 pp) and is **not** a release FPS claim. Visual equivalence is FAIL. Compatibility on that
vanilla profile is PASS. Ready for release: **NO**.

## Historical summary (superseded)

Four optimizations are implemented, each behind its own switch in `config/ultima.properties`. All
four sit in code that both the dedicated server and the client execute, so they apply to multiplayer
servers, to the integrated server in single-player, and to client-side entity and particle physics.

Measured end to end on a dedicated 26.2 server under a fixed 1100-entity load spread over 1089
force-loaded chunks, the old report claimed **10.26 ms to 8.85 ms (-13.7%)** and **96.9 to 112.3
uncapped ticks/s (+15.9%)**. Those values are retained only as history: their harness and arithmetic
failed forensic review and they do not describe the release candidate.

Target selection was driven by a Java Flight Recorder profile of the running server rather than by
inspection alone. That profile is what disqualified two changes I had already designed, and what
identified the largest one. Every optimization that reorders work was checked against a faithful
port of the vanilla algorithm in a differential harness before being kept.

Most of the gain comes from one architectural finding rather than from local tuning. After the first
three optimizations reached diminishing returns, a second-order pass over the *system* rather than
the loop established that **87% of all block positions a collision query visits exist only to catch
two rare block properties**, and can be skipped outright once the section palettes say those
properties are absent. That single change accounts for a 9.7% reduction on top of the other three.

Six candidates were rejected outright, including one whose first implementation worked but was
replaced after measurement showed the injection mechanism cost more than the optimization saved. The
client renderer was deliberately left untouched; the reasoning is in *Rejected optimizations*.

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
Minecraft class. Known missing keys use declared defaults, unknown Mixin module packages fail closed
to vanilla, dependencies are registry data, and any read/write failure degrades to defaults with a
warning rather than failing.

## Second-order architectural pass

After the first three optimizations, the profile still showed the collision subsystem dominating, so
the next question was not "how do I make this loop faster" but "why does this loop have so much to
do". Two structural observations came out of it.

**A single entity movement triggers three separate traversals of overlapping block volumes.**
`Entity.collide` scans the box expanded towards the movement; the step-up path rescans a second
volume; `Entity.checkSupportingBlock` runs a third scan through `findSupportingBlock`. Instrumenting
the server confirmed it: **2.6 million collision queries in 800 ticks with 1100 entities, about 3
queries per entity per tick**. This is the fan-out that makes the collision iterator the hottest code
in the game.

**Most of what those traversals examine cannot possibly matter.** The iterator walks the collider's
volume grown by one block in every direction. Only the interior is tested normally; the surrounding
shell is examined solely to catch a block whose collision shape reaches outside its own cube, or a
moving piston. Instrumentation measured **109 million positions visited in 800 ticks, 87% of them
shell positions**, each costing a chunk lookup and a block state read to conclude that ordinary
terrain is ordinary.

Classifying the candidates this pass produced:

| Class | Candidate | Outcome |
|---|---|---|
| MACRO | Skip the shell when no section in the volume can hold a qualifying block | **Implemented** (`collision_shell_skip`) |
| MACRO | Shared per-tick block/shape cache across the 3 traversals | Rejected — the palette read is already O(1) and cheaper than a validated cache lookup |
| MACRO | Maintained per-section index of large-shape blocks | Rejected in favour of the per-query palette query, which needs no lifecycle |
| MESO | Reuse the collider list from the primary sweep for the step-up scan and `findSupportingBlock` | Not attempted — the boxes are derived from different positions; needs proof of containment |
| MESO | Skip `ServerEntity.sendChanges` when no player tracks the entity | Not attempted — `sendChanges` also advances state that later deltas are based on |
| MICRO | Multi-entry chunk cache, `getOnPos` caching, profiler overhead | Rejected, see below |

The implemented MACRO change is the one that eliminates a whole class of repeated work rather than
speeding up an operation, and it improves every collision caller at once: entity movement, supporting
block resolution, suffocation checks, spawn placement checks, particle physics and
`getAvailableSpaceBelow`.

## Implemented optimizations

### 1. `collision_shell_skip` — skip the shell of a collision query

- **Subsystem:** block collision iteration (server and client).
- **Vanilla hotspot:** `BlockCollisions.computeNext()` via `Cursor3D`, at 28.4% inclusive of the tick.
- **Change:** the volume is grown by one block in every direction, and a shell position only
  contributes if `blockState.hasLargeCollisionShape()` (for a face position) or
  `blockState.is(Blocks.MOVING_PISTON)` (for an edge position); corner positions are already skipped
  by vanilla. Whether *any* block in the volume can qualify is a question about the block palettes of
  the few sections it covers, and `LevelChunkSection.maybeHas` — a public vanilla method — answers it
  without touching individual blocks. Asked once per query, the answer lets the cursor visit only the
  interior.
- **Why faster:** removes the chunk lookup, position write, block state read and condition evaluation
  for 87% of visited positions. The interior-only traversal visits **15.4%** of the positions the full
  traversal does.
- **Why behaviour is equivalent:** the emitted sequence is exactly the `TYPE_INSIDE` subsequence of
  the vanilla traversal, in the same order, so every result vanilla would have produced is still
  produced in the same order. Order matters because callers collect into lists.
- **Lifecycle:** no persistent world index is maintained, avoiding unload/reload invalidation.
  However, the palette answer is a constructor-time snapshot for a lazy iterator. A mod that retains
  the iterator or mutates an unvisited shell position during a callback can diverge from vanilla;
  this is why the module is opt-in.
- **Fallback:** the fast path requires a `Level` that is not a debug world (a debug world synthesises
  block states in `LevelChunk.getBlockState` without consulting the section, so its palettes say
  nothing) and chunks that are `LevelChunk`. `GlobalPalette.maybeHas` returns `true`
  unconditionally, so a section with a palette too large to discriminate falls open to vanilla by
  itself. An absent chunk is treated as contributing nothing, which is what vanilla does with it.
  Cursor eligibility is checked before any palette access, so degenerate or int-overflowing volumes
  fail open before potentially unbounded section work.
- **Mod compatibility risk:** low-medium. Blocks from other mods are handled conservatively: a state
  with a dynamic shape has no shape cache and therefore reports `true` from
  `hasLargeCollisionShape()`, which disables the fast path for that section. The hooks are an
  `@Inject` at the constructor `TAIL` and the already-owned `Cursor3D.advance()`; `computeNext` itself
  is untouched, so other mods' injections there are unaffected.
- **Shader compatibility risk:** none.
- **Verification:** the running server was instrumented to check every skipped position against the
  qualifying predicate: **90,688,524 skipped positions, zero holding a block that could have
  mattered**, with **95% of queries eligible** for the fast path. Separately, an offline harness
  confirmed the interior-only cursor emits exactly the `TYPE_INSIDE` subsequence in order across
  300,000 volumes including degenerate spans, and stays exhausted after finishing. The instrumentation
  was removed before the change was kept.
- **Contribution:** mean tick time 9.80 ms to 8.85 ms, **-9.7%**, on top of the other three.

### 2. `entity_section_lookup` — direct entity section lookup

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
- **Fallback:** vanilla runs if the candidate volume exceeds 1024 sections or exceeds the total
  loaded section count. Saturating arithmetic handles products up to `2^96`, packed-coordinate
  bounds prevent aliasing, and unsupported ranges fail open before direct probing.
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

### 3. `cursor_step` — block iteration without integer division

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

### 4. `block_collision_shape` — deferred collider voxelisation

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
- **Mod compatibility risk:** medium. A MixinExtras `@WrapOperation` suppresses the eager call,
  retains the supplied operation chain, and invokes it lazily so inner wrappers still participate.
  Perfect composition is impossible: an outer wrapper can still observe the constructor-time null
  sentinel, and a mod reading vanilla's private final field before iteration can observe null.
  The module is therefore opt-in.
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

### Release-candidate three-configuration A/B

The corrected harness ran three alternating rounds after final integration. Each fresh JVM used a
1000-tick warmup and a 2500-tick measured sprint in a recreated 1156-force-loaded-chunk world.

| Round | All disabled | Release default (`cursor_step`) | All enabled experimental |
|---|---:|---:|---:|
| A | 8.82 ms/tick | 8.21 ms/tick | 7.25 ms/tick |
| B | 8.85 ms/tick | 8.97 ms/tick | 7.42 ms/tick |
| C | 8.91 ms/tick | 8.73 ms/tick | 7.57 ms/tick |
| **Mean** | **8.86 ms/tick** | **8.64 ms/tick** | **7.41 ms/tick** |

The default configuration's mean was 2.5% lower than all-disabled, but ranges overlap and one default
run was slower than every baseline. Treat this as directional, load-specific evidence rather than a
stable release percentage. All-enabled experimental was 16.3% lower on this collision-heavy load,
with non-overlapping ranges, but it includes three intentionally opt-in modules.

Console ticks/s from `/tick sprint` are uncapped synthetic throughput, not normal Minecraft TPS. All
nine runs loaded the expected module counts, killed 88 cows, stopped cleanly, and had no Mixin
failure or exception.

### Historical end-to-end A/B (superseded)

`scripts/bench-server.sh <label> <enabled|disabled>` recreates the world from scratch each run:
superflat, fixed seed, view and simulation distance 10, 1089 force-loaded chunks, and a generated
1100-entity load (700 mobs of 8 kinds, 400 item entities) spread over ±260 blocks, then sprints 2500
ticks flat out. Both sides ran the same load; each run ends by killing all cows and reported exactly
88 every time, confirming the two sides really ticked the same population.

Sprint result, ms per tick over 2500 ticks:

| Run | Vanilla behaviour (all modules disabled) | All four modules |
|---|---|---|
| 1 | 10.40 | 9.03 |
| 2 | 10.31 | 8.67 |
| 3 | 10.26 | 8.86 |
| 4 | 10.08 | — |
| **mean** | **10.26** | **8.85** |
| range | 10.08 - 10.40 | 8.67 - 9.03 |
| uncapped sprint throughput (ticks/s) | 96.9 | 112.3 |

**-13.7% mean tick time, +15.9% uncapped sprint throughput.** The ranges do not overlap by a wide margin:
the worst optimized run (9.03) beats the best baseline run (10.08). The fourth baseline run was taken
last, after all optimized runs, to rule out machine drift.

Staged, so each step is attributable:

| Configuration | mean ms/tick |
|---|---|
| Vanilla behaviour | 10.26 |
| + `entity_section_lookup`, `cursor_step`, `block_collision_shape` | 9.80 |
| + `collision_shell_skip` via MixinExtras `@WrapOperation` | 9.62 |
| + `collision_shell_skip` driving the cursor directly | **8.85** |

`/tick query` P50 over the 100 ticks following each sprint was 10.2 / 9.9 / 9.6 / 9.5 ms baseline
against 8.7 / 9.2 / 8.1 ms optimized.

**P95 and P99 are not claimed as improvements.** Both sides show occasional large outliers consistent
with garbage collection — baseline P99 ranged 15.1 to 43.1 ms and optimized 12.6 to 26.7 ms — and 100
samples is far too few to separate that from signal. Frame-pacing and tick-pacing claims need the
longer runs listed under *Required real-PC tests*.

These figures are specific to this load. They understate `entity_section_lookup`, whose isolated gain
is 8-21x but which has little to bite on here: the entities occupy roughly 33 chunk columns, so the
strip vanilla walks is already short. They are representative for `cursor_step` and
`collision_shell_skip`, which scale with block collision volume and are present in every tick.

## Rejected optimizations

**MixinExtras `@WrapOperation` as the mechanism for `collision_shell_skip`.** The first working
implementation rejected shell positions by wrapping the chunk lookup inside `computeNext` and
returning `null`, letting vanilla's own null guard do the skipping. That is elegant and stackable,
and it was correct — but `Operation.call(Object...)` boxes both `int` arguments into a varargs array
on every position that is *not* skipped, and in a loop running 109 million times per 800 ticks that
overhead consumed most of the gain: **9.62 ms against 8.85 ms** for driving the cursor directly. The
lesson is that the most compatibility-friendly injector is not always affordable in the hottest loop
in the game; the replacement uses hooks Ultima already owns and leaves `computeNext` untouched, which
is arguably *better* for compatibility as well.

**A maintained per-section index of large-shape blocks.** The original design for
`collision_shell_skip` was a counter on `LevelChunkSection` maintained like vanilla's `fluidCount`.
It was abandoned after tracing the write paths: `recalcBlockCounts()` is called from only one of the
three constructors, `read(FriendlyByteBuf)` restores some counters from the wire but not others, and
`UpgradeData` mutates a section's `PalettedContainer` directly through the public `getStates()`,
bypassing `setBlockState` entirely — and it does so specifically to fix up fence and wall
connectivity, which is exactly the block class the index would track. An index that under-counts lets
an entity walk through a fence. Querying the palette per query instead has no lifecycle to get wrong,
and `maybeHas` turned out to be cheap enough (O(1) for uniform sections) that the maintained index was
unnecessary.

**A shared per-tick block state and shape cache across the three collision traversals.** The obvious
response to "one movement scans overlapping volumes three times" is to memoise block states for the
tick. Rejected on arithmetic: `LevelChunkSection.getBlockState` is a bit-packed palette read of a few
nanoseconds, so a hash lookup plus the validity check needed to stay correct against mid-tick block
changes would cost as much as the read it replaces, while adding an invalidation surface that could
corrupt physics. The redundancy is real, but it is in the *number of positions visited*, not in the
cost of reading one — which is what led to the shell finding instead.

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

## Release-candidate build status

Required commands completed on the integrated release-candidate code:

- `bash scripts/check.sh` — **BUILD SUCCESSFUL**, including `forensicRegressionTest`;
- `./gradlew --no-daemon clean build` — **BUILD SUCCESSFUL**.

Production artifact: `build/libs/ultima-0.1.0.jar`. It contains `fabric.mod.json`,
`ultima.mixins.json`, `Ultima.class`, all four compiled Mixin classes, config classes, and utility
classes. It is distinct from `ultima-0.1.0-sources.jar` and contains `.class`, not `.java`, entries.

`bash scripts/check.sh` runs the same build. No vanilla source is copied into the repository, and
`.agent/` is untracked (0 files matched in `git ls-files`).

## Historical runtime validation (superseded)

Performed on a real dedicated 26.2 server with the mod loaded (`Ultima initialized with 4 of 4
optimization modules enabled`, `mixinextras 0.5.4` present):

- server startup, keypair generation, world creation and spawn preparation;
- data pack reload, 1089 chunks force-loaded across 9 commands, 1100 entities summoned;
- 2500-tick sprints, twelve times across the staged configurations, including four baseline runs and
  three with all four modules enabled;
- an instrumented run that audited 109 million collision positions and 90.7 million skipped ones;
- `/tick query` percentile reporting;
- clean `/stop` with all three dimensions saved;
- **zero exceptions and zero Mixin failures** in any run. The only `ERROR` line, `No key layers in
  MapLike[{}]`, also appears in a pristine run of this workspace before any Mixin existed.

Because `ultima.mixins.json` sets `defaultRequire: 1`, a Mixin whose injection point failed to
resolve would abort class load. All target classes are loaded during world and entity ticking, so a
clean sprint demonstrates that they actually applied; the audit run additionally showed the collision
hook executing by name in a stack trace.

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
2. **The three-traversals-per-movement fan-out** — instrumentation measured about 3 collision queries
   per entity per tick. `collision_shell_skip` made each one much cheaper, but the duplication itself
   remains. The tractable version is proving that the step-up volume and the `findSupportingBlock`
   volume are contained in the volume already scanned by the primary sweep, which would let the
   existing collider list be filtered instead of rescanned. This needs a containment proof before any
   code, since the boxes are derived from different positions.
3. **`MoveToBlockGoal.findNearestBlock`** — 90 of 387 `ServerChunkCache.getChunk` leaf samples came
   from this one goal re-scanning a block volume every tick. A per-search chunk hoist looks tractable.
4. **`ChunkMap.tick`** — allocates a `SectionPos` and a `ChunkPos` per tracked entity per tick purely
   to compare against the previous value, and makes a second full pass over the entity map when any
   player moved.
5. **Client extract phase** — if a GPU and a shader pack are available for validation,
   `LevelExtractor` entity render state pooling and `RenderRegionCache` reuse are the render-side
   targets that sit *off* Sodium's terrain path and are therefore the safest place to start.
