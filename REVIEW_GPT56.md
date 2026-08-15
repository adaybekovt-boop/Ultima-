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

The hook is now a MixinExtras `@WrapOperation` that suppresses the constructor call, retains the
supplied operation chain, and invokes it on the first non-cube read. Runtime output is unchanged for
vanilla finite AABBs, and inner wrappers represented by `original` still participate.

Remaining compatibility risk: the vanilla private final `entityShape` is temporarily `null`, and an
outer wrapper can observe that constructor-time sentinel before Ultima invokes its retained chain.
No mechanism can both skip the immediate operation and provide every outer wrapper its immediate
result. The module is therefore disabled by default.

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

The final integration also moved the constant-time cursor eligibility check before the section
scan. Degenerate or int-overflowing volumes now fail open before chunk/palette work, preventing an
enormous query that vanilla exhausts immediately from triggering an unbounded pre-check.

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

The old script also defaulted to 2000 sprint ticks while the report described 2500 without recording
the required environment override. Its final table implies about 97.4 and 113.0 uncapped ticks/s
from the listed run means, not the reported 96.9 and 112.3; the 96.9 value instead matches the older
10.32 ms three-run baseline documented in history. The headline was stale relative to its own table.

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
with thousands of hash probes. Direct lookup is now capped at 1024 candidates and also falls back
when candidates exceed the total loaded section count. The no-longer-needed `sectionIds` shadow was
removed.

#### M4 — module configuration could silently misreport behavior (fixed)

- A typo such as `enabled` or `treu` was parsed as `false` without warning.
- `collision_shell_skip=true` with `cursor_step=false` loaded a no-op shell Mixin but counted it as
  enabled because `Cursor3D` lacked `InteriorOnlyCursor`.
- Security failures while writing the config could escape despite the documented fallback.

Boolean parsing is now strict, invalid values retain the declared default with a warning, module
dependencies affect both Mixin application and enabled count, unknown module packages fail closed,
and write-time security failures are handled. Dependencies are registry data rather than
string-special-cased. Existing explicit configs are preserved; users of prerelease builds should
review old generated values because changing defaults does not override explicit `true`.

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
| `block_collision_shape` | 88/100 | 58/100 | 20/100 | MEDIUM | **DISABLE BY DEFAULT** |
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
allocation and now retains the wrapped operation chain, but it has not been isolated and cannot
compose perfectly with wrappers outside Ultima or early field readers. Keep it opt-in.

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

- Replaced the ineffective constructor expression modifier with a deferred `@WrapOperation` whose
  retained operation preserves inner wrapper chains.
- Added saturated section-volume arithmetic and packed-coordinate bounds.
- Replaced the global-count performance heuristic with a hard 1024-candidate cap.
- Removed the unnecessary `sectionIds` Mixin shadow.
- Added vanilla fallbacks for inverted and index-overflowing cursors.
- Preserved the vanilla cursor index during interior-only traversal.
- Cached cursor eligibility at construction to avoid hot-loop arithmetic.
- Checked cursor eligibility before shell palette scanning.
- Disabled `entity_section_lookup`, `block_collision_shape`, and `collision_shell_skip` by default.
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
- strict config boolean parsing, registry dependency, unknown-module, enabled-count, and
  risky-default assertions.

The test is dependency-free and runs as a Java verification task, avoiding a benchmark/test
framework dependency merely for arithmetic differential checks.

## Release artifact

`build/libs/ultima-0.1.0.jar` is the production mod JAR from the final clean build. Archive inspection
confirmed `fabric.mod.json`, `ultima.mixins.json`, `Ultima.class`, config/util classes, and all four
compiled Mixin classes. It is not the separate `ultima-0.1.0-sources.jar` and contains compiled
`.class` entries rather than `.java` sources.

## Release-candidate benchmark rerun

After final code integration, the corrected harness ran three alternating rounds of all-disabled,
release-default, and all-enabled configurations. Each fresh JVM performed a 1000-tick warmup followed
by a 2500-tick measured sprint. The world was recreated each time and all module states were explicit.

| Round | All disabled | Release default (`cursor_step`) | All enabled experimental |
|---|---:|---:|---:|
| A | 8.82 ms/tick | 8.21 ms/tick | 7.25 ms/tick |
| B | 8.85 ms/tick | 8.97 ms/tick | 7.42 ms/tick |
| C | 8.91 ms/tick | 8.73 ms/tick | 7.57 ms/tick |
| **Mean** | **8.86 ms/tick** | **8.64 ms/tick** | **7.41 ms/tick** |
| Range | 8.82–8.91 | 8.21–8.97 | 7.25–7.57 |

