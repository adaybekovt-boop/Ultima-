# Ultima architectural audit — second pass

Reviewer: independent second-pass review of the four optimization modules on
`claude/ultima-arch-audit-pass2-nvxxct`, which is based on the optimization branch
`cursor/implement-safe-performance-optimizations-25a5`.

The question this pass asks is not "is this method slow" but "where is Minecraft doing unnecessary
work at the system level". The first pass was a good one — the module boundaries are clean, the
config gating is real, and the reasoning recorded in `PERFORMANCE_REPORT.md` is unusually careful.
This pass therefore concentrates on the places where the *system-level* story and the *implemented*
story diverge.

Three of those were found, and all three are fixed on this branch. One is a correctness-adjacent
performance inversion, one is a Mixin that does not do what it is documented to do, and one is a
silent inter-module dependency.

---

## 0. What this environment could and could not verify

This has to come first, because it bounds every claim below.

**Could not be done.** The session's egress proxy denies `maven.fabricmc.net` and Mojang's
distribution hosts (verified: `403` to `CONNECT`, `curl "$HTTPS_PROXY/__agentproxy/status"`).
Consequently:

- `./gradlew build` / `bash scripts/check.sh` **could not be run**. Loom cannot resolve
  `net.fabricmc.fabric-loom:1.17-SNAPSHOT`.
- `.agent/vanilla-src` **could not be generated**, so **no claim in this document was checked
  against real 26.2 decompiled source.** Vanilla behaviour below is reconstructed from
  long-stable class contracts plus the previous pass's report. Where the two agree I treat it as
  corroborated; where a conclusion depends on an exact vanilla detail I say so explicitly.
- `scripts/bench-server.sh` **could not be run** — it needs a real server jar. No end-to-end tick
  timing was produced in this pass, and none is claimed.

**Could be done.** JDK 25 was installed from the distribution archive and Maven Central *is*
reachable, which was enough to verify the parts that do not need Minecraft:

- **Differential harnesses**, now committed at `tools/audit/` and runnable with
  `bash tools/audit/run.sh`. They compare a transcription of each Ultima Mixin against a reference
  port of the vanilla algorithm. This reproduces — and extends — evidence the first pass produced
  but did not commit.
- **A stub-based type check** of the whole of `src/main/java` against hand-written signatures for
  the Minecraft, Fabric and Sponge Mixin surfaces Ultima touches, with the *real* MixinExtras
  0.5.4, fastutil and jspecify jars from Maven Central. It compiles clean under `-Xlint:all`. This
  is not a substitute for the Gradle build: it validates Ultima's own syntax, imports, generics and
  handler signatures, **not** that the Mixin targets exist in 26.2.
- **Reading MixinExtras 0.5.4's actual source** from Maven Central, which is what settled finding
  A-2 below.

**`bash scripts/check.sh` must be run on a machine with Fabric maven access before this branch is
released.** That is the single outstanding verification gate.

---

## 1. Ranked findings

### KEEP — sound, verified, leave alone

| Module | Why it survives review |
|---|---|
| `cursor_step` | Root cause correctly identified and the replacement is provably equivalent. Re-verified this pass: **0 mismatches over 40,216 volumes / 4,963,671 positions**, including every degenerate span from 1×1×1 to 6×6×6. Narrowest available hook on an 8-line method. |
| `entity_section_lookup` (core algorithm) | The ordering argument is subtle and **correct**. Re-verified: **0 visited-set and 0 visit-order mismatches over 24,000 queries** against a port of the vanilla tree scan, with boxes straddling zero on both y and z. The complexity win is real and large (408 → 2.2 work units per query in a dense world). |
| `collision_shell_skip` (interior traversal) | The interior cursor emits **exactly** the `TYPE_INSIDE` subsequence, in order — verified, 0 mismatches. The decision to query section palettes per query rather than maintain an index is the right call, and the `UpgradeData` reasoning that killed the maintained index is correct and worth preserving in writing. |

### IMPROVE — implemented on this branch

