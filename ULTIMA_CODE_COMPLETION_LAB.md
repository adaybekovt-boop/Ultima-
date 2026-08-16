# Ultima parallel code-completion lab

Isolated experimental native-performance stack. Not the forensic-review branch.
Not a performance claim. Hardware A/B has **not** been run.

```text
EXPERIMENTAL CODE LAB
NO PERFORMANCE CLAIMS
DO NOT MERGE BEFORE RTX 3090 VALIDATION
```

Verdict for this branch:

```text
ARCHITECTURE IMPLEMENTED
COMPILE PASS
CORRECTNESS TEST PASS
HARDWARE PERFORMANCE UNKNOWN
```

Lab branch: `cursor/ultima-code-completion-lab-4423`
(Cloud policy required the `-4423` suffix; do not push to `cursor/forensic-review-9efc`.)

---

## Stage table

| Stage | Feature gate | Implementation status | Correctness status | Build status | Hardware A/B status | Merge eligibility |
|---|---|---|---|---|---|---|
| Lab gates / interfaces | *(registry only)* | IMPLEMENTED | PASS (gate tests) | see `scripts/check.sh` | NOT TESTED | NO — pending hardware validation |
| A — data-oriented mesher | `data_mesher=false` | IMPLEMENTED | PASS (visit-plan + seed + equivalence rule). GPU tessellation byte dumps are **not** in this host | see `scripts/check.sh` | NOT TESTED | NO — pending hardware validation |
| B — compact terrain vertices | `compact_terrain_vertices=false` | IMPLEMENTED | PASS (encode/decode, conversion, uber-align, fail-closed policy). GPU fetch/shader is **not** runtime-tested here | see `scripts/check.sh` | NOT TESTED | NO — pending hardware validation |
| C — command compaction | `command_compaction=false` | IMPLEMENTED | PASS (pack oracle, policy, owner rewrite) | see `scripts/check.sh` | NOT TESTED | NO — pending hardware validation |
| D — retained / incremental visibility | `retained_visibility=false` | IMPLEMENTED | PASS (bitset vs vanilla oracle, camera-path analog) | see `scripts/check.sh` | NOT TESTED | NO — pending hardware validation |
| E — future GPU visibility interface | *(no gate; contract only)* | IMPLEMENTED (minimal) | PASS (mask copy / clip) | see `scripts/check.sh` | NOT TESTED | NO — pending hardware validation |
| Temporal / DLSS | — | NOT MODIFIED | — | — | NOT TESTED | — |
| Custom Vulkan backend | — | NOT ADDED | — | — | NOT TESTED | — |

Hardware A/B status for this task: **NOT TESTED**.
Merge eligibility for every performance-changing module: **NO — pending hardware validation**.

---

## Feature gates (all default OFF)

```text
data_mesher=false
compact_terrain_vertices=false
command_compaction=false
retained_visibility=false
```

Dependency graph (explicit; nothing is silently implied):

```text
data_mesher                     independent
                                if both requested, data_mesher wins and java_mesher yields
compact_terrain_vertices        requires retained_terrain
                                independent of data_mesher
command_compaction              requires retained_terrain
retained_visibility             requires retained_terrain
```

`compact_terrain_vertices` does **not** require `data_mesher`. Compact conversion runs on vanilla BLOCK meshes at uber upload. GPU compact heaps cannot be fail-opened onto vanilla SOLID/CUTOUT pipelines.

Sodium / Iris / Canvas auto-disable every lab module (and `retained_terrain`). Unknown custom renderers are not allow-listed; they get vanilla fallback unless they happen to leave vanilla `SectionCompiler` / retained hooks intact.

---

## Vanilla 26.2 meshing hot path (Stage A oracle)

`SectionCompiler.compile`:

