# GPT-5.6 forensic optimization review

Review date: 2026-08-14  
Target: Minecraft Java 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, Java 25  
Reviewed range: `main..782ecca`, plus the corrective commits on this review branch

## Executive summary

The optimization branch was not safe to merge as reviewed. Two implementation claims were false in
ways that materially affected the risk/performance decision:

1. `block_collision_shape` used `@ModifyExpressionValue`. That injector receives the result only
   after `Shapes.create(box)` has run, so it discarded the eagerly allocated shape rather than
   preventing the allocation. A non-cube collision then built the shape a second time.
2. The checked-in benchmark could not reproduce its report on Minecraft 26.2. The server pauses
   after 60 seconds with no players, while the script waited roughly 90 seconds before its first
   sprint. The script also force-loaded 2304 chunks, not the reported 1089.

There were also real edge-case correctness defects in direct section lookup and cursor stepping:
overflowing candidate-volume arithmetic, packed-coordinate aliasing, inverted/overflowing cursor
ranges, and observer-visible cursor index divergence. Those are fixed and covered by committed
differential checks.

`collision_shell_skip` remains a semantic snapshot optimization on a lazy iterator. A block/chunk
change after construction can make it skip a shell block that vanilla would see. It is now disabled
by default. `entity_section_lookup` remains a whole-method cancelling Mixin on a method known to be
optimized by Lithium-like mods; it is also disabled by default.

No renderer or render-stage Mixin exists in this branch. Nothing bypasses a shader-observable pass,
frame graph, terrain submission, entity render extraction, or shader state transition. Shader risk
is indirect and limited to client physics/entity iteration.

## Findings by severity

### High

#### H1 — `block_collision_shape` did not remove the claimed allocation (fixed)

The constructor hook was an expression-value modifier. `Shapes.create(AABB)` had already completed
before the handler returned `null`. The common path therefore retained all eager computation and
allocation; the uncommon non-cube path called `Shapes.create` again from `computeNext`.

The hook is now a MixinExtras `@WrapOperation` that intentionally does not call the constructor
operation. Runtime output is unchanged for vanilla finite AABBs, and the first non-cube read creates
and retains one shape.

Remaining compatibility risk: the vanilla private final `entityShape` is temporarily `null`, and an
inner third-party wrapper around the same call is not invoked when Ultima terminates the operation
chain. Mods that access that field or transform the same constructor/field read require testing.

#### H2 — `collision_shell_skip` can use stale world state (mitigated, not eliminated)

`BlockCollisions` is a lazy iterator. Ultima scans section palettes in its constructor and then
changes the cursor to emit only interior positions. If a fence, wall, dynamic large-shape block,
moving piston, chunk, or modded collision state changes before an unvisited shell position would
have been read, vanilla sees the new state and Ultima does not.

The same design also calls `getChunkForCollisions` across the query at iterator construction even
when the caller never consumes the iterator or short-circuits on an early collision. That changes
call timing/order observable to chunk and collision mods.

Normal vanilla movement consumes these iterators immediately on one thread, so the practical
window is small, but exact semantic equivalence is not proven for a public lazy iterator and modded
callbacks. The module is now opt-in and its dependency on `cursor_step` is enforced.

#### H3 — direct section lookup aliased out-of-range coordinates (fixed)

`SectionPos.asLong` truncates x/z to 22 bits and y to 20 bits. Vanilla decodes y and z from each key
before applying query bounds. Directly packing an out-of-range y/z candidate could alias an
unrelated loaded section and return entities vanilla rejects. Ultima now falls back to vanilla when
any direct range is outside the signed packed domains.

The candidate volume could also reach a mathematical `2^96`; multiplying three `long` spans could
wrap to zero/negative and bypass the broad-query fallback. Volume arithmetic now saturates at
`Long.MAX_VALUE`.

#### H4 — the published benchmark was not reproducible as checked in (fixed harness, invalidated old evidence)

Minecraft 26.2 defaults `pause-when-empty-seconds` to 60. The old harness exceeded that before
issuing `tick sprint`, then blocked waiting for a sprint the paused server did not process. This was
reproduced during review. The harness now sets the property to zero.