| # | Module | Finding | Status |
|---|---|---|---|
| A-1 | `entity_section_lookup` | Fallback guard does not bound the worst case. A wide query against a sparse world runs **~50× slower than vanilla**. | **Fixed** |
| A-2 | `block_collision_shape` | `@ModifyExpressionValue` does not prevent the expression from being evaluated, so the allocation it claims to remove **still happens**. | **Fixed** |
| A-3 | `collision_shell_skip` ↔ `cursor_step` | Undeclared cross-module dependency: disabling `cursor_step` silently turns `collision_shell_skip` into pure overhead. | **Fixed** (ordering + warning) |
| A-4 | packaging | `fabric.mod.json` hard-depends on Fabric API, which Ultima never uses. | **Fixed** |

### NEW HIGH-VALUE CANDIDATE — not implemented, deliberately

| # | Candidate | Class | Why not now |
|---|---|---|---|
| B-1 | Hoist the shell-eligibility answer across the ~3 collision traversals per entity movement | MACRO | Highest remaining leverage; attacks the fan-out *and* the per-query palette cost together. Needs a containment proof and a real build to measure. See §4. |
| B-2 | Per-entity cached collision predicate in `getEntityCollisions` | MICRO/MESO | Sound, but small, and unmeasurable here. Previous pass ranked it #1; I rank it below B-1. |
| B-3 | `ChunkMap.tick` per-tracked-entity `SectionPos`/`ChunkPos` allocations | MESO | Real allocation reduction, off the critical path. Reasonable next. |

### REJECT — do not pursue

| Candidate | Reason |
|---|---|
| Shared per-tick block-state cache across traversals | Previous pass's arithmetic is right: a validated cache lookup costs about what a palette read costs, and it buys an invalidation surface that can corrupt physics. Redundancy is in *positions visited*, not in per-read cost. |
| Maintained per-section large-shape index | Previous pass's lifecycle analysis is right and should be treated as settled. `UpgradeData` mutates `PalettedContainer` through `getStates()` specifically to fix up fence/wall connectivity — the exact class the index would track. An under-counting index lets an entity walk through a fence. |
| Rewriting `Profiler.get()` plumbing | Correctly rejected under the guardrails' priority order. |
| Client renderer work | Correctly rejected. No GPU here either; that has not changed. |
| Multi-entry chunk cache in `BlockCollisions` | Correctly rejected on profile evidence. But see A-5 in §3 — the shell check *adds* uncached chunk lookups, which changes this calculus slightly. |

---

## 2. The three findings, in detail

### A-1 — `entity_section_lookup`'s guard bounds the wrong thing

**Root cause.** The guard was

```java
if (candidates > 1024 && candidates > this.sectionIds.size()) return; // fall back to vanilla
```

Both clauses must hold to fall back. So **any query covering ≤ 1024 candidate sections always takes
the direct-probe path**, no matter how few entity sections exist in the world. That is exactly
backwards: the direct path wins when the world is *dense* (few probes, many sections vanilla would
walk) and loses when the world is *sparse* (many probes, nothing to find). The `> 1024` clause
disables the protection precisely in the region where protection is needed.

`Level.getEntities` is called with wide boxes routinely — mob targeting and follow ranges, explosion
entity collection, command selectors, and mod code. A 128-block box yields ~787 candidate keys,
comfortably under 1024.

**Measured** (`bash tools/audit/run.sh`, real fastutil containers, sparse world = 3 populated
sections, 128-block query, 200k queries after warmup):

| | ns/query |
|---|---|
| vanilla tree scan | 101 |
| Ultima, guard as it was | **4579** |
| Ultima, guard as fixed | 90 |

**~45× slower than vanilla**, from an optimization module. Raw operation counts confirm it is not a
measurement artefact: 787 hash probes against 9.2 tree-subset descents.

**Fix.** Drop the `> 1024` clause:

```java
if (candidates > this.sectionIds.size()) return;
```

`sectionIds.size()` is the whole storage, so this is a *generous* bound on vanilla's cost — vanilla
walks only the x-strip, never more than the whole set. The guard therefore never falls back on a
query the direct path would have won.

**Equivalence.** The guard only chooses *which* implementation runs, and both were already proven
to produce identical output. Verified anyway: **18,000 queries, 0 mismatches** against the vanilla
port with the tightened guard active (harness Part 3).

