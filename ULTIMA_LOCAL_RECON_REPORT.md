# Ultima local reconnaissance — Minecraft 26.2 rendering

Date: 2026-08-15  
Source: Fabric Loom decompiled Minecraft 26.2 + Blaze3D client sources under `.agent/vanilla-src` (not part of Ultima).  
Status: **observed facts** vs **architectural inference** vs **benchmark hypothesis** are labeled. This report is not FPS evidence.

Product target: **≥ +25% real average FPS** at identical visual quality. Frame generation, upscaling, and quality cuts do not count.

---

## 1. Observed Minecraft 26.2 rendering architecture

### Frame split (fact)

`Minecraft.renderFrame` → `GameRenderer.extract` (main-thread scene) → `GameRenderer.render` / `renderLevel` (GPU) → `LevelRenderer.endFrame`.

Extraction (`LevelExtractor.extract`) is separate from submission. Terrain GPU work runs inside a `FrameGraphBuilder` (`clear`, `sky`, `main`, clouds, weather, transparency, `always_on_top`).

### Terrain producer → backend (fact)

```
dirty visible sections
  → RenderRegionCache / SectionCopy snapshot
  → SectionCompiler.compile (worker)
  → UberGpuBuffer + StagingBuffer upload
  → LevelRenderer.prepareChunkRenders   // every frame
  → ChunkSectionsToRender.renderGroup
  → RenderPass.drawMultipleIndexed
  → GlCommandEncoder.executeDrawMultiple
     or VulkanRenderPass.drawMultipleIndexed
```

Layers: `SOLID`, `CUTOUT`, `TRANSLUCENT`. Opaque group is SOLID+CUTOUT. Translucent is a later pass after solid features and depth copies.

### What `prepareChunkRenders` does every frame (fact)

For every visible `RenderSection` × occupied layer:

1. Allocate `new Matrix4f(modelViewMatrix)` into a new `DynamicUniforms.ChunkSectionInfo` **once per section**, not once per frame.
2. Hash-bucket `new RenderPass.Draw` objects into a fresh `EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<Draw>>>`.
3. Each Draw carries a lambda that binds UBO slice `"ChunkSection"` at submit time.
4. `writeChunkSections(...)` maps and writes **every** section record into a ring UBO (batch path has no consecutive-equals dedupe).

`ChunkSectionInfo` STD140 (`CHUNK_SECTION_UBO_SIZE` = 96 bytes before device alignment):

| Field | Shader name |
|---|---|
| `mat4` modelView | `ModelViewMat` |
| `float` visibility | `ChunkVisibility` |
| `ivec2` atlas size | `TextureSize` |
| `ivec3` origin | `ChunkPosition` |

`ModelViewMat` and `TextureSize` are **the same for every section in a frame**. Only `ChunkPosition` and `ChunkVisibility` are section-local. Camera movement therefore rewrites the entire section UBO array.

### What `drawMultipleIndexed` actually executes (fact)

The name is not GPU multi-draw.

**OpenGL** `GlCommandEncoder.executeDrawMultiple`: Java loop per Draw → `setIndexBuffer` → `setVertexBuffer` → `glBindBufferRange` for `ChunkSection` → possibly rebind VAO → `glDrawElementsInstancedBaseVertex`.

**Vulkan** `VulkanRenderPass.drawMultipleIndexed`: Java loop per Draw → `setUniform` / push descriptors → `setIndexBuffer` → `setVertexBuffer` → `vkCmdDrawIndexed`.

Terrain currently never calls `RenderPass.multiDrawIndexed`, `drawIndexedIndirect`, or `drawIndirect`.

### Backends already expose unused multi-draw/indirect (fact)

`DeviceFeatures`: `shaderDrawParameters`, `multiDrawDirectInterleaved`, `multiDrawDirectSeparate`, `multiDrawIndirect`, `drawIndirect`, `nonZeroFirstInstance`, `persistentMapping`.

OpenGL (`GlDevice` / `GlHeuristics`):

- Enables `GL_ARB_shader_draw_parameters`, `GL_ARB_draw_indirect`, `GL_ARB_multi_draw_indirect`, `GL_ARB_base_instance` when present.
- `multiDrawDirectSeparate = true`, `multiDrawDirectInterleaved = false`.
- Implements `nglMultiDrawElementsBaseVertex`, `glMultiDrawElementsIndirect`.

Vulkan (`VulkanBackend` / `VulkanRenderPass`):

- `shaderDrawParameters` from Vulkan 1.1.
- `multiDrawIndirect` from `VkPhysicalDeviceFeatures.multiDrawIndirect`.
- `EXT_multi_draw` (`vkCmdDrawMultiIndexedEXT`).
- `vkCmdDrawIndexedIndirect`.
- `multiDrawDirectSeparate` throws; interleaved EXT is the Vulkan multi-draw.

### Geometry storage (fact)

Per-layer `UberGpuBuffer` (128 MiB vertex, 32 MiB index) with `TlsfAllocator`. Many visible sections share one GPU buffer until a heap is added. Sequential auto-indices are used when a mesh has no custom index buffer (typical opaque). Vertices are **section-relative**; the shader adds `ChunkPosition`.