The old nine force-load commands each covered up to 16×16 chunks, for 2304 chunks total. The entity
span actually occupies chunk coordinates -17 through 16, or 34×34 = 1156 chunks. The commands now
cover exactly that range.

The historic 10.26 ms → 8.85 ms figures cannot be tied to the checked-in script and are not accepted
as evidence for the reviewed code.

#### H5 — known same-target conflict for entity lookup (mitigated)

`entity_section_lookup` cancels all of
`EntitySectionStorage.forEachAccessibleNonEmptySection` at `HEAD`. Lithium has shipped direct entity
section retrieval logic on this same method family. Injection priority decides which whole-method
replacement wins; one mod can suppress the other's hook/body assumptions. This is not a crash in
vanilla, but it is a high conflict surface for the exact mod category users will combine with
Ultima. The module is now disabled by default.

### Medium

#### M1 — cursor edge inputs and int wrap were not equivalent (fixed)

`Cursor3D` is public and accepts arbitrary integer bounds. For zero/inverted dimensions vanilla can
divide by zero or follow wrapped arithmetic; the carry implementation previously returned different
results. Positive dimensions whose product exceeds `Integer.MAX_VALUE` also let vanilla's index
wrap, after which modular coordinates differ from a carried coordinate.

Carry eligibility is now computed once at construction. Unsupported ranges run the untouched
vanilla method. The check adds one cached boolean branch, not multiplication in the hot loop.

#### M2 — interior-only cursor exposed a different `index` (fixed)

The old interior cursor incremented `index` once per emitted interior position, not once per vanilla
linear position. Another Mixin shadowing the private field could observe a different index, and the
exhausted value was not `end`. The interior path now maintains vanilla's post-advance linear index
for every emitted position and sets `index=end` on exhaustion.

#### M3 — entity lookup's performance fallback compared the wrong quantities (fixed)

The old guard allowed more than 1024 direct probes whenever the candidate volume was no larger than
the *global* `sectionIds` count. Global sections outside the queried x strip do not represent work
the vanilla query would perform. A broad empty-strip query could replace zero vanilla section visits
with thousands of hash probes. Direct lookup is now unconditionally capped at 1024 candidates, and
the no-longer-needed `sectionIds` shadow was removed.

#### M4 — module configuration could silently misreport behavior (fixed)

- A typo such as `enabled` or `treu` was parsed as `false` without warning.
- `collision_shell_skip=true` with `cursor_step=false` loaded a no-op shell Mixin but counted it as
  enabled because `Cursor3D` lacked `InteriorOnlyCursor`.
- Security failures while writing the config could escape despite the documented fallback.

Boolean parsing is now strict, invalid values retain the declared default with a warning, the module
dependency affects both Mixin application and enabled count, and write-time security failures are
handled. Existing explicit configs are preserved; users of prerelease builds should review old
generated values because changing defaults does not override explicit `true`.

#### M5 — Mixin failure policy is fail-closed

`ultima.mixins.json` is required and uses `defaultRequire: 1`. This is useful for detecting Minecraft
signature drift, but any enabled injection whose target is removed/restructured by another mod can
abort class transformation instead of falling back. Per-module switches limit recovery after a
conflict is identified; they do not provide runtime self-disabling. This especially affects the two
`BlockCollisions` Mixins and the cancelling `EntitySectionStorage`/`Cursor3D` Mixins.

### Low / informational

- All production Mixins target common game classes and import no client-only classes. Dedicated
  server classloading is clean.
- No global world/entity/chunk cache was added. There are no unbounded maps, retained level
  references, executor changes, locks, or cross-world lifecycle state.
- `block_collision_shape` retains its lazy shape only for the lifetime of the vanilla iterator.
- Sodium/Iris primarily replace renderer paths not touched here. Their shader/frame-graph contracts
  are not bypassed. Lithium-like entity/collision optimizers are the primary compatibility concern.
- Entity/chunk/collision mods can observe changed injection order, eager palette/chunk queries, or
  cancellation even when vanilla outputs match.

## Optimization verdicts

