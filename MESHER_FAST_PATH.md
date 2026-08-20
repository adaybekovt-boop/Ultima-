# Ultima mesher fast path (Phase 3.2)

Gate: `mesher_fast_path=false` (default **OFF**). Client-only. Auto-off with Sodium/Iris/Canvas.
Independent of `retained_terrain` and of PR #3. When both `java_mesher` and
`mesher_fast_path` are requested, `mesher_fast_path` owns `SectionCompiler.compile`.

This document is **not** a performance claim. CPU meshing-time numbers in tests
are wall-clock of the synthetic cube kernel only. Real FPS/GPU A/B is a later
hardware stage. See `MESHER_HARDWARE_AB.md`.

## Architecture

Hybrid compile of one 16³ section:

1. Capture an 18³ packed snapshot (interior + 1-block halo), x-fastest, worker
   `ThreadLocal` scratch. Production flags are lean (air / solid / BE / fluid /
   model only). Occlusion-shape and `skipRendering` bits are
   **test-fixture / `flagsOfForTest` only** and are not computed on the
   compile hot path.
2. Visit interior cells in `BlockPos.betweenClosed` order.
3. **Fast path** if `FastPathCriteria` admits the cell: emit cached
   vanilla `BakedQuad`s with vanilla `Block.shouldRenderFace`,
   `BlockModelLighter`, and `BlockColors`. Does **not** call
   `ModelBlockRenderer.tesselateBlock`.
4. **Fallback** otherwise: vanilla `ModelBlockRenderer.tesselateBlock` /
   `FluidRenderer.tesselate`.

Zero approximation: if identity with vanilla cannot be guaranteed, fallback.

`FastPathCriteria` is the single source of truth. `CubeModelCache.lookup`
returns the same `Reason` enum the kernel/tests use. The synthetic kernel
(`FastPathCubeMesher` / `VanillaCubeOracle`) admits cells only through
`fromFixtureState`. Flag-only `fromFlags` is a fixture heuristic and does
not admit production cells.

The cache is an `IdentityHashMap<BlockState, …>`. Minecraft interns each
property combination, so `furnace[facing=north]` and `furnace[facing=south]`
are already distinct entries. Phase 3.2 does not change that keying; it
admits a second model class (`WeightedVariants`) whose alternatives are
still proven unit cubes.

## Fail-open

`SectionCompilerMixin` wraps `HybridSectionMesher.compile` in try/catch.
An Ultima-only fault finishes and closes any partial layer `MeshData` (26.2
`BufferBuilder` has no `discard()`), logs WARN with the section position,
increments `meshFastPathFailures`, and **does not cancel** — vanilla
`SectionCompiler.compile` rebuilds that section on the same pack. Neighbor
sections are unchanged.

A session circuit breaker is keyed by **BlockState identity**, not by section
(`MesherCircuitBreaker`, trip after 3 failures). One noisy model is excluded
from the fast path for the rest of the session; other blocks keep using it.
This is documented and tested; it is not a section blacklist.

## Why `NOT_SINGLE_VARIANT` dominated the first hardware run

Vanilla 26.2 still bakes the variants map **per `BlockState`**. Facing / axis
/ lit cubes (furnace, logs, barrels, glazed terracotta) are already
`SingleVariant` at `BlockStateModelSet.get(state)` and were never the
12.67 M fallback bucket.

The bucket is the other `BlockStateModel` implementations:

| Category | 26.2 examples | Geometry | Fast path? |
|---|---|---|---|
| (a) Weighted unit cubes | stone, dirt, deepslate, sand, red_sand, bedrock, netherrack, sculk, mycelium/podzol (incl. snowy=true cube), concrete powder, infested stone/deepslate, rooted_dirt | 6 axis-aligned opaque faces; UVs chosen by `BlockState.getSeed(pos)` among 4 rotations/mirrors | **yes, Phase 3.2** — cache every alternative, pick with vanilla `WeightedList.getRandomOrThrow` |
| (a) Per-state SingleVariant cubes | furnace facing/lit, oak_log axis, basalt, bone_block, hay, dispenser, observer, barrels, glazed terracotta, redstone_lamp, TNT | unit cube; textures depend on the interned `BlockState` | **already yes** — cache key is BlockState identity |
| (b) Weighted non-cubes | grass_block (side overlay), dirt_path, lily_pad, sea_pickle, turtle_egg | extra quads / non-cube elements | **no** (`WEIGHTED_NON_CUBE`) |
| (b) Multipart | fences, walls, panes, bamboo, redstone, chorus, mushroom blocks | connected-state / layered | **no** (`MULTIPART_MODEL`) |
| (b) True non-cubes | stairs, slabs, plants, rails, chests, leaves | not a 6-quad unit cube | **no** (existing reasons) |

Waterlogged full cubes are not a coverage lever: `classifyState` still
rejects any non-empty `FluidState` (`HAS_FLUID`) and `FluidRenderer` remains
vanilla. Most waterlogged blocks are also not unit cubes.

`VanillaCubeCoverage` lists the 29 weighted unit-cube block names from the
26.2 `blockstates` JSON. Production never uses that list as an allow-list;
admission is baked-quad geometry.

## Fast path (covered types)

A cell is fast-pathed only when **all** of these hold:

