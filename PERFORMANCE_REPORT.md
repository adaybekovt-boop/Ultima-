# Ultima performance report

Target: Minecraft Java Edition 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, Java 25.

## Summary

Four optimizations are implemented, each behind its own switch in `config/ultima.properties`. All
four sit in code that both the dedicated server and the client execute, so they apply to multiplayer
servers, to the integrated server in single-player, and to client-side entity and particle physics.

Measured end to end on a dedicated 26.2 server under a fixed 1100-entity load spread over 1089
force-loaded chunks, mean tick time dropped from **10.26 ms to 8.85 ms (-13.7%)** and sustained tick
rate rose from **96.9 to 112.3 TPS (+15.9%)**, over four baseline runs and three optimized runs whose
ranges do not overlap.

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

> **Amended by the second-pass architectural audit.** `ARCHITECTURAL_AUDIT.md` re-reviewed all four
> modules and corrected three claims made below. In short: `block_collision_shape` was not removing
> the allocation it describes (the injector used does not suppress the call); `entity_section_lookup`
> had a fallback guard that left an unbounded worst case, measured at ~45x slower than vanilla on a
> sparse world; and the `-9.7%` attributed to `collision_shell_skip` was measured on a superflat
> world, which is that module's best case by construction. Corrections are inline in each section
> below and marked **[audit]**. The end-to-end A/B figures themselves were not re-run — the audit
> environment could not reach Fabric maven or Mojang, so no server could be built or benchmarked.

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
- **Why no stale-state risk:** nothing is cached beyond a single query. There is no index that could
  fall out of sync with the world and let an entity walk through a fence, which is exactly what a
  maintained per-section counter would have risked.
- **Fallback:** the fast path requires a `Level` that is not a debug world (a debug world synthesises
  block states in `LevelChunk.getBlockState` without consulting the section, so its palettes say
  nothing) and chunks that are `LevelChunk`. `GlobalPalette.maybeHas` returns `true`
  unconditionally, so a section with a palette too large to discriminate falls open to vanilla by
  itself. An absent chunk is treated as contributing nothing, which is what vanilla does with it.
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
- **[audit] The -9.7% is specific to the superflat bench world and does not generalize.** The
  saving is a fixed count of skipped shell positions, but the cost is
  `Σ over covered sections of (palette size)` predicate tests per query, because
  `LevelChunkSection.maybeHas` scans the palette. `scripts/bench-server.sh` sets
  `level-type=minecraft:flat`, whose sections are single-value air or a ~4-entry flat stack, so a
  query pays roughly 4-8 tests. An ordinary overworld surface or cave section carries a
  `LinearPalette` (<=16) or `HashMapPalette` (17-256), so the same query pays roughly 60-180. The
  benchmark understates this module's cost by about an order of magnitude while its benefit is
  unchanged. **An A/B on a default world type is required before this figure is quoted again.**
  Two further costs belong in that measurement and were never counted: `ultimaShellIsIrrelevant()`
  performs one `getChunkForCollisions` per (x, z) column per query *outside* the one-entry chunk
  cache in `BlockCollisions`, and a `GlobalPalette`-backed section returns `true` unconditionally
  after the scan of every other section has already been paid.
- **[audit] Ordering fix.** The eligibility test used to run before the `instanceof InteriorOnlyCursor`
  check. With the `cursor_step` module disabled, that meant every query paid the full palette scan
  and then found no cursor to drive. The two are now tested cheap-first, and Ultima warns at startup
  when that combination is configured.

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
- **Fallback:** if the candidate volume exceeds the total number of existing sections, the vanilla
  path runs instead, so a pathological query can never make Ultima probe more sections than vanilla
  could visit. A degenerate or infinite bounding box produces a candidate count computed in `long`
  arithmetic and falls back for the same reason.
- **[audit] The guard originally read `candidates > 1024 && candidates > sectionIds.size()`, which
  did not bound the worst case.** Because both clauses had to hold, any query covering 1024 or
  fewer candidate sections took the direct-probe path however sparse the world was — that is,
  protection was disabled exactly where it was needed, since direct probing wins on dense worlds and
  loses on empty ones. Wide boxes are routine (mob follow ranges, explosion entity collection,
  command selectors), and a 128-block box yields ~787 candidates, comfortably under the budget.
  Measured on real fastutil containers with 3 populated sections and a 128-block query, 200k
  queries after warmup: vanilla **101 ns**, Ultima with the old guard **4579 ns**, Ultima with the
  `1024` clause removed **90 ns** — a ~45x regression, repaired. The dense-world wins are unaffected
  (an entity-sized query covers 2-8 candidates against thousands of sections). Result equivalence
  under the tightened guard was re-verified over 18000 queries with 0 mismatches. Reproduce with
  `bash tools/audit/run.sh`.
- **[audit] Residual, disclosed:** mid-density worlds where the candidate count is just under
  `sectionIds.size()` but the x-strip is nearly empty still probe more than vanilla walks (measured
  33.9 vs 13.2 work units on one such shape). Closing that needs the per-strip population, which a
  `LongSortedSet` cannot supply in O(1). The unbounded case is gone; this one is bounded and small.
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
- **Mod compatibility risk:** low. Implemented with MixinExtras (bundled in Fabric Loader 0.19.3),
  which is stackable — unlike `@Redirect`, several mods can wrap the same expression without an
  exclusivity conflict.