**Effect on the module's headline win: none.** An entity-sized query covers 2–8 candidate sections
against thousands of populated ones, so the dense-world cases are untouched — 408 → 2.2 and
118 → 2.2 work units, exactly as before. The fix is strictly a worst-case repair.

**Residual, disclosed.** One window remains: mid-density worlds where candidates is just under
`sectionIds.size()` but the *x-strip* is nearly empty (harness row "small server, 32-block query":
13.2 vanilla vs 33.9 both before and after). Closing it needs the strip population, which is not
obtainable in O(1) from a `LongSortedSet`. This residual is bounded and small; the unbounded case is
what mattered and it is gone.

### A-2 — `block_collision_shape` did not remove the allocation it documents

**Root cause.** The module deferred the collider voxelisation like this:

```java
@ModifyExpressionValue(method = "<init>...", at = @At(value = "INVOKE", target = "...Shapes;create(...)"))
private VoxelShape ultimaSkipEagerVoxelisation(final VoxelShape original) { return null; }
```

`@ModifyExpressionValue` **does not suppress the expression**. Confirmed from MixinExtras 0.5.4's
own source: its javadoc states the handler "receives the expression's resultant value … and should
return the adjusted value", and `ModifyExpressionValueInjector.injectValueModifier` calls
`target.insns.insert(insertionPoint, after)` — it *inserts* the handler call after the original
instruction and never removes it.

So `Shapes.create(box)` still executed, still allocated its `ArrayVoxelShape` and three
`DoubleArrayList`s, and the mixin then threw the reference away. The module's stated benefit —
"removes an `ArrayVoxelShape` plus three `DoubleArrayList` and their arrays per query" — was not
what the bytecode did. Any real saving depended on C2 inlining `Shapes.create` and proving the
result non-escaping, which is plausible but unguaranteed. Meanwhile the module reliably *added* a
handler invocation per construction and a null check per `entityShape` read.

This is the clearest instance of the pattern the audit was asked to look for: **a patch that treats
the symptom (a value being present) rather than the cause (a call being made).**

**Fix.** Wrap the operation and decline to perform it:

```java
@WrapOperation(method = "<init>...", at = @At(value = "INVOKE", target = "...Shapes;create(...)"))
private VoxelShape ultimaSkipEagerVoxelisation(final AABB box, final Operation<VoxelShape> original) {
    return null;
}
```

**Why the boxing objection does not apply here.** The previous pass rejected `@WrapOperation` for
`collision_shell_skip` because `Operation.call(Object...)` boxes into a varargs array — correct, and
decisive in a loop running 109M times per 800 ticks. Neither half applies here: this handler
**never calls `original`**, so no varargs array is ever created, and it runs **once per query**
rather than once per block position.

**Compatibility.** `@WrapOperation` is stackable, so other mods can still wrap the same call.
A mod wrapping this specific call site to substitute a custom entity shape would be bypassed — but
that was equally true before, since the old code discarded whatever value it produced. No
regression; documented rather than silently inherited.

**Fragility, disclosed and not fixed.** Both the old and new forms leave `entityShape` null and
patch its reads in `computeNext` only. If a future Minecraft version reads `entityShape` from a
second method, that read gets `null` and NPEs. `defaultRequire: 1` does **not** protect against
this — it validates that injection points resolve, not that the set of field readers is unchanged.
Re-validate on every version bump.

### A-3 — an undeclared dependency between two modules

`collision_shell_skip` reaches the cursor through `InteriorOnlyCursor`, an interface implemented by
the **`cursor_step`** mixin. Each module is independently switchable, so `cursor_step=false` with
`collision_shell_skip=true` is a configuration a user can reach — and the old code handled it like
this:

```java
if (this.ultimaShellIsIrrelevant() && this.cursor instanceof InteriorOnlyCursor interiorOnly) {
```

`&&` evaluates left to right, so **every collision query paid the full section-palette scan and then
discovered it had nothing to drive.** Fail-open on correctness, but a pure and permanent slowdown,
invisible to the user.

Two fixes, both applied:

1. Test the cheap, run-constant `instanceof` first, so the palette scan is skipped entirely when the
   cursor cannot be driven.
