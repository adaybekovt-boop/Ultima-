# Ultima mesher fast path (Phase 3)

Gate: `mesher_fast_path=false` (default **OFF**). Client-only. Auto-off with Sodium/Iris/Canvas.
Independent of `retained_terrain` and of PR #3. When both `java_mesher` and
`mesher_fast_path` are requested, `mesher_fast_path` owns `SectionCompiler.compile`.

This document is **not** a performance claim. CPU meshing-time numbers in tests
are wall-clock of the synthetic cube kernel only. Real FPS/GPU A/B is a later
hardware stage.

## Architecture

Hybrid compile of one 16³ section:

1. Capture an 18³ packed snapshot (interior + 1-block halo), x-fastest, worker
   `ThreadLocal` scratch.
2. Visit interior cells in `BlockPos.betweenClosed` order.
3. **Fast path** if the block is a proven unit cube (see below): emit cached
   vanilla `BakedQuad`s with vanilla `Block.shouldRenderFace`,
   `BlockModelLighter`, and `BlockColors`. Does **not** call
   `ModelBlockRenderer.tesselateBlock`.
4. **Fallback** otherwise: vanilla `ModelBlockRenderer.tesselateBlock` /
   `FluidRenderer.tesselate`.

Zero approximation: if identity with vanilla cannot be guaranteed, fallback.

## Fast path (covered types)

A cell is fast-pathed only when **all** of these hold:

| Check | Required |
|---|---|
| `RenderShape.MODEL` | yes |
| Fluid empty | yes |
| `hasOffsetFunction()` | must be false |
| `LeavesBlock` / `forceOpaque` | always fallback (layer remap) |
| Model class | vanilla `SingleVariant` only |
| Unculled quads | none |
| Culled quads | exactly one per `Direction` |
| Each quad | axis-aligned unit-cube face, matching cull direction |
| Occlusion | vanilla `Block.shouldRenderFace` (not a guessed occupancy) |
| Lighting / tint | vanilla `BlockModelLighter` + `BlockColors` on those quads |

Typical vanilla models that pass: `cube`, `cube_all`, `cube_bottom_top`,
`cube_column` full cubes — stone, dirt, cobblestone, ores, planks, wool,
concrete, terracotta, sand, gravel, netherrack, deepslate, glowstone, glass,
ice, and grass_block **if** its baked model remains a 6-quad cube.
Glass/ice neighbor culling stays on vanilla `Block.shouldRenderFace`
(`HalfTransparentBlock.skipRendering`), not a guessed occupancy bit.

## Always fallback

- Air (skipped, matching vanilla)
- Fluids / waterlogged fluid tessellation
- Non-`MODEL` render shapes
- `WeightedVariants`, multipart, Fabric custom models
- Stairs, slabs, fences, walls, panes, plants, rails, chests, and any
  non-unit-cube geometry
- Blocks with random offset (flowers, ferns)
- All `LeavesBlock` states (including fancy leaves)
- Any neighbor situation is still exact in production because culling calls
  `Block.shouldRenderFace`. The synthetic kernel additionally refuses a cell
  whose neighbor is neither a full-block occluder nor an empty occluder
  (slabs, stairs) so tests never guess voxel joins.

## Equivalence tests

Two layers, both required:

1. **Production identity by construction.** The in-game fast path emits the
   cached vanilla `BakedQuad` and runs vanilla `Block.shouldRenderFace`,
   `BlockModelLighter`, and `BlockColors`. It does not invent positions, UVs,
   AO, or tint. Anything that is not a proven 6-quad unit cube is vanilla
   `tesselateBlock` / `FluidRenderer`.
2. **Kernel visit/occlusion tests** in `MesherFastPathChecks`: packed 18³
   visit order vs `BlockPos.betweenClosed`, occlusion mask vs the first
   branches of `Block.shouldRenderFace`, FaceInfo winding vs
   `FullCubeTemplates`, and ordered vertex identity of the template fast path
   vs the per-face cube oracle (same lighting kernel, independent culling
   control flow). Ordered identity, not visual similarity.

See the PASS list printed by `forensicRegressionTest`.

## CPU meshing time

`CpuMeshingBenchmark` times the synthetic kernel (this host, Java 25). Printout is tagged
as CPU meshing time only.

Recorded on this cloud VM (not hardware A/B, **not an FPS claim**):

| Snapshot | Oracle (min) | Fast path (min) | Vertices |
|---|---|---|---|
| solid 16³ | 0.333 ms | 0.255 ms | 6144 |
| checkerboard | 1.480 ms | 1.506 ms | 49152 |

These numbers measure the Java cube kernel only. They are **not** a real FPS/GPU
claim and require hardware A/B before any performance statement.

## License

Winding and AO sampling follow Minecraft 26.2 `FaceInfo` / `FaceBakery` /
`BlockModelLighter` as local reference. This is an independent Ultima
implementation. It is not a line copy of Sodium, Lithium, or other GPL/LGPL
meshers.