- **Shader compatibility risk:** none.
- **[audit] The original implementation did not remove the allocation described above.** It used
  `@ModifyExpressionValue` on the `Shapes.create` call and returned `null`. That annotation does not
  suppress the expression: MixinExtras 0.5.4's own javadoc says the handler "receives the
  expression's resultant value ... and should return the adjusted value", and
  `ModifyExpressionValueInjector.injectValueModifier` *inserts* the handler call after the original
  instruction rather than replacing it. `Shapes.create(box)` therefore still ran and still allocated;
  only the reference was discarded. Any real saving depended on C2 inlining the call and proving the
  result non-escaping, which is plausible but unguaranteed, while the module reliably added a handler
  invocation per construction and a null check per `entityShape` read. It is now implemented with
  `@WrapOperation`, whose handler declines to call `original`, which is what actually skips the call.
  No varargs array is allocated precisely because `original` is never invoked, and this runs once per
  query rather than once per block position — so the boxing cost that disqualified `@WrapOperation`
  for `collision_shell_skip` does not apply here.
- **[audit] Consequence for the staged measurements:** this module's historical contribution should
  be assumed to be near zero and re-measured. It was never isolated — it is folded into the
  three-module staged row below — so no published figure has to be withdrawn, but none supports it
  either.
- **[audit] Known fragility:** both the old and the new form leave `entityShape` null and patch its
  reads in `computeNext` only. If a future Minecraft version reads that field from a second method,
  the read returns null and throws. `defaultRequire: 1` does not protect against this; it validates
  that injection points resolve, not that the set of field readers is unchanged. Re-validate on
  every version bump.
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

| Run | Vanilla behaviour (all modules disabled) | All four modules |
|---|---|---|
| 1 | 10.40 | 9.03 |
| 2 | 10.31 | 8.67 |
| 3 | 10.26 | 8.86 |
| 4 | 10.08 | — |
| **mean** | **10.26** | **8.85** |
| range | 10.08 - 10.40 | 8.67 - 9.03 |
| sustained TPS | 96.9 | 112.3 |

**-13.7% mean tick time, +15.9% sustained tick rate.** The ranges do not overlap by a wide margin:
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
strip vanilla walks is already short. They are representative for `cursor_step`, which scales with
block collision volume and is present in every tick.

**[audit] The claim that they are also representative for `collision_shell_skip` is withdrawn.**
That module's cost scales with section palette size, and the bench world is superflat
(`level-type=minecraft:flat`), whose palettes are the smallest that exist. Its benefit is
world-independent but its cost is not, so this world is its best case by construction. See the
module's entry above and `ARCHITECTURAL_AUDIT.md` §A-5. The bench script should grow a
default-world mode before the next optimization is measured against it.

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

## Build status

Last command: `./gradlew --no-daemon clean build` — **BUILD SUCCESSFUL**.

`bash scripts/check.sh` runs the same build. No vanilla source is copied into the repository, and
`.agent/` is untracked (0 files matched in `git ls-files`).

**[audit] The second-pass changes have not been through that build.** The audit environment's egress
proxy denies `maven.fabricmc.net` and Mojang's distribution hosts (403 to `CONNECT`), so Loom cannot
resolve `fabric-loom:1.17-SNAPSHOT`, no Minecraft jar can be fetched, `.agent/vanilla-src` cannot be
generated and no server can be launched. What was verified instead:

- **Differential harnesses**, now committed at `tools/audit/` and runnable with
  `bash tools/audit/run.sh`: 0 mismatches over 40216 cursor volumes / 4963671 positions, and 0
  visited-set and 0 visit-order mismatches over 24000 entity-section queries, plus 18000 more under
  the tightened guard.
- **A stub-based type check** of all of `src/main/java` against hand-written Minecraft, Fabric and
  Sponge Mixin signatures with the real MixinExtras 0.5.4, fastutil and jspecify jars: compiles
  clean under `-Xlint:all`. This validates Ultima's own syntax, generics and handler signatures; it
  does **not** validate that the Mixin targets exist in 26.2.
- Both JSON resources parse.

`bash scripts/check.sh` must be run on a machine with Fabric maven access before release.

## Runtime validation

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

Ranked by the evidence gathered, not by guesswork.

**[audit] Re-ranked.** The second pass promotes the fan-out above the predicate allocation, and
sharpens what to do about it. Rather than proving the step-up and `findSupportingBlock` volumes are
contained in the primary sweep's volume — which needs a containment proof — memoise the
*shell-eligibility answer* for the duration of one `Entity.move`. No block state changes during a
single entity movement, so the palette answer is constant across all three traversals of that
movement. That cuts the palette cost identified in `ARCHITECTURAL_AUDIT.md` §A-5 by roughly 3x and
is the change most likely to make `collision_shell_skip` unambiguously positive on ordinary worlds.
It needs a working build to measure and was deliberately not implemented on evidence this
environment could not produce.

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