2. Warn once at init when the inert combination is configured, instead of letting it look like it
   works.

The deeper point is architectural and is the answer to "what will become fragile": `dev.ultima.ext`
is a **cross-module coupling channel with no representation in the module registry**. One such
interface is fine. Five will not be. Before the next module lands, `UltimaModules.Module` should
carry its dependencies and `UltimaMixinPlugin` should refuse — loudly — to apply a module whose
dependency is disabled. I have not built that mechanism for a single edge; it should not be deferred
past the second.

### A-4 — an unused hard dependency on Fabric API

`fabric.mod.json` declared `"fabric-api": "*"` in `depends`. Ultima imports nothing from
`net.fabricmc.fabric.api` — only `net.fabricmc.api.ModInitializer` and
`net.fabricmc.loader.api.FabricLoader`, both from the loader. The declaration forced Fabric API onto
every install, which matters for exactly the deployment Ultima targets: lean dedicated servers.
Removed.

---

## 3. Module-by-module review against the required checks

### `cursor_step` — **KEEP**

- **Root cause:** correct. `advance()` re-derives x/y/z with two integer divides by non-constant
  fields, in the innermost loop of the hottest server operation.
- **Frequency:** every visited block position of every collision query. Highest-frequency code in
  the mod.
- **Semantic equivalence:** verified, 0/4,963,671 positions. The `index != 0` guard is safe because
  `Cursor3D` has no reset and construction zeroes the fields.
- **Ordering / lifecycle:** the cursor is per-`BlockCollisions`, never shared, never rewound.
- **Worst case:** identical to vanilla; there is no input where carrying an increment is slower.
- **Mod surface:** cancelling `@Inject` at `HEAD` — a soft overwrite of an 8-line method. Acceptable
  under the guardrails only because no narrower hook exists, which is true.
- **Shader/render:** none. `Cursor3D` is also used by `ClientLevel`, where the divide-free path
  applies and the interior path never engages.
- **Benchmark representativeness:** good. Present in every tick regardless of world shape.

### `entity_section_lookup` — **KEEP + IMPROVED (A-1)**

- **Root cause:** correct, and the sharpest finding of the first pass. The key layout puts x in bits
  42–63, z in 20–41, y in 0–19, so vanilla's per-x subset spans every z and y — making a
  single-entity query scale with how much of the world is populated.
- **Semantic equivalence:** verified, 0/24,000 queries, set and order. The masking argument
  (negative coordinates sort above non-negative within a fixed x, hence the two-half iteration) is
  correct and is the part most likely to be broken by a careless edit — it now has a committed test.
- **Ordering:** order genuinely matters; callers collect into `List` and some resolve ties by
  first-encountered. Preserved exactly.
- **Lifecycle:** rests on `sections` and `sectionIds` being mutated together. **This is the module's
  load-bearing assumption and it could not be re-verified against 26.2 source here.** It should be
  re-checked on every version bump; if a code path ever adds to `sections` alone, Ultima visits a
  section vanilla would not.
- **Worst case:** *was* ~45× vanilla; now bounded. See A-1.
- **Mod surface:** cancelling `@Inject` at `HEAD`, replacing the loop. Lithium is the most likely
  conflict and remains an untested real-PC item.
- **Version fragility:** the box expansion constants (`-2.0`, `-4.0`, `-2.0`, `+2.0`, `+0.0`,
  `+2.0`) are **transcribed from vanilla**. If Mojang changes them, Ultima silently queries the
  wrong volume rather than failing to apply. This is silent semantic drift and is the single most
  dangerous version-bump hazard in the mod. See §5.

### `block_collision_shape` — **IMPROVED (A-2)**

- **Root cause:** correct in principle — the shape is needed only for non-cube, non-empty block
  shapes — but the implementation did not act on it. Now it does.
- **Frequency:** once per collision query, ~3 per entity per tick.
- **Semantic equivalence:** same call, same immutable box, computed at most once. Unchanged by the
  fix.
- **Worst case:** a query that *does* need the shape now builds it on first read instead of in the
  constructor — same call, one extra null check. Negligible.
- **Benchmark representativeness:** this module has no differential harness and its individual
  contribution was never isolated; it is folded into the 3-module staged figure. Given A-2, **its
  historical contribution should be assumed to be near zero** and re-measured.

