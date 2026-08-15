# Ultima retained terrain architecture

This is the lifecycle contract for the experimental `retained_terrain` module. It exists to prevent stale meshes, wrong origins, and shader-mod breakage — not as a novel.

Gated **off by default**. Fail open to vanilla `LevelRenderer.prepareChunkRenders`.

---

## 1. Vanilla path

`prepareChunkRenders` walks `visibleSections`, builds per-frame `RenderPass.Draw` lists, writes one `ChunkSection` UBO slice per section (duplicating `ModelViewMat` + atlas size), then `ChunkSectionsToRender.renderGroup` calls `drawMultipleIndexed`. Both GL and Vulkan backends still **loop in Java** and bind `ChunkSection` per draw.

Translucent terrain always stays on this path in the first prototype.

---

## 2. Ultima retained path (opaque only)

Enabled when:

- module `retained_terrain` is on
- Sodium / Iris / Canvas are **not** loaded
- device reports `shaderDrawParameters` (for table fetch)
- custom `ultima:core/terrain_retained` pipelines compile
- no exception during prepare/submit

Otherwise the vanilla method runs unmodified.

Per frame:

1. Walk the same `visibleSections` list, same lock around `SectionRenderDispatcher`.
2. Update the **section metadata table** for visible slots (origin, fade, mesh generation).
3. Update **command slots** only when a dependency generation changed.
4. Submit SOLID then CUTOUT using retained batches.
5. Build vanilla Draw lists **only for TRANSLUCENT**.

Camera motion updates the shared `ChunkSection` header (`ModelViewMat`) and does not rebuild command objects.

---

## 3. Section identity

| Key | Meaning |
|---|---|
| `RenderSection.index` | Stable slot while the `ViewArea` ring exists |
| `sectionNode` (packed `SectionPos`) | World identity; changes on ring wrap → treat as unload + load |
| `meshIdentity` | `System.identityHashCode(sectionMesh)` + per-layer indexCount |
| `generation` | Monotonic per slot; bumped on node change, mesh change, renderer reset |

Do not key GPU slots by `BlockPos` objects. Origins are `int` block coordinates from `getRenderOrigin()` (section origin, 16-aligned).

---

## 4. Mesh lifetime

Meshes remain vanilla `SectionMesh` in `UberGpuBuffer`. Ultima does not retesselate, restrip, or change vertex formats. A command slot stores vertex/index buffer **handles and offsets** from `getRenderSectionSlice`. If the slice is null (not uploaded), the slot is skipped this frame — same as vanilla.

---

## 5. Visibility lifetime

`RenderSection.getVisibility(now)` is a fade in `[0,1]`. Most slots are `1.0`. Ultima stores a quantized fade (`floatToIntBits` on GPU, full float on CPU). If fade is already 1 and `fadeDuration` elapsed, visibility updates are skipped. Quantization is bit-identical to the float vanilla wrote.

Frustum occupancy is **vanilla** `visibleSections`. Ultima does not cull.

---

## 6. Command lifetime

A command slot is `(sectionIndex, layer ∈ {SOLID, CUTOUT})`:

- `alive`, `vertexBuffer`, `indexBuffer`, `indexType`, `firstIndex`, `indexCount`, `baseVertex`, `metadataIndex`, `meshGeneration`, `node`

Slots are persistent arrays sized to `ViewArea.size()`. They are **not** allocated per draw per frame.

Rebuild the GPU batch lists only when any slot in that `(layer, vertexBuffer, indexBuffer)` group was created, removed, or had mesh/offset change. Visibility-only changes rewrite the metadata table, not the indirect commands (indexCount stays).

---

## 7. GPU metadata lifetime

Two UBOs, both persistent/ringed:

1. **`ChunkSection`** (vanilla layout, **one record for the whole opaque pass**): `ModelViewMat`, unused fade/origin fields, `TextureSize`. Bound once.
2. **`UltimaSectionBatch`**: `ivec4[BATCH]` packed `(origin.xyz, floatBitsToInt(visibility))`. Indexed by `gl_DrawID + gl_BaseInstance`.

Legacy fallback (no shader table): vanilla per-draw `ChunkSection` slices via retained `RenderPass.Draw` objects (still skip EnumMap churn when commands are stable). Used if pipelines fail.