### Compile / snapshot / queue (fact)

- `new RenderRegionCache()` every extract. `createRegion` copies a 3×3×3 neighborhood. Each `SectionCopy` does `PalettedContainer.copy()` and `ImmutableMap.copyOf(chunk.getBlockEntities())` — the BE map is **chunk-wide**, so neighboring sections of the same chunk duplicate it.
- `SectionCompiler.compile` allocates `new ModelBlockRenderer` + `new FluidRenderer` and walks `BlockPos.betweenClosed` (index `% width` / `/ width` order, 4096 positions).
- `SectionTaskDynamicQueue.poll`: synchronized O(n) scan, cancel cleanup, distance compare, `ArrayList.remove` O(n). Recompile quota = 2.
- Worker `Thread.onSpinWait()` while uber/staging append fails.

### Terrain shaders (fact)

`core/terrain.vsh/.fsh` + include `chunksection.glsl`. RGSS is **fragment super-sampling** gated by `Globals.UseRgss`. Endpoint `mix(nearest, rgss, blendFactor)` still evaluates both sides. This is a **separate GPU experiment**, not the retained-command architecture.

### Entity extraction (fact)

`EntityRenderer.createRenderState()` allocates a fresh state object per visible entity per frame (`EntityRenderer.createRenderState(entity, partialTicks)`).

---

## 2. Why previous client micro-Mixins failed (fact + inference)

Deleted modules `client_chunk_matrix_reuse`, `client_chunk_layer_array_reuse`, `client_chunk_dirty_dedup` showed **−0.28% avg FPS** on RTX 3090 (n=3, inconclusive). They reduced allocations inside the same per-frame rebuild/submit contract. The driver still saw per-draw UBO binds and per-draw `glDraw*` / `vkCmdDrawIndexed`.

**Inference:** removing Java allocations without changing the producer representation or the per-draw bind loop will not hit the product FPS target.

---

## 3. Central architecture hypothesis (hypothesis — needs A/B)

Minecraft 26.2 already has extraction, a frame graph, uber buffers, staged uploads, OpenGL, Vulkan, and multi-draw *capability*. The expensive leftover is the **producer representation**:

- rebuild Java draw objects every frame
- duplicate section transform into every UBO record
- bind a different `ChunkSection` slice per draw
- execute a Java draw loop in both backends

**Retained terrain rendering** (opaque first):

- persistent section metadata (stable identity + generation)
- one metadata table; commands store `sectionMetadataIndex`
- persistent command slots; camera motion does not rebuild Java draws
- compact GPU table; `gl_DrawID` / `gl_BaseInstance` fetch origin + fade
- grouped indirect / multi-draw on the existing backends
- vanilla `prepareChunkRenders` fallback

Do **not** write a new Vulkan engine. Feed the existing `RenderPass` multi-draw/indirect APIs.

---

## 4. Top 50 candidates (ranked by expected frame-time leverage × evidence)

Legend: **F** = observed fact, **I** = inference, **H** = needs benchmark. Class A = Sodium-safe; B = vanilla-only / gate Sodium; C = visual or shader risk.