1. Region is a 3×3×3 `RenderSectionRegion` of `SectionCopy` snapshots (`BlockAndTintGetter`).
2. Interior walk is `BlockPos.betweenClosed` on the 16³, **x-fastest**, then y, then z.
3. Air cells are skipped.
4. `VisGraph.setOpaque` for `isSolidRender`.
5. Block-entity side table via `handleBlockEntity`.
6. Fluid tessellation (`FluidRenderer.tesselate`) when fluid is non-empty.
7. Model tessellation (`ModelBlockRenderer.tesselateBlock`) when `RenderShape.MODEL`.
8. Local coordinates are `SectionPos.sectionRelative` (section-local, not world).
9. Seed is `blockState.getSeed(pos)` (default `Mth.getSeed`; beds/doors/double plants override).
10. Quads go to `BufferBuilder.putBlockBakedQuad` on the layer from `quad.materialInfo().layer()`, except `forceOpaque(cutoutLeaves)` which emits SOLID.
11. Light and biome tint stay on the region.
12. Translucent mesh is sorted; visGraph is resolved; builders are worker-owned `SectionBufferBuilderPack`.

Default `BLOCK` vertex is **28 bytes**: float3 pos, rgba8, float2 uv, short2 light. **No normal.** Positions in the mesh are section-local.

Ultima’s data mesher does **not** clone this method as a greedy/binary mesher. It packs the 18³ halo, visits in the same order, and still calls the vanilla tessellators.

---

## Architecture — data mesher (`data_mesher`)

Packages: `dev.ultima.meshing` (pure), `dev.ultima.client.renderer.snapshot`, `dev.ultima.client.renderer.meshing`. Mixin: `dev.ultima.mixin.data_mesher.SectionCompilerMixin`.

### What it is

- Versioned `PackedSectionVolume` (18³, x-fastest, interior 0–15 → packed local+1).
- Palette state IDs + compact `BlockRenderFlags` (AIR / SOLID_RENDER / HAS_BLOCK_ENTITY / HAS_FLUID / MODEL).
- Direct neighbor indexing into the halo; no `BlockPos.betweenClosed` on the inner loop.
- Explicit interior block-entity slot table.
- Worker `ThreadLocal` scratch (snapshot, tessellators, one `MutableBlockPos`, started-layer map).
- Frozen volume after capture; writes throw.

Light and biome tint **delegate to `RenderSectionRegion`** so lighting/tint stay vanilla.

### Equivalence rule

Exact GPU vertex bytes are not compared on this host (no client world tessellation dump). Tests compare **visit plans** against an independent 3D-array oracle (`VanillaVisitOracle` vs `PackedVisitScanner`): state, flags, 6 neighbor IDs, default seed, BE slot.

Canonical vertex rule (`MeshEquivalence`) for later dumps:

1. Per-layer ordered vertices (exact floats / packed ints).
2. Else quad-multiset per layer (winding kept as groups of 4).
3. Else not equivalent. A vertex bag is not parity.

Production visit order matches vanilla, so rule 1 is the expected path.

Greedy/binary meshing is **not** part of this experiment. `OcclusionFaces` is a snapshot adjacency test only.

### Metrics

`snapshotBuildNs`, `meshBuildNs`, `blocksVisited`, `modelCalls`, `fluidCalls`, `verticesEmitted`, `bytesEmitted`, `temporaryAllocationProxy` (thread allocated-bytes delta when the JVM supports it), `rebuildCount`, `workerQueueLatencyNs` (currently **0** — not wired to the compile queue).

---

## Architecture — compact terrain vertices (`compact_terrain_vertices`)

Packages: `dev.ultima.vertex` (codec), `dev.ultima.client.renderer.vertex`. Mixins: `compact_terrain_vertices.SectionRenderDispatcherMixin`, `compact_terrain_vertices.RenderSectionMixin`. Shader: `assets/ultima/shaders/core/terrain_compact.vsh`. Vanilla `DefaultVertexFormat.BLOCK` is **not** mutated.

### Layout (20 bytes)