| Optimization | Correctness confidence | Compatibility confidence | Performance evidence quality | Risk | Verdict |
|---|---:|---:|---:|---|---|
| `entity_section_lookup` | 90/100 | 40/100 | 25/100 | HIGH | **DISABLE BY DEFAULT** |
| `block_collision_shape` | 88/100 | 52/100 | 20/100 | MEDIUM | **KEEP BUT HARDEN** (injector fixed) |
| `cursor_step` | 96/100 | 65/100 | 55/100 | MEDIUM | **SAFE TO KEEP** |
| `collision_shell_skip` | 58/100 | 48/100 | 35/100 | HIGH | **DISABLE BY DEFAULT** |

### `entity_section_lookup`

For packable, bounded, stable storage the direct loop reproduces vanilla signed-long ordering:
ascending x, then non-negative z before negative z, then non-negative y before negative y. The
committed differential test exercises mixed-sign ranges against real `SectionPos` packing.

Confidence is not higher because a cancelling replacement has different composition behavior with
other injections and because mutation of section storage from an output callback is not a supported
equivalence case. The reported 8.2×/21.4× microbenchmarks have no source, raw output, JMH metadata, or
reproduction command in the branch. The end-to-end staging grouped this module with two others.

### `block_collision_shape`

For vanilla, `AABB` is immutable and the corrected operation computes the same `Shapes.create(box)`
value at most once when the non-cube branch needs it. There is no world lifecycle/cache issue.

The prior benchmark measured the ineffective injector. The corrected hook may save the documented
allocation, but it has not been isolated from the other modules and its wrapper/null-field
compatibility needs a Lithium-like collision stack. Keep it switchable.

### `cursor_step`

For eligible positive, non-wrapping dimensions, carried x/y/z is algebraically identical to
vanilla's modulo/division sequence. Unsupported dimensions fall through to vanilla. The interior
subsequence and post-advance index are also differential-tested.

The remaining risk is Mixin composition: this is a cancellable `HEAD` injection into a tiny utility
method. A second mod replacing `advance` will be priority-sensitive. No render-stage or world-state
risk exists.

### `collision_shell_skip`

Palette rejection is conservative at the instant it runs: dynamic states report a large shape,
global palettes return true, non-`LevelChunk` getters and debug worlds fall back, absent chunks match
vanilla at that instant, and out-of-height sections are air. Negative section coordinates use
arithmetic shift and are handled correctly.

That proof does not cover mutation between palette scan and lazy consumption. The optimization also
changes chunk-query timing and depends on the `cursor_step` Mixin. Keep only as an expert opt-in
until a current-state validation strategy or a constrained immediate-consumption hook exists.

## Fixes made

- Replaced the ineffective constructor expression modifier with a non-invoking `@WrapOperation`.
- Added saturated section-volume arithmetic and packed-coordinate bounds.
- Replaced the global-count performance heuristic with a hard 1024-candidate cap.
- Removed the unnecessary `sectionIds` Mixin shadow.
- Added vanilla fallbacks for inverted and index-overflowing cursors.
- Preserved the vanilla cursor index during interior-only traversal.
- Cached cursor eligibility at construction to avoid hot-loop arithmetic.
- Disabled `entity_section_lookup` and `collision_shell_skip` by default.
- Enforced the shell-skip dependency on cursor stepping.
- Hardened config parsing and write failure handling.
- Added benchmark mode/label validation, explicit module discovery checks, a 1000-tick warmup,
  exact force-load bounds, empty-server pause prevention, and startup-state verification.
- Marked `PERFORMANCE_REPORT.md` historical and corrected “TPS” to uncapped sprint throughput.

## Tests added

`forensicRegressionTest`, wired into `check`, performs:

- saturated-volume tests including the full int-domain `2^96` case;
- signed SectionPos packing boundary and alias rejection tests;
- 10,000 randomized vanilla-tree-order vs direct-order comparisons;
- 100,000 randomized division/modulo vs carried cursor comparisons;
- every width/height/depth combination from 1 through 12 for interior subsequence, order, and index;
- zero, inverted, and `Integer.MAX_VALUE` cursor eligibility boundaries;
- strict config boolean parsing, module dependency, enabled-count, and risky-default assertions.

The test is dependency-free and runs as a Java verification task, avoiding a benchmark/test
framework dependency merely for arithmetic differential checks.

## Benchmark rerun

