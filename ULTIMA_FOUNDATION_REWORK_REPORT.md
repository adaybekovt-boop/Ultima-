# Ultima foundation rework report

Date: 2026-08-16  
Branch: `cursor/forensic-review-9efc`  
Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API `0.156.0+26.2` / Java 25

This pass exists to make retained opaque terrain **actually retained** on the GPU. DLSS, FSR, Frame Generation, mesh shaders, entity-renderer work, and new simulation modules stay blocked until the foundation verdict is PASS on a GPU host.

This measurement host has **no discrete GPU**. OpenGL/Vulkan FPS, GPU utilization, and visual-parity screenshots were **not** collected here. Do not treat any number in this file as a new RTX 3090 result.

---

## 1. Old failure baseline (already measured on RTX 3090)

### OpenGL retained vs vanilla

Corrected stationary:

| Metric | OFF | ON | Delta |
|---|---:|---:|---:|
| AVG FPS | 328.86 | 213.60 | **−34.50%** |
| Terrain prepare CPU | | | **+30.59% worse** |
| Terrain submit CPU | | | **−84.68%** |
| Terrain measured total | | | **−33.97%** |

Deterministic yaw sweep, n=6:

| Metric | OFF | ON | Delta |
|---|---:|---:|---:|
| AVG FPS | 381.33 | 202.77 | **−46.70%** (95% CI −50.04% to −43.37%) |
| 1% low | | | **−34.30%** |
| P99 frame time | | | **+63.99% worse** |
| Terrain measured total | | | −24.44% |
| Terrain submit CPU | | | −76.14% |
| GPU utilization | ~50.35% | ~34.12% | GPU starved |

A subsystem submit-CPU win that costs ~40% whole-frame FPS is not a pass.

### Vulkan

Retained pipelines failed to compile (`gl_DrawIDARB` undefined). Fail-open to vanilla. **No valid Vulkan retained A/B.**

### Visual

OpenGL visual parity for the tested terrain scope passed on that host.

---

## 2. Root-cause analysis

Inspected against Minecraft 26.2 generated sources. The old path was not retained on the GPU:

1. `MappableRingBuffer.rotate()` / `currentBuffer()` fence-waited (`Long.MAX_VALUE`) every frame.
2. Each batch mapped/unmapped a 256-record UBO and wrote unused padding.
3. Indirect commands were rewritten every frame; `firstInstance` was 0 so the shader used DrawID.
4. The shader required `#extension GL_ARB_shader_draw_parameters` and `gl_DrawIDARB`. Vulkan `GlslCompiler` defines that extension path for shaderc but only remaps `gl_VertexID`→`gl_VertexIndex` and `gl_InstanceID`→`gl_InstanceIndex`. ARB-suffixed draw-parameter builtins are undefined.
5. Extra cost vs vanilla is **not** an extra opaque pass. Vanilla `ChunkSectionsToRender.renderGroup` already creates one encoder + one pass. Starvation was map/fence/rewrite **inside** that pass.
6. `CommandEncoder.writeToBuffer` requires `USAGE_COPY_DST`, cannot run inside a render pass, and is the correct upload path (GL `bufferSubData`, VK staging+copy).

`UniformType` is only `UNIFORM_BUFFER` and `TEXEL_BUFFER`. Texel buffers must be the entire buffer. Clouds already use `TEXEL_BUFFER`. `GpuFormat.RGBA32_SINT` → `isamplerBuffer` / `texelFetch` → `ivec4`.

---

## 3. Architecture changes

| Change | Why |
|---|---|
| Persistent section texel table, one texel per `ViewArea` slot | Camera motion must not rewrite section origins |
| Persistent `SubmitGroup` indirect buffers | Yaw must not rebuild immutable commands |
| `instanceCount` 0/1 for frustum occupancy | Visibility is a one-int patch |
| Shared `ChunkSection` header UBO | One camera/atlas write, skipped when unchanged |
| `writeToBuffer` dirty ranges only | Replaces map/unmap + 256-record padding |
| `firstInstance = RenderSection.index` | Shader indexes with `gl_BaseInstance` / `gl_BaseInstanceARB` |
| OpenGL compile define `ULTIMA_GL_DRAW_PARAMETERS` | ARB suffix only on OpenGL |
| Drop multi-draw-direct-only modes | Those APIs cannot supply per-draw base instance |
| One encoder; uploads before `createRenderPass` | Same single opaque pass as vanilla |
| Async timestamp queries, 3-rotation pool | GPU time without stalling the current frame |
| Upload/sync counters in benchmark JSON | Explain whole-frame cost, not just Java submit ns |

`OpaqueDrawBatch` and the fingerprint recycle path are deleted.

---

## 4. Buffer lifetime design

