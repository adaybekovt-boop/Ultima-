# Ultima architectural audit — second pass

Scope: a system-level review of the four optimizations on this branch and of the subsystems they do
not touch. The question driving it is not "which method is slow" but "where is the game doing
unnecessary work as a system".

This pass changed one line of behaviour and rejected everything else it considered. That is the
intended outcome: the first pass found a real architectural win, and the remaining cheap ideas are
either already rejected for good reasons or need evidence this environment cannot produce.

## Environment limits of this pass

These bound every claim below and are stated first so nothing here is mistaken for a measurement.

- **No Gradle build.** The network policy blocks `maven.fabricmc.net` and `piston-meta.mojang.com`
  (gateway returns 403 to CONNECT), so Loom cannot resolve Minecraft, and `scripts/check.sh` cannot
  run. Only JDK 21 is present; the project targets 25.
- **No vanilla sources.** `.agent/vanilla-src` does not exist and `scripts/bootstrap.sh` cannot
  populate it. Vanilla behaviour below is reasoned from the documented algorithms and from the first
  pass's report, **not** from reading 26.2 sources. Anything depending on an exact 26.2 signature is
  flagged where it matters.
- **No benchmark.** `scripts/bench-server.sh` needs a running dedicated server. No new timing number
  appears in this audit or in `PERFORMANCE_REPORT.md`.