### `collision_shell_skip` — **KEEP, with a measurement caveat (A-5)**

- **Root cause:** correct and genuinely architectural. The shell exists only to catch
  `hasLargeCollisionShape()` blocks (face positions) and `MOVING_PISTON` (edge positions); corners
  are already skipped by vanilla. Ultima's predicate is the union of both, which is conservative in
  the safe direction.
- **Geometry confirmed independently.** For a typical mob collider spanning 2×3×2 blocks the cursor
  volume is 4×5×4 = 80 positions of which 12 are interior — **85% shell**, matching the first pass's
  instrumented 87%.
- **Semantic equivalence:** the interior subsequence is exactly `TYPE_INSIDE`, verified. The
  `width/height/depth < 3` bail-out is correct: `BlockCollisions` always grows the box by one per
  side so every axis is ≥ 3, and where it were not, the interior is genuinely empty.
- **Fail-open design:** good. Non-`Level` collision getters, debug worlds, non-`LevelChunk` chunks
  and `GlobalPalette` sections all fall back. Absent chunks are treated as contributing nothing,
  matching vanilla.

**A-5 — the benchmark is the module's best case, by construction.** This is the most important
unresolved item in the report.

The saving is a fixed ~68 skipped positions per query. The cost is
`Σ over covered sections of (palette size)` predicate evaluations, per query, because
`LevelChunkSection.maybeHas` scans the palette. That cost is a property of the *world*:

| World | Typical palette per covered section | Predicate evaluations per query |
|---|---|---|
| Superflat bench world (`level-type=minecraft:flat`) | air `SingleValuePalette` (1), flat stack `LinearPalette` (~4) | **~4–8** |
| Ordinary overworld surface / caves | `LinearPalette` (≤16) to `HashMapPalette` (17–256) | **~60–180** |

`scripts/bench-server.sh` writes `level-type=minecraft\:flat`. The benchmark world is therefore
*maximally* favourable to this module — roughly an order of magnitude less palette work than a
normal world, against an unchanged saving.

This does not show the module is a regression, and I am not claiming it is. It shows the **-9.7%
figure is world-specific and should not be generalized.** The module needs one A/B on a default
world type before that number is quoted as Ultima's headline. `PERFORMANCE_REPORT.md` has been
amended accordingly.

Two secondary costs, both currently unmeasured, that belong in that re-measurement:

- `ultimaShellIsIrrelevant()` calls `getChunkForCollisions` once per (x, z) column per query,
  **outside** `BlockCollisions`' one-entry chunk cache. For a volume straddling a chunk boundary in
  both axes that is 4 uncached lookups added per query, partly re-done by `computeNext` afterwards.
  This mildly weakens the (correct) earlier rejection of a multi-entry chunk cache.
- A section backed by a `GlobalPalette` returns `true` unconditionally, so the query falls back to
  vanilla having already paid to scan every other section in the volume.

---

## 4. Architectural scan

**Collision system — the remaining structural redundancy.** The first pass's own instrumentation is
the key number: **~3 collision traversals per entity per tick**, from `Entity.collide`, the step-up
rescan, and `Entity.checkSupportingBlock` → `findSupportingBlock`. `collision_shell_skip` made each
traversal cheaper; the *fan-out itself is untouched* and is still the largest identified structural
waste in the server tick.

This is the honest answer to "are optimizations solving symptoms rather than root causes": there is
a chain of three, and the mod has worked inward along it. `cursor_step` reduces the cost per
position (symptom). `collision_shell_skip` reduces the number of positions (closer). The number of
*traversals* is the root, and it is still 3.

**B-1, the strongest remaining candidate**, follows directly and is better than the version the
first pass listed as "next target #2". Rather than trying to prove the three volumes are contained
in one another — which needs a containment proof the first pass rightly refused to hand-wave —
memoise **the shell-eligibility answer**, not the collider list, for the duration of one
`Entity.move`. That is sound for a reason worth stating precisely: no block state changes during a
single entity movement, so the palette answer is constant across all three traversals of that
movement. This would cut A-5's palette cost by ~3× and is the change most likely to make
`collision_shell_skip` unambiguously positive on real worlds. It needs a build to measure and
plumbing through `Entity`, so it is **specified here and deliberately not implemented**.