| Buffer | Usage | Lifetime | Update |
|---|---|---|---|
| `ChunkSection` header | `USAGE_UNIFORM \| USAGE_COPY_DST` | Renderer session | Dirty when `ModelViewMat` or atlas size changes |
| `UltimaSectionTable` | `USAGE_UNIFORM_TEXEL_BUFFER \| USAGE_COPY_DST` | Grows with `ViewArea` slot count; rewritten on realloc | Dirty per slot on origin/fade/identity change |
| Indirect commands per `SubmitGroup` | `USAGE_INDIRECT_PARAMETERS \| USAGE_COPY_DST` | Lives with the (layer, vertex, index) group | Dirty per command on mesh change or visibility 0/1 |
| Vanilla section meshes | unchanged `UberGpuBuffer` | Vanilla compile/upload | Ultima stores handles + offsets only |

CPU shadows: `int[]` section table, SoA command arrays, `BitSet` dirty runs coalesced by `BitSetRuns`.

Happy-path stationary after warmup: header write 0, table writes 0, immutable command writes 0, map/unmap 0, fence wait 0.

---

## 5. Command lifetime design

A command record is one draw in a `SubmitGroup`:

- Immutable: `indexCount`, `firstIndex`, `baseVertex`, `firstInstance` (section slot)
- Dynamic: `instanceCount` (0 hidden, 1 visible)

| Event | Action |
|---|---|
| New visible mesh | `add` |
| Same group, new index range | `updateImmutable` |
| Buffer identity change | leave old group, `add` to new group |
| Not in `visibleSections` / null slice | `setVisible(false)` — do **not** destroy the command |
| `sectionNode` wrap | `resetIdentity` → remove + add |
| Closed vertex/index buffer or empty group | prune and close GPU command buffer |
| Level close / renderer reset | drop all groups and GPU objects |

Yaw sweep therefore touches only `instanceCount` on commands that entered or left the vanilla frustum list.

---

## 6. OpenGL shader path

`terrain_retained.vsh` compiles with `ULTIMA_GL_DRAW_PARAMETERS`:

```glsl
#ifdef ULTIMA_GL_DRAW_PARAMETERS
#extension GL_ARB_shader_draw_parameters : enable
#endif
```

`section_table.glsl` (no `#version`; imported) returns `gl_BaseInstanceARB` under that define. Pixel math is still vanilla terrain + fade from the table.

Automated checks: source scan forbids `gl_DrawID` / `gl_DrawIDARB`; require the ifdef and `gl_BaseInstanceARB`.

Runtime: `device.precompilePipeline` on the OpenGL backend. Failure → fail-open.

---

## 7. Vulkan shader path

Same files. Vulkan compile does **not** set the define, so the shader uses core `gl_BaseInstance`.

Automated checks (when shaderc natives load):

- Vulkan 1.2 snippet using `gl_BaseInstance` + `isamplerBuffer` must compile
- `gl_DrawIDARB` must fail
- `gl_BaseInstanceARB` must fail (proves ARB suffixes are not portable)

Runtime: `device.precompilePipeline` on the Vulkan backend. Failure → fail-open. This host did not run a Minecraft Vulkan device.

---

## 8. Map / upload statistics

Wired in `RetainedUploadMetrics` → `terrainMetrics` in the client benchmark JSON:

`mapCalls`, `unmapCalls`, `writeToBufferCalls`, `metadataBytesWritten`, `commandBytesWritten`, `dirtyRanges`, `commandRecordsChanged`, `immutableCommandWrites`, `visibilityCommandWrites`, `bufferReallocs`, `headerWrites`, `sectionTableSlotsWritten`

**Measured on this host:** n/a (no client benchmark JSON).

**Expected after warmup, stationary:** map=0, unmap=0, header=0, table slots=0, immutable commands=0.

**Expected yaw sweep:** map=0, immutable commands=0, visibility command writes = sections that entered/left the frustum.

---

## 9. Synchronization statistics

Counters: `fenceWaitNs`, `mapWaitNs`, `renderPasses`, `encoders`.

Happy path does not call `GpuBuffer.map` and does not wait on a ring fence. `writeToBuffer` is the only upload.

**Measured on this host:** n/a.

**Expected:** `renderPasses=1`, `encoders=1` per opaque submit (same as vanilla opaque `renderGroup`). `fenceWaitNs=0`, `mapWaitNs=0`.

---

## 10. GPU timing

`RetainedGpuTimers` allocates a 3-slot timestamp query pool and writes begin/end on the opaque pass. Readback is the oldest rotation; the current frame is never stalled for queries.

JSON: `gpuTerrainNs`, `gpuTimingSupported`.

**Measured on this host:** n/a. `gpuTimingSupported` is unknown until a GPU client run.

---

## 11. OpenGL A/B