| Check | Required |
|---|---|
| `RenderShape.MODEL` | yes |
| Fluid empty | yes |
| `hasOffsetFunction()` | must be false |
| `LeavesBlock` / `forceOpaque` | always fallback |
| Render layer | **no** `TRANSLUCENT` quad (glass/ice/slime always fallback) |
| Model class | `SingleVariant` **or** `WeightedVariants` whose every child is `SingleVariant` |
| Unculled quads | none |
| Culled quads | exactly one per `Direction` on the chosen child |
| Each quad | axis-aligned unit-cube face, matching cull direction |
| Weighted pick | `RandomSource.createThreadLocalInstance` + `setSeed(BlockState.getSeed(pos))` + `WeightedList.getRandomOrThrow` (same consume as vanilla `collectParts`) |
| Occlusion | vanilla `Block.shouldRenderFace` |
| Lighting / tint | vanilla `BlockModelLighter` + `BlockColors` |
| Circuit breaker | not tripped for this BlockState |

Typical vanilla models that pass: `cube`, `cube_all`, `cube_mirrored`,
`cube_bottom_top`, `cube_column`, `orientable` full cubes — including the
26.2 weighted families (stone, dirt, deepslate, sand) and already-covered
SingleVariant cubes (planks, ores, wool, cobble, furnace, logs).

## Always fallback

- Air (skipped, matching vanilla)
- Fluids / waterlogged fluid tessellation
- Non-`MODEL` render shapes
- **Glass, stained glass, tinted glass, ice, slime, honey, and any other
  translucent-layer cube** — conservative for this version (sorting / shader
  / `HalfTransparentBlock` risk)
- `MultiPartModel` and Fabric custom models that are not vanilla
  `SingleVariant` / unit-cube `WeightedVariants`
- Weighted families that fail the 6-quad unit-cube proof (grass overlay)
- Stairs, slabs, fences, walls, panes, plants, rails, chests
- Blocks with random offset
- All `LeavesBlock` states
- Circuit-breaker tripped BlockStates

Neighbor culling in production is always exact `Block.shouldRenderFace`.
An unloaded neighbor section is an air halo: all six faces stay visible,
no crash, no special fallback bucket. The synthetic kernel additionally
refuses a cell whose neighbor is neither a full-block occluder nor an
empty occluder so tests never guess voxel joins.

## Equivalence tests

**Honesty constraint:** kernel tests in `MesherFastPathChecks` exercise
`FastPathCubeMesher` / `VanillaCubeOracle` / `OcclusionMask`. Those classes are
**not** called from `SectionCompilerMixin`. Production compile goes through
`HybridSectionMesher.tessellateFastCube`. Kernel PASS does **not** by itself
prove production vertices, UVs, or AO.

Two layers:

1. **Production culling (this revision).**
   `HybridSectionMesherProductionChecks` calls
   `HybridSectionMesher.forEachVisibleFastCubeFace` — the only culling loop
   `tessellateFastCube` uses — with real `Block.STONE` / `Block.AIR` and
   compares the emitted faces to vanilla `Block.shouldRenderFace` for the same
   neighbors. AO on/off is the production predicate
   (`ambientOcclusion && lightEmission == 0 && cube.useAmbientOcclusion()`).
   Full vertex/UV/AO identity vs `ModelBlockRenderer.tesselateBlock` still
   needs a booted client `BlockStateModelSet` and is **not** claimed here.
2. **Kernel tests** in `MesherFastPathChecks`: visit order, occlusion vs
   the first `shouldRenderFace` branches, FaceInfo winding, ordered vertex
   identity, fail-open, circuit breaker, glass fallback, animated UVs,
   multiple tint providers, unloaded-neighbor edges, cache reload,
   realistic CPU datasets, **weighted / facing / axis cube equivalence**,
   grass-overlay and multipart fallback, and vanilla weighted RNG identity.
   These remain synthetic-kernel coverage.

## CPU meshing time

`CpuMeshingBenchmark` times the synthetic kernel (this host, Java 25).
Printout is tagged as CPU meshing time only. Datasets: solid 16³,
checkerboard, typical overworld surface, dense structure, **weighted
overworld volume** (stone/dirt/deepslate-like).

These numbers measure the Java cube kernel only on this cloud VM and vary
between runs. They are **not** a real FPS/GPU claim.

## Theoretical coverage (not FPS)

The first 3×3 hardware route reported **13.4397% ± 0.1023%** of non-air
blocks on the fast path, with `NOT_SINGLE_VARIANT` ≈ 12.67 M cells/run.
On a similar overworld compile mix, converting the weighted unit-cube
family (stone / dirt / deepslate / sand / netherrack / bedrock / …) is
the volume that lived in that bucket. Multipart, grass overlay, fluids,
leaves, and true non-cubes stay fallback.

This is a **block-type coverage forecast**, not a frame-time or FPS
number. Tomorrow's hardware A/B (`fastPathCoverageOfNonAir`,
`weightedFastPathBlocks`, `fallbackByReason`) is the measurement.

## License

Winding and AO sampling follow Minecraft 26.2 `FaceInfo` / `FaceBakery` /
`BlockModelLighter` as local reference. Independent Ultima implementation.
Not a line copy of Sodium, Lithium, or other GPL/LGPL meshers.
