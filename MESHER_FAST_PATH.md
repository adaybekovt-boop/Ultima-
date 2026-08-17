# Ultima mesher fast path (Phase 3.1)

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

## Fast path (covered types)

A cell is fast-pathed only when **all** of these hold:

| Check | Required |
|---|---|
| `RenderShape.MODEL` | yes |
| Fluid empty | yes |
| `hasOffsetFunction()` | must be false |
| `LeavesBlock` / `forceOpaque` | always fallback |
| Render layer | **no** `TRANSLUCENT` quad (glass/ice/slime always fallback) |
| Model class | vanilla `SingleVariant` only |
| Unculled quads | none |
| Culled quads | exactly one per `Direction` |
| Each quad | axis-aligned unit-cube face, matching cull direction |
| Occlusion | vanilla `Block.shouldRenderFace` |
| Lighting / tint | vanilla `BlockModelLighter` + `BlockColors` |
| Circuit breaker | not tripped for this BlockState |

Typical vanilla models that pass: `cube`, `cube_all`, `cube_bottom_top`,
`cube_column` full cubes — stone, dirt, cobble, ores, planks, wool,
concrete, terracotta, sand, gravel, netherrack, deepslate, glowstone,
and grass_block **if** its baked model remains a 6-quad opaque cube.

## Always fallback

- Air (skipped, matching vanilla)
- Fluids / waterlogged fluid tessellation
- Non-`MODEL` render shapes
- **Glass, stained glass, tinted glass, ice, slime, honey, and any other
  translucent-layer cube** — conservative for this version (sorting / shader
  / `HalfTransparentBlock` risk)
- `WeightedVariants`, multipart, Fabric custom models
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

Two layers, both required:

1. **Production identity by construction.** Cached vanilla `BakedQuad` +
   vanilla cull / light / tint. Anything not a proven opaque 6-quad unit
   cube is vanilla `tesselateBlock` / `FluidRenderer`.
2. **Kernel tests** in `MesherFastPathChecks`: visit order, occlusion vs
   the first `shouldRenderFace` branches, FaceInfo winding, ordered vertex
   identity, fail-open, circuit breaker, glass fallback, animated UVs,
   multiple tint providers, unloaded-neighbor edges, cache reload, and
   realistic CPU datasets.

## CPU meshing time

`CpuMeshingBenchmark` times the synthetic kernel (this host, Java 25).
Printout is tagged as CPU meshing time only. Datasets: solid 16³,
checkerboard, typical overworld surface, dense structure.

These numbers measure the Java cube kernel only on this cloud VM and vary
between runs. They are **not** a real FPS/GPU claim.

## License

Winding and AO sampling follow Minecraft 26.2 `FaceInfo` / `FaceBakery` /
`BlockModelLighter` as local reference. Independent Ultima implementation.
Not a line copy of Sodium, Lithium, or other GPL/LGPL meshers.