**Not measured.** This VM has no discrete GPU. Previous RTX 3090 numbers describe the **old** mapped/DrawID path, not this rework.

Required on a GPU host before a PASS verdict:

1. `terrain_metrics=true` on both sides
2. Stationary Fancy RD16+ : `retained_terrain=false` vs `true`
3. Deterministic yaw sweep n≥6
4. Compare AVG FPS, 1% low, P99, prepare/submit CPU, `gpuTerrainNs`, GPU util, map/write/command counters
5. Confirm `retainedActive=true` and a valid submit mode (`indirect` / `indirect_single` / `base_instance_loop`)

---

## 12. Vulkan A/B

**Not measured.** No Minecraft Vulkan device on this host.

Required: logs must show `retainedActive=true` and a valid submit mode. Fail-open samples do not count.

---

## 13. Visual parity

**Not re-run.** Prior OpenGL parity for the old path is not evidence for this path.

Must cover opaque, CUTOUT foliage, chunk boundaries, near/far, lighting, water adjacent to retained terrain, camera motion, and chunk streaming. Vulkan needs its own pass once retained is active.

---

## 14. Lifecycle / stability

Compile-time / structural:

- Fail-open on pipeline compile failure, prepare/submit exceptions, Sodium/Iris/Canvas, wireframe debug
- `ShaderManager.apply` invalidates pipelines
- `LevelRenderer.close` resets GPU objects
- Closed mesh buffers prune their submit groups
- Translucent terrain stays on vanilla `RenderPass.Draw` lists

Runtime crash/corruption: **not tested in-game on this host.**

---

## 15. Rejected experiments

| Idea | Why rejected |
|---|---|
| Keep `MappableRingBuffer` but write fewer records | `rotate()` still fence-waits every frame |
| `gl_DrawID` without ARB suffix as the only index | Still backend-specific; `firstInstance` is the portable index we already write |
| Multi-draw-direct without base instance | Cannot fetch the section table per draw |
| Extra opaque render pass | Vanilla already has one; the bug was inside the pass |
| Restore `client_chunk_*` micro-opts | Prior RTX 3090 A/B was −0.28% FPS |
| DLSS / FSR / mesh shaders / entity rewrite | Blocked until foundation PASS |

---

## 16. Final verdict

Architecture and compile/portability work for the foundation is implemented. Whole-frame FPS, GPU utilization, and visual parity are **unmeasured** on this host.

**FOUNDATION VERDICT: REWORK**

Do not start DLSS, FSR, Frame Generation, mesh shaders, or an entity renderer rewrite.

---

```text
ULTIMA RENDER FOUNDATION REWORK COMPLETE

OPENGL
AVG FPS OFF: n/a
AVG FPS ON: n/a
DELTA: n/a

1% LOW OFF: n/a
1% LOW ON: n/a
DELTA: n/a

P99 OFF: n/a
P99 ON: n/a
DELTA: n/a

TERRAIN CPU OFF: n/a
TERRAIN CPU ON: n/a
DELTA: n/a

GPU FRAME / TERRAIN TIME OFF: n/a
GPU FRAME / TERRAIN TIME ON: n/a
DELTA: n/a

GPU UTIL OFF: n/a
GPU UTIL ON: n/a

METADATA MAP CALLS / FRAME: expected 0 (unmeasured)
METADATA BYTES WRITTEN / FRAME: expected 0 stationary after warmup (unmeasured)
INDIRECT COMMAND WRITES / FRAME: expected 0 immutable stationary (unmeasured)
RENDER PASSES / FRAME: expected 1 (unmeasured)

VULKAN
RETAINED ACTIVE: NOT MEASURED (shader path compiles in source/shaderc tests; runtime precompile not run)

AVG FPS OFF: n/a
AVG FPS ON: n/a
DELTA: n/a

1% LOW OFF: n/a
1% LOW ON: n/a
DELTA: n/a

P99 OFF: n/a
P99 ON: n/a
DELTA: n/a

GPU FRAME / TERRAIN TIME: n/a

VISUAL PARITY: NOT MEASURED

STABILITY: COMPILE-ONLY (in-game not run)

PERSISTENT GPU STATE: PASS (architecture)

DIRTY-RANGE UPDATE MODEL: PASS (architecture)

VULKAN PORTABILITY: PASS (source + shaderc contract; runtime device not present)

FOUNDATION VERDICT:
REWORK

PRIMARY REMAINING BOTTLENECK:
Unvalidated on a GPU. The previous bottleneck was map/fence/per-frame UBO+indirect rewrite. This host cannot prove the rewrite removed the whole-frame regression.

NEXT ALLOWED FEATURE:
None until an RTX-class OpenGL+Vulkan A/B makes FOUNDATION VERDICT = PASS. Next work is that A/B, not DLSS/FSR/mesh shaders.
```