| Offset | Attribute | Encoding |
|---|---|---|
| 0 | Position.xyz + unused w | RGBA16_UNORM. `packed = round((local + 32) * 1024)`, range `[-32, 32)`, unit `1/1024` block, max error `1/2048` block |
| 8 | Color | RGBA8 (bit-identical) |
| 12 | UV0 | RG16_UNORM on `[0, 1]`, saturate outside |
| 16 | Light UV2 | RG16_SINT, bit-identical to vanilla |

No vertex normal (vanilla BLOCK has none). Layer/material stay per-draw. Fog and section fade stay in the section table / shader.

Half-float positions were rejected (ulp at local 16 is ~1/64 block).

### Backend

Backend-neutral `VertexFormat` (`GpuFormat` attributes). OpenGL retained path binds it when the gate is on. Mojang Vulkan uses the same pipeline declaration; **Vulkan runtime is not claimed**. No custom Vulkan backend.

TRANSLUCENT stays 28-byte BLOCK on the vanilla submit path. Uber align uses the layer **label** (`solid` / `cutout` / `translucent`), not heap creation order.

### Fail-closed opaque

Compact SOLID/CUTOUT uber heaps are 20-byte. Vanilla opaque pipelines fetch 28-byte BLOCK. If retained cannot submit, vanilla opaque is **cancelled** (skip opaque, do not mis-fetch). Translucent still fail-opens.

### Metrics

`vertexBytesPerVertex` (20), `vanillaBytesPerVertex` (28), `terrainVertexBytesResident` (**cumulative converted bytes**, not a live VRAM query), `uploadBytes`, `vertexCount`, clamp/saturation counters. GPU terrain timing remains the existing retained `gpuTerrainNs` when the timer path is present.

---

## Architecture — command compaction (`command_compaction`)

Packages: `dev.ultima.command`, `dev.ultima.lab.CommandCompactionPolicy`. Hook: `SubmitGroup.compactIfNeeded()` after `hideUnseenSlots`, before `pruneDeadGroups` / GPU flush. No extra Mixins.

### Policy (explicit, not a hidden magic cutoff)

Compact iff `dead > 0` AND `dead >= minDead` AND `dead/total >= threshold`.

Defaults: **0.50 / 64**. Override:

```text
-Dultima.command_compaction.dead_ratio=0.5
-Dultima.command_compaction.min_dead=64
```

Zero dead never compacts, even if threshold is 0.

### What is preserved

Stable pack of `instanceCount > 0`. Relative live order. `firstIndex`, `indexCount`, `baseVertex`, `sectionSlot`/`firstInstance`, visibility (`instanceCount`), group ownership. Owner `commandIndex` rewritten; dropped owners get `group=null`, `commandIndex=-1` so a later recapture `add()`s. Dirty prefix is the compacted live range.

### Metrics

`compactionCount`, `commandsBefore`, `commandsAfter`, `compactionCpuNs`, `bytesRewritten`, `maxDeadRatio`.

Intended later test: long chunk-flight (streaming) where zero-instance commands accumulate.

---

## Architecture — retained / incremental visibility (`retained_visibility`)

Packages: `dev.ultima.visibility`. Hook: `RetainedTerrainRenderer.prepare`. Vanilla `visibleSections` remains the oracle. No approximate occlusion. No Hi-Z.

### Behavior

- Persist vanilla visible slots as a bitset keyed by `ViewArea` slot (`RenderSection.index`).
- FULL rebuild on slot-count change (render distance) or `reset()` (dimension / renderer close).
- INCREMENTAL: recapture opaque iff FULL or newly visible **or** mesh/origin/fade/sectionNode changed.
- Still-visible unchanged: `keepOpaqueVisible` (no full `captureOpaque`).
- Translucent is always captured (vanilla path).
- Fail-open: if the module is off, the previous retained walk/capture path runs unchanged.

### Differential tests