After all code fixes were committed, the corrected harness ran three alternating disabled/enabled
pairs. Each fresh JVM performed a 1000-tick warmup followed by the reported 2500-tick sprint. The
world was recreated each time and all four modules were set explicitly; the enabled side therefore
includes the two opt-in modules and is not representative of default configuration.

| Pair | All disabled (ms/tick) | All enabled (ms/tick) |
|---|---:|---:|
| A | 9.08 | 7.54 |
| B | 8.61 | 6.96 |
| C | 9.38 | 7.13 |
| **Mean** | **9.02** | **7.21** |
| Range | 8.61–9.38 | 6.96–7.54 |

For this synthetic load, combined enabled modules reduced measured sprint time by **20.1%**. Mean
reported uncapped throughput was 110.7 vs 138.3 ticks/s (**+25.0%**). All six runs killed 88 cows,
completed a clean Gradle/server shutdown, and had no exception or Mixin failure. The recurring
`No key layers in MapLike[{}]` line occurred equally on both sides and predates the Mixins.

This is credible evidence that the combined current implementation has a large effect on this
specific entity/collision stress load. It does not isolate any module, does not rehabilitate the
historic per-module nanosecond claims, and does not override the correctness/default-state verdicts.

## Benchmark caveats

- The historic nanosecond microbenchmarks are not in the repository. There is no way to check
  warmup, forks, dead-code elimination, blackhole use, CPU governor, core pinning, JVM flags, or raw
  variance. They are directional hypotheses, not accepted measurements.
- A 1.60 ns operation claim is sensitive to inlining and benchmark-loop optimization; it does not
  independently imply TPS/FPS gain.
- The original four-baseline/three-optimized sample is unbalanced, has no standard deviation or
  confidence interval, and provides no raw logs.
- The staged 9.80 → 9.62 → 8.85 sequence changes both feature set and injection mechanism. It does
  not isolate interactions among all four final modules.
- `/tick sprint` reports uncapped throughput, not a playable server rate above Minecraft's 20 TPS
  target. “112 TPS” was misleading terminology.
- A fixed seed and 88 surviving cows do not prove identical simulation histories. AI, combat,
  deaths, item merging, UUID/random initialization, asynchronous chunk completion, and GC can differ.
- The corrected harness alternates sides and warms each fresh JVM, but does not randomize order,
  pin CPU frequency/cores, isolate the host, or retain allocation/JFR profiles.
- The 100-sample `/tick query` P95/P99 output remains too small for tail-latency claims.
- No benchmark in this branch supports an FPS, shader, frame-time, or 1%-low claim.

## Remaining real-PC validation needed

1. Current Fabric builds of Sodium, Iris, and Lithium, first with risky modules at their defaults and
   then explicitly enabled one at a time. Confirm Mixin application and inspect conflict/audit logs.
2. Collision/physics mods and custom dynamic-shape blocks, including fences/walls, moving pistons,
   world-border-adjacent movement, vehicles, step-up, suffocation, supporting-block selection, and
   blocks that mutate collision state from callbacks.
3. Client testing with shaders off/on. Measure FPS, 1% lows, frame-time distribution, entity-heavy
   scenes, view distance 32, and chunk loading. The expected shader result is “no visual change,”
   but it has not been observed here.
4. Long dedicated-server and integrated-server runs across overworld/nether/end unload/reload, with
   heap/GC and tick percentiles. No cache leak is expected, but allocation savings must be profiled.
5. Isolated JMH or equivalent forked benchmarks checked into the repository for section lookup,
   shape allocation, and cursor stepping, followed by async-profiler/JFR confirmation in the game.

## Final merge recommendation

**Do not merge the original optimization head without this review branch.**

With these fixes, merge is reasonable only as a guarded experimental release:

- keep `cursor_step` enabled by default;
- keep the corrected `block_collision_shape` switchable and require a collision-mod compatibility
  pass before calling it broadly safe;
- keep `entity_section_lookup` and `collision_shell_skip` disabled by default;
- do not publish the historic 13.7%/15.9% figures as results for the reviewed code;
- do not claim shader/FPS gains.

Promoting either opt-in module to default should require the real-modpack tests above and new,
reproducible per-module evidence.