**Entity lookup / spatial queries.** Addressed by `entity_section_lookup`; worst case now bounded.
Worth checking on a machine with sources: other `EntitySectionStorage` accessors
(`getExistingSectionPositionsInChunk` and friends) may share the strip-scan shape.

**Chunk lifecycle, tick scheduling, invalidation fan-out.** Not touched by any module — correctly,
since every Ultima optimization to date is query-local with no cached state and therefore has *no
invalidation surface at all*. That is the mod's best architectural property and it should be
defended: the moment a module maintains state across ticks, it acquires a lifecycle that
`UpgradeData`-class code paths can violate. The rejected per-section index is the cautionary case.

**Allocation-heavy paths.** `block_collision_shape` is the only allocation-targeted module and, per
A-2, was not actually reducing allocation until this branch. The first pass's JFR observation of
130 GCs in 193 s suggests headroom remains; `ChunkMap.tick` (B-3) is the cleanest next target.

**Render-side CPU, resource/model lookup, networking, integrated client-server.** Untouched, and
that remains correct. There is no GPU in this environment, so no render change could be validated,
and the 26.2 renderer surfaces are exactly the ones Sodium and Iris replace. Under "better to lose
3 FPS than break shaders", not touching them is the right call for a second consecutive pass.

---

## 5. What will become fragile

Ordered by how much damage it does when it eventually bites.

1. **Transcribed vanilla constants drift silently.** `entity_section_lookup` copies the box
   expansion constants; `collision_shell_skip` copies the `1.0E-7` collision epsilons and the
   `>> 4` section derivation. `defaultRequire: 1` catches a *moved injection point* loudly, but
   catches a *changed constant* not at all — Ultima would keep applying and quietly query the wrong
   volume. Every module that transcribes a constant should say so at the declaration and be listed
   in a version-bump checklist.
2. **Cross-module coupling has no registry representation** (A-3). Fixed for the current edge;
   needs a real mechanism before the next one.
3. **`entityShape`'s single-reader assumption** (A-2) breaks silently on a version bump.
4. **The benchmark world shapes conclusions.** A superflat bench world understates palette costs and
   overstates section-density wins. As modules accumulate, one world type will increasingly flatter
   whichever optimizations resemble the existing ones. The bench script should grow a default-world
   mode.
5. **`UltimaConfig.writeIfChanged` discards unknown keys.** If the file has any key not in the
   registry, `existing.size() != modules.size()` triggers a rewrite that drops it. Harmless today,
   surprising later.

---

## 6. Changes made on this branch

| File | Change |
|---|---|
| `mixin/entity_section_lookup/EntitySectionStorageMixin.java` | A-1: tightened fallback guard; removed the `1024` budget constant. |
| `mixin/block_collision_shape/BlockCollisionsMixin.java` | A-2: `@ModifyExpressionValue` → `@WrapOperation` so `Shapes.create` is actually skipped. |
| `mixin/collision_shell_skip/BlockCollisionsShellMixin.java` | A-3: test `instanceof` before the palette scan. |
| `Ultima.java` | A-3: warn when `collision_shell_skip` is enabled without `cursor_step`. |
| `resources/fabric.mod.json` | A-4: dropped the unused Fabric API dependency. |
| `tools/audit/*` | New committed differential harnesses + runner. |
| `PERFORMANCE_REPORT.md` | Corrected the `block_collision_shape` claim; scoped the `collision_shell_skip` figure to its world type; recorded A-1. |

No gameplay semantics, save format, network protocol, tick ordering or render path is affected by
any of them. No new concurrency is introduced; all four modules remain query-local with no state
that outlives a single query.

**Verification status:** differential harnesses pass (0 mismatches across 4.96M cursor positions and
42,000 section queries); full `src/main/java` type-checks clean under `-Xlint:all` against stubbed
Minecraft/Mixin signatures with real MixinExtras 0.5.4; both JSON resources parse.
**`bash scripts/check.sh` has not been run and must be, on a machine with Fabric maven access,
before release.**