Stationary, yaw/pitch/translation analog, teleport, load/unload, render-distance slot growth, dimension `reset`, 200-frame random oracle equality.

### Metrics

`visibilityCpuNs`, `sectionsConsidered`, `sectionsVisible`, `visibilityStateChanges`, `fullRebuilds`, `incrementalUpdates`.

---

## Architecture — future GPU visibility (Stage E only)

`dev.ultima.visibility.gpu.GpuVisibilityContract`:

```text
visibility source → compact command visibility mask → indirect command generation/compaction
```

Copies the vanilla-visible bitset; clips to `slotCount`. No mesh shaders, no Hi-Z, no vendor path.

---

## Adversarial self-review

### Stage A — data mesher

| Question | Answer |
|---|---|
| Work actually removed? | `BlockPos.betweenClosed` iterator and repeated `region.getBlockState` on air cells after flags are packed. Vanilla tessellators are **not** removed. |
| Work merely moved? | Neighbor/state reads happen once into an 18³ snapshot, then again from the palette during tessellation (`getBlockState` on the snapshot). Tessellation is still vanilla. |
| New overhead? | 18³ capture (5832 cells) including halo air; palette intern (`IdentityHashMap`); flag packing; metrics timers; ThreadLocal scratch. Empty sections pay snapshot cost they did not pay as a single 16³ air skip. |
| What can regress FPS? | Snapshot cost on sparse sections; extra getBlockState during capture; cache misses on packed arrays vs vanilla region. **UNKNOWN until A/B.** |
| Visual parity risk? | Snapshot vs live region for queries **beyond** the 1-block halo (falls back to the same `RenderSectionRegion`). Seed/model/light/tint still vanilla. Visit-plan tests do not prove GPU vertex bytes. |
| Mod compatibility? | Cancels `SectionCompiler.compile` when enabled. Sodium/Iris/Canvas auto-disable. Other compiler mixins can still conflict. |
| Unbounded growth? | Worker ThreadLocal palette/intern capacity tracks the worst section seen on that worker; intern is cleared each capture. |
| OpenGL testing? | Visual parity of tessellated meshes vs vanilla. |
| Vulkan testing? | Same meshes; no unique Vulkan path. |

### Stage B — compact vertices

| Question | Answer |
|---|---|
| Work actually removed? | 8 bytes/vertex on SOLID/CUTOUT uber heaps (28→20). |
| Work merely moved? | CPU convert-copy at upload (`convertVanillaBlock` allocates a new buffer). |
| New overhead? | Per-upload conversion; extra shader unpack (`* 65535/1024 - 32`); pipeline variant. |
| What can regress FPS? | Conversion CPU; UNORM fetch vs float3; shader unpack. **UNKNOWN until A/B.** |
| Visual parity risk? | Position quantization (max 1/2048 block); UV UNORM16 (~1/65535). OOR positions/UVs clamp. No normal in format (vanilla has none). |
| Mod compatibility? | Custom shaders expecting BLOCK 28 on SOLID/CUTOUT will break. Iris/Sodium/Canvas auto-disable. Shader packs on vanilla pipelines are not supported for compact heaps. |
| Unbounded growth? | Conversion is per upload; compact buffer is transient CPU. Uber heap size is still the vanilla heap cap. |
| OpenGL testing? **Required.** | Geometry shift, UV swim, lighting banding, fail-closed skip of opaque if retained pipelines fail. |
| Vulkan testing? **Required** before any Vulkan claim. | Same format declaration; Mojang backend only. |
| Architecturally unsound piece reworked? | Vanilla fail-open onto 20-byte heaps would be garbage. Opaque is now **fail-closed** when compact is on and retained is not ready. Uber align keys off layer label, not heap index. |

### Stage C — command compaction