- **What was verified instead:** an independent differential harness (written from the vanilla
  algorithms rather than from Ultima's own test model), and a stub-based compile of all of
  `src/main/java` and `src/test/java`. See *Verification performed*.

## 1. Did the first pass optimize the right bottlenecks?

**Largely yes, with one systematic caveat.**

The profile-first approach was right, the reasoning is unusually careful, and the biggest change
(`collision_shell_skip`) is a genuine architectural finding rather than loop tuning — it removes a
class of work instead of making an operation faster, and it improves every collision caller at once.
Two designed optimizations were discarded on measurement rather than kept on intuition, which is the
correct instinct.

The caveat is that **all four optimizations live in one subsystem**, and that subsystem was selected
by a single JFR profile of a single synthetic load. Ultima is currently an entity-movement collision
mod, not a general performance mod. The load that chose the targets was:

superflat, fixed seed, no players, 1089 pre-force-loaded chunks, 1100 summoned entities with no
pathfinding targets, `advance_time false`.

Each of those properties suppresses a subsystem that dominates real servers:

| Benchmark property | Subsystem it hides |
|---|---|
| all chunks force-loaded up front | chunk generation, load, save, serialization, lighting |
| no players connected | `ChunkMap` tracking, entity trackers, packet encoding, view-distance churn |
| superflat, uniform sections | palette complexity, block-entity density, redstone |
| summoned mobs, no goals to satisfy | pathfinding (`PathFinder`, `Node`), POI/villager work |
| `advance_time false`, low random tick | scheduled ticks, crop/fluid ticking |

So the profile's finding that entity ticking is 94.3% of the tick is a property of the harness, not
of Minecraft. It is still a *valid* target — entity-dense areas are a real server problem — but it
is one workload, and the portfolio is fitted to it. Ranked candidate lists derived from that profile
inherit the same bias.

**Two reporting problems follow from this and should be fixed regardless of any code change.**

**(a) The headline number does not describe the shipped default.** `UltimaModules` ships
`entity_section_lookup` and `collision_shell_skip` **disabled by default**. The report's headline
(−13.7% mean tick time, +15.9% TPS) is for all four modules on. `bench-server.sh` writes every
module explicitly to `true` or `false`, so it never measures the default either. The staged table has
no row for `cursor_step` + `block_collision_shape` alone — which is what a user actually installs.
Since `collision_shell_skip` alone accounts for −9.7% of the −13.7%, **the default configuration's
gain is unmeasured and is certainly a small fraction of the headline.** Making the modules opt-in was
the right safety call; leaving the headline attached to a configuration nobody gets by default is
not.

**(b) The report describes a fallback the code no longer has.** For `entity_section_lookup` the
report states the vanilla path runs "if the candidate volume exceeds 1024 sections *and* exceeds the
total number of existing sections". The code implements only the first half:

```java
if (candidates > ULTIMA_DIRECT_LOOKUP_BUDGET) { return; }
```

The `sections.size()` comparison was dropped when the probe cap was introduced. The behaviour is
still safe — it is a pure fail-open — but the documented rationale no longer matches the code, and
the dropped half was the part that protected against the module's actual worst case (see §3).

## 2. Module-by-module review

### `cursor_step` — **KEEP**

- **Root cause:** genuine. Vanilla re-derives `x/y/z` from a running index with four integer divides
  by non-constant divisors, in the innermost loop of block collision iteration. Non-constant divisors
  cannot be strength-reduced by the JIT, so these are hardware divides.
- **Frequency:** highest in the mod — once per visited block position, ~109M positions per 800 ticks
  under the first pass's load.
- **Semantic equivalence:** **independently confirmed.** 27,000,000 positions across all volumes
  1..24³, comparing `x`, `y`, `z`, `index` and `getNextType()` at every step against a from-scratch
  vanilla model: zero mismatches.
- **Ordering / lifecycle:** the carry is valid only because the cursor advances one position at a
  time from index 0. `CursorMath.canUseCarry` correctly refuses degenerate and index-wrapping volumes
  so vanilla's divide-by-zero and wrap behaviour is preserved rather than "fixed".
- **Mod interaction:** a cancelling `@Inject` at `HEAD` on an 8-line method. Acceptable, and the
  narrowest available hook. `index` is still advanced so other injectors observe the same value.
- **Fragility introduced:** vanilla's cursor is *stateless* — `x/y/z` are pure functions of `index`,
  so any external write to `index` self-heals on the next `advance()`. Ultima's cursor is stateful;
  an external write to `index` desyncs the coordinates silently and permanently. Nothing in vanilla
  does this, but it is a property that was there and is now gone. Worth a comment, not a change.
- **Shader/render:** none.
- **Benchmark representativeness:** good. This cost is present in every tick and scales with
  collision volume, exactly as the benchmark exercises it.

### `collision_shell_skip` — **KEEP, IMPROVED** (one fix applied this pass)

- **Root cause:** genuine and architectural. The shell exists only to catch `hasLargeCollisionShape`
  blocks (fences, walls, dynamic-shape blocks) at face positions and `MOVING_PISTON` at edge
  positions; corners are already skipped by vanilla. Everything else in the shell is read and
  discarded.
- **Semantic equivalence:** **independently confirmed**, and over a wider domain than Ultima's own
  test. 8,096 volumes — all of 1..16³ plus 4,000 random volumes with dimensions up to 89, versus
  Ultima's own coverage of 1..12³ — 330,953,490 interior positions emitted. The interior cursor
  emits exactly the `TYPE_INSIDE` subsequence, in order, with matching `index`, never emits a shell
  position, leaves `index == end` on exhaustion, and stays exhausted. Zero mismatches.
- **Why the payoff is real:** for the volume shapes entity movement actually produces, the shell is
  most of the work — and it is *worst* for the cheapest query:

  | volume | positions | interior | shell |
  |---|---|---|---|
  | 3×3×3 (`findSupportingBlock`, thin box under an entity) | 27 | 1 | **96.3%** |
  | 3×4×3 (feet on a block boundary) | 36 | 2 | 94.4% |
  | 3×5×3 (walking sweep) | 45 | 3 | 93.3% |
  | 4×5×4 (sweep straddling a boundary) | 80 | 12 | 85.0% |
  | 5×5×5 | 125 | 27 | 78.4% |

  `findSupportingBlock` runs per on-ground entity per tick and is the 96.3% case, which is why this
  change lands so hard.

- **Ordering assumptions:** sound. Callers collect into lists and the subsequence order is preserved.
- **Lifecycle assumption — the real risk, correctly handled.** `BlockCollisions` is a lazy
  `AbstractIterator`, but the palette decision is taken once in the constructor. If the world changes
  between construction and drain, Ultima's decision is stale where vanilla would have read the new
  state. Every vanilla caller drains within the same call chain, so this needs a mod holding the
  `Iterable` across a block change. **Shipping this module default-off with exactly this reason in
  its description is the correct call** and should not be reversed without evidence.
- **Worst case — under-documented.** `maybeHas` is O(1) only for a uniform section; otherwise it
  scans the palette calling the predicate. So a query pays up to ~8 palette scans plus ~4 chunk
  lookups **before** visiting a single position, and gets nothing back whenever any covered section
  contains a fence, a wall, or a dynamic-shape block. That is not an exotic case: it is villages,
  fenced farms, and fence/wall-built mob farms — i.e. **precisely the entity-dense builds where
  collision cost matters most**. The measured 95% eligibility rate is a property of a superflat
  bench world with near-uniform palettes and should not be quoted as a general figure.
- **Benchmark representativeness — a real gap.** The benchmark measures callers that *drain* the
  iterator (`Entity.collide`, `findSupportingBlock`). The other major caller family,
  `noCollision`/`isUnobstructed`, stops at the **first** collider. For those, vanilla may finish after
  one position while Ultima has already paid the whole pre-check. That is a small constant-factor
  regression on a common call, invisible to the current benchmark, and it is not mentioned anywhere.
  It does not outweigh the win — but it should be stated.
- **Fix applied this pass — eager unbounded pre-check.** The condition was

  ```java
  if (this.ultimaShellIsIrrelevant() && this.cursor instanceof InteriorOnlyCursor interiorOnly)
  ```

  Java evaluates left to right, so the palette scan ran **before** checking whether the cursor could
  use the answer. The scan iterates every section the box covers, with a chunk lookup per `(x,z)`
  column, and is unbounded in the size of the box. For an enormous volume the cursor refuses the
  carry (`canUseCarry` fails once `w*h*d` overflows `int`) and keeps the vanilla traversal — so the
  scan was pure waste. Worse, vanilla's `end = width*height*depth` is computed in `int`; when that
  product overflows to `0`, vanilla's `advance()` returns `false` immediately and the query is
  instant, while Ultima would first scan on the order of 10¹³ sections. **A query vanilla answers
  instantly could fail to finish.** The condition now asks the cheap, constant-time questions first:

  ```java
  if (this.cursor instanceof InteriorOnlyCursor interiorOnly
          && interiorOnly.ultimaCanVisitInteriorOnly()
          && this.ultimaShellIsIrrelevant())
  ```

  This is a pure fail-open with **no effect on any query the optimization currently helps** — every
  realistic collision volume stays carry-eligible (a 512³ region is still only 1.4×10⁸ positions,
  well inside `int`). It needed no tuning constant: the cursor already knows the answer, it simply
  was not being asked. A budget constant would have been the worse fix, since it would have traded
  away the large-volume win to bound a case the existing eligibility test already identifies exactly.

### `entity_section_lookup` — **KEEP (default-off is correct)**

- **Root cause:** genuine and the sharpest algorithmic finding in the mod. `SectionPos.asLong` packs
  x into bits 42–63, z into 20–41, y into 0–19, so the `sectionIds` subtree range vanilla queries for
  one x — `asLong(x,0,0)` to `asLong(x,-1,-1)` — spans *every* z and *every* y at that x. Vanilla
  therefore walks every non-empty entity section in a whole chunk strip and discards the rest with an
  `if`. Cost scales with how populated the world is, not with the query.
- **Semantic equivalence:** **independently confirmed.** 20,000 randomised section populations and
  query windows straddling zero on both y and z, 66,570 section visits, comparing visited set *and*
  order against a from-scratch model of the vanilla sorted-set scan: zero mismatches. The subtle part
  — that masking maps negative coordinates above every non-negative one, so the non-negative half
  must be visited first — is correct.
- **Ordering:** correctly treated as observable. Callers collect into a `List` and some resolve ties
  by first-encountered (a mob choosing between equidistant targets), so order is behaviour, not an
  implementation detail. The first pass was right to insist on this.
- **Mod interaction — the reason for default-off.** This is a cancelling `@Inject` at `HEAD`, i.e. a
  full method replacement, on the single method entity-optimization mods are most likely to target.
  If such a mod replaces the *storage* rather than the *query*, Ultima's `@Shadow` on `sections`
  could read a field that is no longer authoritative. Correct call, correct default.
- **Worst case — regressed when the fallback was simplified.** With a hard cap only, a query whose
  volume is under 1024 sections but whose world holds far fewer populated sections does more work
  than vanilla: e.g. a 512-candidate window in a sparsely populated world costs 512 hash probes
  where vanilla walks perhaps 20 tree nodes. The dropped `sections.size()` half of the condition was
  what covered that. Not changed here — it is a tuning question that needs the benchmark — but the
  report should not claim a guard the code does not implement.
- **Benchmark representativeness — poor, and the report says so.** The bench world concentrates
  entities in ~33 chunk columns, so the strip vanilla walks is already short. The report is
  appropriately explicit that the end-to-end figure understates this module and that its isolated
  gain is 8–21×. That honesty is right; the consequence is that this module's real value is
  **unmeasured under any realistic load.**

### `block_collision_shape` — **KEEP, with an unimplemented compatibility improvement**

- **Root cause:** genuine. The collider's voxel shape is built in the constructor but read only when
  a block's collision shape is neither empty nor a full cube. Entities moving through air and full
  blocks never reach that branch, so an `ArrayVoxelShape` plus three `DoubleArrayList` are allocated
  and discarded per query.
- **Semantic equivalence:** the shape is produced by the same call on the same immutable box, at most
  once per query. No control flow changes. Sound.
- **Mechanism:** `@ModifyExpressionValue` is a good choice — stackable, unlike `@Redirect`.
- **Compatibility defect (not fixed here).** The `@WrapOperation` on `Shapes.create` **never calls
  `original`**:

  ```java
  private @Nullable VoxelShape ultimaSkipEagerVoxelisation(final AABB box, final Operation<VoxelShape> original) {
      return null;   // deliberately does not call the operation
  }
  ```

  Wrappers chain, so if another mod also wraps that expression, its wrapper is inside `original` —
  and dropping `original` **silently discards that mod's modification**. Ultima then calls
  `Shapes.create(this.box)` directly at read time, bypassing it a second time. A mod adjusting the
  collider shape would be silently ignored, which is the failure mode the guardrails' "do not assume
  another mod is absent" rule exists to prevent.

  The fix is to store `original` and invoke it lazily instead of discarding it. The cost objection
  that killed `@WrapOperation` for `collision_shell_skip` — `Operation.call(Object...)` boxing into a
  varargs array — does not apply here: this call site runs **at most once per query** on the rare
  branch, not once per visited position. Storing the `Operation` in a field is a field write per
  query and no allocation.

  **Not implemented this pass** because it depends on MixinExtras `Operation` remaining callable
  after the wrapped method returns, and that cannot be verified without a build. It is the single
  highest-value follow-up once a build is available.

- **Secondary risk:** the shadowed `entityShape` field is left `null` on the object. Any other mod
  reading it outside `computeNext` gets `null`. Vanilla reads it only there, so this is latent, not
  active — but it is a null where a mod would reasonably expect a value.

## 3. Are optimizations solving symptoms instead of root causes?

Mostly no — but there is one instance, and it is the one the first pass created.

`collision_shell_skip` asks the same question, of the same few sections, **once per query**. With
~3 queries per entity per tick and up to ~8 sections per query, the first pass's own load implies on
the order of 26,000 palette interrogations per tick, nearly all recomputing an identical answer for
an identical section whose contents did not change. **The pre-check is itself repeated derived-state
computation** — the exact pattern the guardrails list first under "preferred optimization classes".

The first pass considered and rejected the obvious cure (a maintained per-section index) for
excellent reasons: `recalcBlockCounts()` runs from only one of three constructors,
`read(FriendlyByteBuf)` restores some counters and not others, and `UpgradeData` mutates
`PalettedContainer` through the public `getStates()` — specifically to fix up fence and wall
connectivity, which is the very block class the index would track. An under-counting index lets an
entity walk through a fence. That rejection is correct and should stand.

**I am not proposing to revisit it.** A memoisation keyed on palette identity would need a change
counter `PalettedContainer` does not expose, and inventing one means maintaining state across every
block write — the same lifecycle problem in a new place. The honest conclusion is that the
redundancy is real, known, and currently not safely removable. It is recorded here so a future pass
does not rediscover it and reach for the unsafe version.

## 4. Duplicated work across subsystems

The first pass identified the biggest instance and correctly declined to fix it. Adding the
containment analysis it said was needed:

**One entity movement triggers three overlapping block-volume traversals** — the primary sweep in
`Entity.collide`, the step-up rescan, and `findSupportingBlock` via `checkSupportingBlock`.

- **Plain containment fails.** The step-up volume is the entity box expanded by `maxUpStep()`
  (0.6) **upward**, which is not inside the primary sweep volume unless `vec.y >= 0.6`. The first
  pass was right to demand a proof before coding; the proof does not exist in that form.
- **But a superset is already known-safe, by vanilla's own construction.** `Entity.collide` computes
  the *entity* collision list once from `aabb.expandTowards(vec)` and passes that same list to all
  three `collideBoundingBox` calls, including ones using smaller boxes. Vanilla therefore already
  relies on `Shapes.collide` being insensitive to extra non-overlapping shapes: a shape that does not
  overlap in the other two axes clips the delta by zero. So the tractable design is not containment —
  it is **scan the union volume once and pass the superset**, which is the discipline vanilla already
  applies to entity shapes and simply does not apply to block shapes.
- **Why it still should not be built yet.** Two blockers. First, step-up only runs when
  `maxUpStep() > 0` *and* a horizontal collision occurred, so for the free-moving mobs in the bench
  load it rarely fires — the "3 queries per entity per tick" is mostly primary sweep plus
  `findSupportingBlock`, and those two are the pair a union does **not** merge cleanly, because
  `findSupportingBlock` needs the `BlockPos` of the supporting block rather than a moved shape. The
  cheap-looking two thirds is the wrong two thirds. Second, it requires a broad mixin on
  `Entity.collide`, core physics, which is a far larger compatibility surface than anything Ultima
  currently touches.

Verdict: **highest-value remaining candidate, correctly deferred, and now with the analysis needed
to scope it.** Do not attempt it without a build and the benchmark.

## 5. Subsystems Ultima has not examined at all

The requested scan, with honest coverage:

| Subsystem | Ultima coverage | Note |
|---|---|---|
| block collision | 3 of 4 modules | well mined; diminishing returns |
| entity spatial queries | 1 module (default off) | `Level.getEntities` at 15.2% inclusive is **unaddressed** in the default build |
| chunk lifecycle | none | hidden by the benchmark's force-loading; dominant on real servers |
| tick scheduling | none | unexamined |
| invalidation / rebuild fan-out | none | mostly render-side |
| allocation-heavy paths | 1 module | 130 GCs in 193 s says there is more here |
| repeated derived-state | none removed; one **added** (§3) | |
| render-side CPU prep | none, deliberately | correct — no GPU to validate against |
| resource/model lookup | none | unexamined |
| networking / server work | none | `ChunkMap.tick` noted but untouched |
| integrated client-server | none | unexamined |

The first pass's decision to leave the renderer alone deserves explicit endorsement. 26.2 replaced
the renderer wholesale, the per-frame costs that matter are exactly the surfaces Sodium and Iris
replace or observe, and there is no GPU here to validate against. Under "better to lose 3 FPS than
break shaders", touching it would have been the wrong call.

## 6. Architectural patterns that will become fragile

These are cheap to fix now and expensive later. None is fixed in this pass — each needs a build.

1. **Unknown module ⇒ enabled.** `UltimaConfig.isEnabled` returns `true` for any key not in the
   registry, and `UltimaMixinPlugin` derives the key from the mixin's *package segment*. Rename or
   mistype a package and the module silently becomes unconditionally on — the kill switch fails
   **open in the dangerous direction**. Fail-open to vanilla is the guardrails' rule; this is
   fail-open to *optimized*. A startup assertion that every entry in `ultima.mixins.json` maps to a
   declared module would catch it.
2. **Cross-module dependencies hardcoded as string comparisons.** The `collision_shell_skip` →
   `cursor_step` dependency lives inside `isEnabled` as a literal string compare. With N modules this
   becomes an ad-hoc web inside one method. It belongs in the `Module` record as declared data.
   (The runtime is already double-protected: if `cursor_step` is off the mixin is not applied, so the
   `instanceof InteriorOnlyCursor` check fails and the shell module no-ops. Good defence in depth.)
3. **The regression test duplicates the production algorithm.** `CursorModel` re-implements the
   carry and interior stepping rather than exercising the mixin, so the test cannot catch divergence
   between itself and `Cursor3DMixin` — the two can drift silently and the suite stays green. This is
   why this pass wrote an *independent* harness. As more modules land, this pattern manufactures
   false confidence. The fix is to extract the stepping into a pure state machine both use; that
   touches the hottest loop in the game, so it needs the benchmark, not a guess.
4. **Cross-module state on a shared vanilla class.** `Cursor3D` now carries four `@Unique` fields, one
   module's mixin owning state a *different* module's mixin drives through `InteriorOnlyCursor`. It
   is correct today and the interface is the right mechanism, but it is the seam where a third
   collision module would create a three-way coupling with no owner.

## Ranked verdict

| Rank | Item | Verdict |
|---|---|---|
| 1 | `cursor_step` | **KEEP** — narrowest hook, exactly verified, highest call frequency |
| 2 | `collision_shell_skip` | **KEEP / IMPROVED** — eager unbounded pre-check fixed this pass; default-off is correct |
| 3 | `entity_section_lookup` | **KEEP** — best algorithmic finding; default-off correct; report text stale |
| 4 | `block_collision_shape` | **KEEP** — but see the discarded-`Operation` defect |
| 5 | Lazy `Operation` in `block_collision_shape` | **IMPROVE** — highest-value follow-up once a build exists |
| 6 | Measure and publish the **default** configuration | **IMPROVE** — headline currently describes a non-default build |
| 7 | Restore/justify the `entity_section_lookup` size guard | **IMPROVE** — code and report disagree |
| 8 | Module-registry fail-open direction (§6.1) | **IMPROVE** — small, safety-relevant |
| 9 | Union-volume collider reuse across the 3 traversals | **NEW HIGH-VALUE CANDIDATE** — analysis in §4; needs build + benchmark |
| 10 | `Level.getEntities` / `getEntityCollisions` predicate path | **NEW CANDIDATE** — 15.2% inclusive, wholly unaddressed by default |
| 11 | Chunk lifecycle, tick scheduling, networking | **NEW CANDIDATE** — unexamined, and hidden by the current benchmark |
| 12 | Maintained per-section large-shape index | **REJECT** — first pass's lifecycle analysis stands |
| 13 | Per-tick block/shape cache across traversals | **REJECT** — palette read is cheaper than a validated cache lookup |
| 14 | Profiler `ThreadLocal` overhead | **REJECT** — <2% against Tracy/profiler breakage |
| 15 | Client renderer / extract phase | **REJECT** — no GPU to validate; Sodium/Iris surface |
| 16 | Further micro-optimization inside the collision loop | **REJECT** — diminishing returns; the remaining wins are structural |

## Verification performed

Full `./gradlew build` was **not possible** (see *Environment limits*). What was actually run:

1. **Independent differential harness** — vanilla models written from the documented algorithms, not
   copied from Ultima's test model, so a shared error could not hide a divergence:
   - `cursor_step`: 27,000,000 positions over all volumes 1..24³ — `x`, `y`, `z`, `index`,
     `getNextType()` compared at every step. **0 mismatches.**
   - `collision_shell_skip`: 8,096 volumes (all of 1..16³ plus 4,000 random up to 89³),
     330,953,490 interior positions. Exact `TYPE_INSIDE` subsequence, order, and `index`; never a
     shell position; `index == end` on exhaustion; stays exhausted. **0 mismatches.**
   - `entity_section_lookup`: 20,000 randomised populations and query windows straddling zero on y
     and z, 66,570 visits. Visited set and order identical. **0 mismatches.**
2. **Stub-based compile gate** — 40 stub classes for the Minecraft, Mixin, MixinExtras, fastutil,
   Fabric and slf4j types the mod references, against which **all of `src/main/java` and
   `src/test/java` compile cleanly**, before and after this pass's edit. This type-checks Ultima's own
   sources and signatures; it does **not** validate mixin targets against real 26.2 bytecode.
3. **Ultima's own suite** — `dev.ultima.review.ForensicRegressionTest` executed against a faithful
   `SectionPos` packing stub: **passes**, including the test added this pass.

Still required on a machine with a build, and unchanged from the first pass's list: mixin target
resolution against real 26.2, a dedicated-server A/B **including a default-configuration run**, and
all client/shader/modpack testing.