On this synthetic collision-heavy load, the shipped default was **2.5% lower mean tick cost** than
all-disabled. Its ranges overlap and one default run was slower than every baseline run, so this is
directional evidence only, not a robust release performance claim. All-enabled experimental reduced
mean tick cost by **16.3%**, with non-overlapping ranges, but includes three modules intentionally
disabled for compatibility/correctness reasons.

The corresponding console values are uncapped sprint throughput, not normal Minecraft TPS. All nine
runs loaded the expected 0/1/4 enabled-module states, killed 88 cows, shut down cleanly, and had no
exception or Mixin failure. The recurring `No key layers in MapLike[{}]` line occurred equally in all
configurations and predates the Mixins.

## Benchmark caveats

- The historic nanosecond microbenchmarks are not in the repository. There is no way to check
  warmup, forks, dead-code elimination, blackhole use, CPU governor, core pinning, JVM flags, or raw
  variance. They are directional hypotheses, not accepted measurements.
- A 1.60 ns operation claim is sensitive to inlining and benchmark-loop optimization; it does not
  independently imply TPS/FPS gain.
- The original four-baseline/three-optimized sample is unbalanced, has no standard deviation or
  confidence interval, and provides no raw logs.
- The historic script/report disagree on 2000 vs 2500 measured ticks, and the reported throughput
  values disagree with the final ms/tick table.
- The staged 9.80 → 9.62 → 8.85 sequence changes both feature set and injection mechanism. It does
  not isolate interactions among all four final modules.
- `/tick sprint` reports uncapped throughput, not a playable server rate above Minecraft's 20 TPS
  target. “112 TPS” was misleading terminology.
- A fixed seed and 88 surviving cows do not prove identical simulation histories. AI, combat,
  deaths, item merging, UUID/random initialization, asynchronous chunk completion, and GC can differ.
- The corrected harness alternates sides and warms each fresh JVM, but does not randomize order,
  pin CPU frequency/cores, isolate the host, or retain allocation/JFR profiles.
- The 100-sample `/tick query` P95/P99 output remains too small for tail-latency claims.
- No benchmark in this branch supports a **reliable** FPS, shader, or 1%-low claim. The RTX 3090
  stationary A/B is valid hardware data and is inconclusive (−0.28% average FPS). See
  `CLIENT_PERFORMANCE_REPORT.md`.

## Remaining real-PC validation needed

1. Current Fabric builds of Sodium, Iris, and Lithium on Minecraft 26.2. Static incompatible-mod
   gates exist; they have not been runtime-tested because the measured profile's mods folder was
   empty.
2. Collision/physics mods and custom dynamic-shape blocks, including fences/walls, moving pistons,
   world-border-adjacent movement, vehicles, step-up, suffocation, supporting-block selection, and
   blocks that mutate collision state from callbacks.
3. Client scenes the first RTX 3090 A/B did not cover: moving camera, update-heavy/redstone, high
   render distance / 1440p, entities, block entities, particles, water/transparency, and shader
   OFF/ON visual captures. Repeat with at least 6 balanced disabled-vs-default pairs.
4. Long dedicated-server and integrated-server runs across overworld/nether/end unload/reload, with
   heap/GC and tick percentiles. No cache leak is expected, but allocation savings must be profiled.
5. Isolated JMH or equivalent forked benchmarks checked into the repository for section lookup,
   shape allocation, and cursor stepping, followed by async-profiler/JFR confirmation in the game.
6. Do not treat the pair-1 0.1% low (−17.63%) as noise; investigate it on a repeat run.

## Final merge recommendation

**Do not merge the original optimization head without this review branch.**

With these fixes, merge is reasonable only as a guarded experimental release:

- keep `cursor_step` enabled by default;
- keep `entity_section_lookup`, `block_collision_shape`, and `collision_shell_skip` disabled by
  default;
- keep the client chunk preparation modules enabled only on the vanilla renderer and automatically
  disabled with Sodium/Iris; the first real GPU A/B is inconclusive (−0.28% average FPS) and is not
  a performance claim;
- do not publish the historic 13.7%/15.9% figures as results for the reviewed code;
- do not claim shader/FPS gains, 2× FPS, or a noticeable client FPS improvement;
- do not treat visual equivalence as PASS until water/transparency, block-entity, particle, and
  shader captures exist.

Promoting either opt-in module to default should require the real-modpack tests above and new,
reproducible per-module evidence.