| Question | Answer |
|---|---|
| Work actually removed? | Zero-instance indirect commands after threshold (dead slots no longer submitted / stored). |
| Work merely moved? | Hide still writes `instanceCount=0`; compaction is a later pack. |
| New overhead? | Scan + rewrite when policy fires; full dirty prefix rewrite; owner fixup. |
| What can regress FPS? | Compaction CPU spikes during long flights if threshold is too aggressive. **UNKNOWN until A/B.** |
| Visual parity risk? | Bugs in owner `commandIndex` / `firstInstance` would drop or duplicate sections. Tests cover pack oracle. |
| Mod compatibility? | Only retained submit groups. Off if retained off. |
| Unbounded growth? | This is the mitigation for unbounded dead commands. If the gate is off, dead commands still accumulate (existing retained behavior). |
| OpenGL / Vulkan testing? | Long chunk-flight: live/total ratio, no missing/extra sections, no flicker. |

### Stage D — retained visibility

| Question | Answer |
|---|---|
| Work actually removed? | Full `captureOpaque` for still-visible unchanged slots (slice/baseVertex/group update). |
| Work merely moved? | Vanilla frustum list is still walked every frame. Bitset persist is extra. |
| New overhead? | Bitset ensure/clear/xor/commit; `keepOpaqueVisible` branch. |
| What can regress FPS? | Bitset work on huge render distances; missed recapture causing stale mesh (correctness, not FPS). **UNKNOWN until A/B.** |
| Visual parity risk? | Stale opaque if `opaqueStateChanged` misses a generation. Pop-in if newly visible is not recaptured (tests require recapture). No extra occlusion, so no missing sections vs vanilla frustum. |
| Mod compatibility? | Same retained gate / renderer family. |
| Unbounded growth? | Bitset size = ViewArea slot count. |
| OpenGL testing? | Camera paths, teleport, RD change, dimension change vs vanilla visible set. |
| Vulkan testing? | Same CPU path. |

### Stage E

Interface only. No GPU work removed or added.

---

## What this branch does **not** do

- No DLSS / FSR / XeSS / frame generation.
- No Native temporal passthrough rewrite.
- No custom Vulkan backend.
- No simulation-stack changes.
- No restored deleted client micro-Mixins.
- No greedy meshing in the base data mesher.
- No Hi-Z / mesh-shader renderer.

---

## How to A/B later (causal, one gate at a time)

All lab gates start false. Enable only what the row says. Record `ultima.properties` and bench JSON (`mesherMetrics`, `compactVertexMetrics`, `commandCompactionMetrics`, `visibilityMetrics`, existing `terrainMetrics`).

Recommended order:

1. Baseline: lab gates off, `retained_terrain=false`.
2. `retained_terrain=true` alone (existing architecture; not this lab’s claim).
3. `retained_terrain=true` + `data_mesher=true`.
4. `retained_terrain=true` + `compact_terrain_vertices=true` (OpenGL first, then Vulkan). Confirm fail-closed: if retained pipelines fail, opaque must vanish rather than corrupt.
5. `retained_terrain=true` + `command_compaction=true`, long chunk-flight. Vary `-Dultima.command_compaction.dead_ratio` / `min_dead`.
6. `retained_terrain=true` + `retained_visibility=true`, camera paths listed in Stage D.
7. Combinations only after singles have a verdict. Do not enable four gates and call the mix “the mesher”.

Do not reuse old retained prototype FPS numbers as evidence for these modules.

---

## Known risks

- Data mesher: visit-plan parity ≠ GPU tessellation dump parity.
- Compact vertices: quantization; conversion cost; shader-pack incompatibility; fail-closed hides opaque if retained pipelines fail.
- Compact `terrainVertexBytesResident` is cumulative converted bytes, not queried VRAM.
- Command compaction: threshold too low → CPU spikes; too high → dead commands remain.
- Incremental visibility: still walks the vanilla visible list; only skips opaque recapture.
- Mixin cancel of `SectionCompiler.compile` / opaque `renderGroup` can fight other render mods that are not in the Sodium/Iris/Canvas list.