| # | Candidate | Class | Evidence |
|---|---|---|---|
| 1 | Retained opaque command slots | B | F: full rebuild in `prepareChunkRenders` |
| 2 | Section metadata table (one record / section) | B | F: origin duplicated per occupied layer; MV duplicated per section |
| 3 | Hoist ModelViewMat + atlas out of per-section UBO | B | F: identical every section |
| 4 | GPU section table + drawID/baseInstance | B | F: backends already expose the features |
| 5 | OpenGL `glMultiDrawElementsIndirect` for opaque | B | F: implemented, unused by terrain |
| 6 | Vulkan `vkCmdDrawIndexedIndirect` / `EXT_multi_draw` for opaque | B | F: `drawMultipleIndexed` is still a Java loop |
| 7 | Skip command rebuild on camera-only frames | B | F: invalidation today is “everything” |
| 8 | Visibility-only updates (fade) without mesh touch | B | F: `getVisibility` is a fade scalar |
| 9 | Persistent Draw lists even without indirect (one VAO/UBO bind) | B | I: bind/draw loop is the remaining CPU |
| 10 | `prepareChunkRenders` persistent arrays / dirty bitsets | B | F: EnumMap + hash maps every frame |
| 11 | Persist `RenderRegionCache` object; share BE map per chunk | B | F: `ImmutableMap.copyOf` per section |
| 12 | Chunk-versioned SectionCopy; cancel stale compiles | B | H: snapshot cost vs hitch |
| 13 | Reuse `ModelBlockRenderer`/`FluidRenderer` | B | F: new every compile |
| 14 | Packed x/y/z mesher loop, worker scratch | B | F: `BlockPos` iterator + `%`/`/` |
| 15 | Heap + lazy cancel for `SectionTaskDynamicQueue` | B | F: O(n) poll + O(n) remove |
| 16 | Replace compile-worker `onSpinWait` with park/backpressure | B | F: spin in `CompileTask` |
| 17 | Cap / defer `compileSync` on render thread | B | F: NEARBY distSqr < 768 |
| 18 | Staging full → explicit wakeup, not spin | B | F: `tryAppend` null → spin |
| 19 | Entity `RenderState` arenas (vanilla family first) | A | F: new state per entity per frame |
| 20 | BE render-state pool | A | F: same pattern |
| 21 | Particle scratch quaternion/vectors | A | F: ~5 allocs / quad in prepare |
| 22 | Skip empty particle phase prepare | A | F: dual submit always |
| 23 | Reuse particle frustum | A | F: `new Frustum().offset(-3)` |
| 24 | RGSS endpoint specialization | C | F: both filters always run; H: GPU <3% reject |
| 25 | Translucent retained commands | B/C | F: order reversed; Iris-sensitive — **after** opaque |
| 26 | Incremental occlusion graph | C | F: 8-block / FOV full rebuild |
| 27 | Coarser frustum 2° threshold | C | pop-in risk |
| 28 | Narrow `setBlockDirty` 3³ fan-out | C | lighting/mesh seams |
| 29 | Skip translucency resort | C | water/glass order |
| 30 | Replace uber-buffer allocator | C | huge compatibility surface |
| 31 | Native/Panama mesher | B | only if Java mesher still hot |
| 32 | Palette snapshot sharing | B | must version-stamp |
| 33 | Light/tint generation stamps | B | correctness boundary |
| 34 | Memoize `hasAllNeighbors` | B | 8 FULL chunk probes |
| 35 | Typed entity extract arenas beyond Living | A | after common family |
| 36 | GoalSelector / AI perception | sim | after render; profile first |
| 37 | Pathfinding / POI | sim | profile first |
| 38 | Entity tracking | sim | profile first |
| 39 | Scheduled / random ticks | sim | profile first |
| 40 | Block entity ticks | sim | profile first |
| 41 | Redstone | sim | profile first |
| 42 | Restore matrix/layer/dirty micro-Mixins | — | **rejected** (RTX 3090) |
| 43 | New Vulkan renderer from scratch | — | **out of scope** |
| 44 | Bypass frame-graph / shader stages | — | **forbidden** |
| 45 | Lower resolution / cut particles | — | **not same image** |
| 46 | Approximate culling | — | **forbidden** |
| 47 | JNI per-block / per-face | — | **forbidden** |
| 48 | Broad `@Overwrite` of LevelRenderer | — | avoid; fail open |
| 49 | Enable retained path under Iris/Sodium | — | disable module only |
| 50 | Collision micro-pass #7 | sim | already −21.8% tick; do not chase unless new JFR leaf |

---

## 5. Top 10 implementation targets (this mission)

1. **Measurement** of prepare / command / submit CPU, draws, sections, UBOs, rebuilds, GPU time, frame time — independently.
2. **Retained opaque terrain prototype** behind a gate; vanilla path intact.
3. **Section metadata table** + `sectionMetadataIndex`; legacy UBO fallback.
4. **Persistent command slots** with a real invalidation table.
5. **Multi-draw / indirect opaque** using detected GL/VK features.
6. **Feed existing Vulkan backend** (no new engine); measure GL and VK separately.
7. **Retain `prepareChunkRenders` producer** (arrays, generations) — render-thread CPU is the metric.
8. **Render snapshots** (`RenderRegionCache` / `SectionCopy`) with version stamps; no stale meshes.
9. **Java `SectionCompiler`** exact-equivalence corpus before any native mesher.
10. **`SectionTaskDynamicQueue`** heap + lazy cancel + no staging spin.

RGSS, entities, and simulation are **later**, separately gated. Simulation collision stack stays.

---

## 6. Proposed path to ≥25%

This is a **hypothesis**, not a claim.

| Stage | Keep if |
|---|---|
| Opaque retained + table + commands | ≥10% terrain prep/submit CPU **or** ≥5% avg FPS in a CPU/submission-limited terrain scene |
| + multi-draw/indirect | additional credible FPS or GPU/CPU submit drop |
| + snapshots / Java mesher / scheduler | only if those leaves are hot in the same workload |
| Entity arenas | only if extraction CPU moves FPS/1% low |
| Simulation | already measured; do not spend a major pass without a new profile |

If retained submission is **not** the bottleneck after measurement: **change direction**. Do not stack speculative micro-gains.

---

## 7. Shader compatibility boundary (policy)

Ultima may replace **opaque terrain pipelines/shaders it owns** (`ultima:core/terrain_retained`). Vanilla `core/terrain` remains for translucent and for fallback.

If Iris, Sodium, Canvas, or another renderer replaces `prepareChunkRenders`, `ChunkSectionsToRender`, or core terrain shaders: **disable only `retained_terrain` (and other client renderer modules)**. Simulation modules stay.

Same image: identical geometry, lighting, atlas, RGSS/nearest choice, fade, fog. Different uniform *binding* is allowed; different pixels are not.