---

## 8. Invalidation sources

| Event | Metadata | Command | Mesh |
|---|---|---|---|
| Block mesh rebuild / new `SectionMesh` | UPDATE | UPDATE | vanilla compile |
| Upload slice change (uber heap) | KEEP | UPDATE | KEEP |
| Visibility / fade only | UPDATE | KEEP | KEEP |
| Camera `ModelViewMat` only | header only | KEEP | KEEP |
| Atlas size change | header only | KEEP | KEEP |
| Section node wrap (`setSectionNode`) | REMOVE+ADD | REMOVE+ADD | vanilla `reset()` |
| Section no longer in `visibleSections` | KEEP row | disable slot | KEEP |
| Resource reload / pipeline cache clear | REBUILD GPU | REBUILD GPU | KEEP |
| Renderer `close` / level change | REBUILD | REBUILD | RELEASE GPU Ultima buffers |
| Shader compile failure | — | — | disable module path |

---

## 9. Resource reload

`ShaderManager.apply` clears the device pipeline cache. Ultima drops compiled retained pipelines and re-precompiles on the next opaque frame. If invalid → vanilla for the rest of the session (logged once).

---

## 10. Fallback behavior

Any throwable in prepare or submit: log, set `failedOpen=true`, return control to vanilla for that frame and subsequent frames until renderer reset. Never mutate world state. Never leave a half-bound render pass: submit uses try-with-resources `RenderPass` like vanilla.

---

## 11. OpenGL path

Capability order:

1. `drawIndirect` + `multiDrawIndirect` → `drawIndexedIndirect` on a `USAGE_INDIRECT_PARAMETERS` buffer.
2. Else `multiDrawDirectSeparate` → `multiDrawIndexed(PointerBuffer, counts, baseVertices, n)`.
3. Else loop `drawIndexed` with `firstInstance = slot` (still one VAO / one batch UBO).

Requires `GL_ARB_shader_draw_parameters` in the retained shader (`gl_DrawIDARB + gl_BaseInstanceARB`).

---

## 12. Vulkan path

Same producer. Submit:

1. `drawIndexedIndirect` if `drawIndirect` (and `multiDrawIndirect` when count > 1).
2. Else `multiDrawDirectInterleaved` → `multiDrawIndexed(IntBuffer, ...)`.
3. Else per-draw `drawIndexed` with `firstInstance`.

Do not add a second Vulkan device. Push-descriptor cost falls because `ChunkSection` is not rebound per section.

---

## 13. Shader compatibility boundary

Owned shaders: `assets/ultima/shaders/core/terrain_retained.{vsh,fsh}` and `include/section_table.glsl`. Pixel math copies vanilla `core/terrain` except origin/fade fetch.

Vanilla `minecraft:core/terrain` is untouched so translucent, block items, and shader packs that rewrite vanilla files keep working.

Incompatible mods (`sodium`, `iris`, `canvas`): module auto-off via `UltimaModules`. Simulation modules stay enabled.

---

## 14. Adjacent gated modules (not the opaque producer)

| Module | Default | What it changes | Stale-mesh / visual risk |
|---|---|---|---|
| `render_snapshot` | off | Intern `ImmutableMap.copyOf(chunk.getBlockEntities())` per live map identity inside one `RenderRegionCache` | Palettes still copied per section. Interned BE map is the first snapshot of that extract, same as vanilla's first `SectionCopy` |
| `java_mesher` | off | Packed x-fastest loop matching `BlockPos.betweenClosed`; ThreadLocal tessellators | Visit order tested; tessellation still vanilla `ModelBlockRenderer` / `FluidRenderer` |
| `section_task_queue` | off | Compact cancelled tasks; `LockSupport.parkNanos(50µs)` instead of `onSpinWait` | Same nearest-initial / recompile-quota selection |
| `rgss_endpoint` | off | Fragment source rewrite of `sampleRGSS` endpoints only | Exact at blend 0 and 1; reject unless GPU ≥3% |

Entity render-state arenas (Phase 12) and extra simulation (Phase 13) stay deferred until terrain is measured on a GPU.

