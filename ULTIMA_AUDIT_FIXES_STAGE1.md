# Ultima audit fixes — stage 1

Forensic audit follow-up: correctness of fail-open, symmetric terrain CPU A/B, honest sync
counters, command-growth observability, and default/experimental classification.

This is **not** a performance-measurement pass. No FPS claim is made.

## Environment

| Item | Value |
|---|---|
| Starting SHA | `93ce4292719ee161e3ce7d1e4cf0ca25d15d1c81` |
| Final SHA | *(git SHA of this documentation revision; see `git log -1`)* |
| Branch | `cursor/forensic-review-9efc` |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.156.0+26.2 |
| Java | 25.0.4 (Temurin) |
| Gradle | 9.5.1 |
| Build | `bash scripts/check.sh` **BUILD SUCCESSFUL**; `forensicRegressionTest` passed (including shaderc natives); summarizer `--self-test` passed |
| Discrete GPU client test | **not run** |

HEAD at task start matched the audited SHA. Working tree was clean. No merge to `main`.

## Audit findings

### D1 — fail-open must not cancel vanilla on failed submit

**Status: FIXED** (with a documented same-frame geometry residual)

**Code change:**
- `RetainedTerrainRenderer.submitOpaque` returns `boolean`.
- `retained_terrain.ChunkSectionsToRenderMixin` cancels vanilla opaque iff
  `RetainedOpaqueSubmitDecision.shouldCancelVanillaOpaque(submitted)` is true.
- Exceptions are still logged through `failOpen`; the mixin no longer treats a
  swallowed submit exception as success.
- Sticky fail-open for later frames is unchanged (`failedOpen` → prepare returns
  null → vanilla `prepareChunkRenders` runs).

**Test:** `AuditStage1Checks.testFailOpenCancellationDecision`
- success → cancel
- failure before any draw → do not cancel
- failure after at least one group → do not cancel
- reason string preserved (log path)

**Remaining risk:**
If retained **prepare already succeeded** and replaced vanilla opaque draw lists,
a later submit exception still does not cancel vanilla `renderGroup(OPAQUE)`, but
that object may contain only translucent draws. Vanilla can therefore draw no
opaque geometry **this frame**. Later frames are full vanilla prepare.

Tradeoff: if a failed submit already issued partial GPU draws **and** vanilla
still has opaque lists, the failed frame may overdraw rather than miss terrain.
Overdraw is preferred to a cancelled empty pass. Reconstructing vanilla opaque
lists on every retained frame would pollute the next RTX CPU/FPS A/B, so it was
not added.

### D2 — retained OFF/ON CPU metrics must be symmetric

**Status: FIXED**

**Code change:**
- `TerrainCpuPhases` nested begin/end clock.
- Comparable buckets: `terrainPrepareCpuNs`, `terrainOpaqueSubmitCpuNs`,
  `terrainTranslucentSubmitCpuNs`, `terrainTotalCpuNs`.
- `terrainCommandCpuNs` is diagnostic (subset of prepare on retained).
- Retained prepare/submit self-time so Mixin `HEAD` cancel cannot drop opaque
  submit CPU.
- `terrain_metrics` Mixins split `renderGroup` by `OPAQUE` vs other groups.
- Mixin priority/order: metrics wrap retained (`priority` 900 vs 1100).
- `beginFrame` zeros last-frame values.

**Test:** `AuditStage1Checks.testTerrainCpuTimingModel`
- vanilla prepare/opaque/translucent
- retained success (command nested in prepare; total does not double-count)
- fail-open sequential attempt + vanilla fallback
- nested mixin+retained opaque counts once
- `beginFrame` drops stale totals
- begin/end pairing balanced

**Remaining risk:** Mixin `RETURN` after `ci.cancel()` is implementation-defined.
Self-timing inside retained methods is the source of truth. `timingPairingBalanced`
is exported so a GPU run can see imbalance.

### D3 — stop presenting tautological sync counters as GPU-stall proof

**Status: FIXED**

**Code change:**
- JSON names: `ultimaIssuedMapCalls`, `ultimaIssuedUnmapCalls`,
  `ultimaIssuedFenceWaitNs`, `ultimaIssuedMapWaitNs`.
- Explicit flags: `syncCountersScope=ultima_issued_only`,
  `driverImplicitSyncObserved=false`,
  `writeToBufferMayInsertBackendBarriers=true`.
- Added `writeToBufferBytes`, split `metadataDirtyRanges` /
  `commandDirtyRanges`.
- Async GPU timestamps (`gpuTerrainNs`) unchanged.
- Summarizer prints write churn next to GPU terrain time / P99 / FPS and a
  NOTE that fence-wait 0 is not “no synchronization”.

**Test:** summarizer self-test plus JSON field presence in the timing model
docs below. No fake driver-stall counter was invented.

**Remaining risk:** Minecraft 26.2 `CommandEncoder.writeToBuffer` may still
insert GL `bufferSubData` sync or Vulkan staging copies/barriers. Ultima cannot
count those honestly. Infer them from `gpuTerrainNs`, frame P99, and a GPU
profiler on the RTX host.

### D4 — command growth must be measurable

**Status: OBSERVABLE**

**Important: compaction deliberately not implemented yet.**

**Code change:** per-frame and JSON:
`submitGroupCount`, `totalCommandRecords`, `liveCommandRecords`,
`hiddenZeroInstanceCommands`, `liveToTotalRatio`, `largestSubmitGroupCommands`,
`commandArrayCapacity`, `commandBufferCapacityBytes`, `commandBufferReallocs`,
`commandArrayReallocs`, `commandsAdded`, `commandsRemoved`, `visibilityToggles`,
plus high-water `maxTotalCommandRecords`, `maxHiddenCommands`, `minLiveRatio`,
and sample-window `firstSample*` / `lastSample*` plus heuristic
`commandPopulationGrewWhileLiveBounded`.

CPU list extracted to `SubmitCommandList` (swap-remove unchanged).

**Test:** G1/G3/D4 accounting in `AuditStage1Checks`.

**Remaining risk:** heuristic flag is not a compaction policy. The RTX
chunk-flight JSON must be read before any threshold is chosen.

### D15 — benchmark default/experimental classification

**Status: FIXED**

**Code change:**
- `UltimaModules.Kind`: `SHIPPED_DEFAULT` / `OPT_IN_EXPERIMENT` / `INSTRUMENTATION`.
- Benchmark JSON `moduleClass` from that registry.
- `summarize-client-bench.py` uses `moduleClass` (fallback: `enabledByDefault` +
  instrumentation keys). Enabling `entity_section_lookup`,
  `block_collision_shape`, or `collision_shell_skip` on a default ON side no
  longer warns.
- Self-test parses `UltimaModules.java` so those three cannot silently drift
  back to “experimental”.

**Test:** `AuditStage1Checks.testModuleClassification` + Python self-test.

**Remaining risk:** old JSON without `modules[]` still uses
`abProtocol.requestedRole == enabled` only.

## Documentation reconciliation

Stale contradictions fixed (history preserved; current code wins):

| Location | Stale claim | Current truth |
|---|---|---|
| `ULTIMA_FOUNDATION_REWORK_REPORT.md` §7 automated checks | `gl_BaseInstance` compiles; `gl_BaseInstanceARB` must fail | Opposite: ARB compiles; unsuffixed is undeclared. Live test unchanged. |
| Same file table + E9 | “GL define / VK core BaseInstance” | Shipped shader uses `gl_BaseInstanceARB` on both. |
| `README.md` | “no production vanilla-renderer Mixin left” | Default-on `terrain_metrics` and `temporal` Mixins; auto-off with Sodium/Iris/Canvas. |
| `REAL_PERFORMANCE_REPORT.md` | only `client_benchmark` client Mixin | Same correction; 21.8% tick A/B **not** altered. |
| `ARCHITECTURAL_AUDIT.md` / `PERFORMANCE_REPORT.md` / `REVIEW_GPT56.md` | those three sim modules opt-in/disabled | Historical; live defaults are on. Banner added. |
| `ULTIMA_LOCAL_RECON_REPORT.md` | `gl_DrawID` / `gl_BaseInstance` indexing | `gl_BaseInstanceARB`. |
| `ULTIMA_RENDER_ARCHITECTURE.md` fallback | cancel vanilla after any submit attempt | Cancel only on submit success. |
| Foundation sync prose | `fenceWaitNs=0` as happy-path proof | Ultima-issued only. |

OLD retained prototype (mapped UBO + DrawIDARB, RTX 3090 −34% / −46% FPS) remains
in `ULTIMA_FOUNDATION_REWORK_REPORT.md` §1 as historical evidence.
CURRENT foundation rework is the persistent table + ARB BaseInstance path, still
unmeasured on a GPU.

## New benchmark metrics

Timing model `ultima_stage1_symmetric_v1`. Units are nanoseconds unless noted.

| Name | Unit | Scope | A/B comparable? | Interpretation | Limitations |
|---|---|---|---|---|---|
| `terrainPrepareCpuNs` / `terrainPrepareCpuNsAvg` | ns | `prepareChunkRenders` or retained prepare | **yes** | CPU to build this frame’s terrain submit inputs | Includes retained command gen when ON |
| `terrainOpaqueSubmitCpuNs` / `Avg` | ns | vanilla `renderGroup(OPAQUE)` or retained `submitOpaque` | **yes** | Opaque encode/submit CPU | Nested mixin+retained counts once |
| `terrainTranslucentSubmitCpuNs` / `Avg` | ns | vanilla `renderGroup` non-opaque | **yes** | Translucent (and any other non-opaque group) | Same path OFF and ON |
| `terrainTotalCpuNs` / `Avg` | ns | prepare + opaque + translucent | **yes** | High-level terrain CPU for the frame | Does **not** add command ns a second time |
| `terrainCommandCpuNs` / `Avg` | ns | retained command dirtying | **no** | Diagnostic subset of prepare | 0 when OFF |
| `prepareNs` / `submitNs` | ns | aliases | yes if using stage1 JSON | `submitNs` = opaque+translucent | Pre-stage1 `submitNsAvg` ON was translucent-only — do not compare to old JSON |
| `ultimaIssuedMapCalls` | count | Ultima | **no** | Map calls Ultima issued | Always 0 on the writeToBuffer path |
| `ultimaIssuedUnmapCalls` | count | Ultima | **no** | Unmap calls Ultima issued | Same |
| `ultimaIssuedFenceWaitNs` | ns | Ultima | **no** | Fence waits Ultima issued | 0 ≠ no driver stall |
| `ultimaIssuedMapWaitNs` | ns | Ultima | **no** | Map waits Ultima issued | 0 ≠ no driver stall |
| `writeToBufferCalls` / `Bytes` | count / bytes | Ultima `writeToBuffer` | diagnostic | Upload churn to correlate with GPU/P99/FPS | Backend barriers not counted |
| `metadataDirtyRanges` | count | section table runs | diagnostic | Coalesced table uploads | |
| `commandDirtyRanges` | count | command buffer runs | diagnostic | Coalesced command uploads | |
| `headerWrites` | count | header UBO | diagnostic | Camera/atlas header | |
| `sectionTableSlotsWritten` | count | table texels | diagnostic | Dirty slots flushed | |
| `immutableCommandWrites` | count | commands | diagnostic | Mesh/range rewrites | |
| `visibilityCommandWrites` | count | commands | diagnostic | instanceCount 0/1 patches | |
| `bufferReallocs` / `commandBufferReallocs` / `commandArrayReallocs` | count | GPU/CPU | diagnostic | Growth | |
| `gpuTerrainNs` | ns | async timestamp | diagnostic | Retained opaque GPU time, lagged rotations | Not a CPU A/B; unsupported → 0 |
| `submitGroupCount` | count | retained groups | **no** | Groups this frame | 0 when OFF |
| `totalCommandRecords` | count | all command slots | **no** | Includes hidden zero-instance | Growth signal |
| `liveCommandRecords` | count | instanceCount>0 | **no** | Visible draws | |
| `hiddenZeroInstanceCommands` | count | instanceCount=0 | **no** | Hidden but retained | |
| `liveToTotalRatio` | ratio | live/total | **no** | Drops if hidden accumulate | |
| `largestSubmitGroupCommands` | count | max group | **no** | | |
| `commandArrayCapacity` | slots | CPU SoA | **no** | | |
| `commandBufferCapacityBytes` | bytes | GPU indirect | **no** | | |
| `commandsAdded` / `Removed` / `visibilityToggles` | count | this frame | **no** | | |
| `maxTotalCommandRecords` / `maxHiddenCommands` / `minLiveRatio` | mixed | session high-water | **no** | Across sampled frames | Reset at warmup end |
| `firstSampleTotalCommands` / `lastSampleTotalCommands` (+ live) | count | sample window | **no** | Chunk-flight growth | |
| `commandPopulationGrewWhileLiveBounded` | bool | heuristic | **no** | Total grew, live relatively flat | **Not** a compaction trigger |
| `syncCountersScope` | enum | documentation | n/a | `ultima_issued_only` | |
| `driverImplicitSyncObserved` | bool | documentation | n/a | always false here | |
| `moduleClass` | enum | each module | n/a | `shipped_default` / `opt_in_experiment` / `instrumentation` | Authoritative with `UltimaModules` |
| `timingPairingBalanced` | bool | this frame | diagnostic | begin/end matched | |
| `failOpenThisFrame` | bool | this frame | diagnostic | retained failed open | |
| `opaqueSubmitSucceeded` | bool | this frame | diagnostic | vanilla opaque cancelled only if true | |

Observer overhead: extra nested `begin` does not call `nanoTime` when depth>0.
Per-frame command tallies are O(submit groups). OFF and ON both run
`terrain_metrics` when that module is on.

## Tests added

| Test | Invariant |
|---|---|
| `testFailOpenCancellationDecision` | G4 / D1 cancel only on full success |
| `testSubmitGroupSwapRemove` | G1 owner remap, dirty follows move, liveDraws, hidden keep |
| `testViewAreaSlotIdentityReset` | G2 same index + different node removes old layers |
| `testVisibilitySequences` | G3 visible→hidden→visible→hidden; hidden remove |
| `testCommandGrowthAccounting` | D4 add/remove/growth/ratio |
| `testTerrainCpuTimingModel` | D2 flows, pairing, no stale, no double-count |
| `testHeaderLayout` | G5 std140 offsets vs `DynamicUniforms.CHUNK_SECTION_UBO_SIZE` |
| `testModuleClassification` | D15 shipped vs experiment vs instrumentation |
| Python summarizer self-test | default A/B must not warn; retained ON must warn; parse Java defaults |

## What was deliberately NOT implemented

- command compaction
- mesher / vertex-format work
- visibility redesign
- DLSS / FSR / XeSS / Frame Generation / jitter / motion-vector targets
- GPU-driven culling / Hi-Z / mesh or task shaders
- entity renderer rewrite
- translucent retained rendering
- new simulation modules
- Panama/JNI meshing
- expanding `retained_terrain` beyond fail-open/metrics/observability

## Performance statement for this pass

The benchmark path can now measure terrain CPU OFF vs ON with equivalent
prepare + opaque submit + translucent submit scopes, and can observe write
churn and command-population growth. No FPS delta is claimed.

## Ready for Prompt #2?

Yes, after `scripts/check.sh` is green on this commit, with the residual D1
same-frame empty-opaque risk and unobserved driver barriers listed above.
Do not start Prompt #2 in this pass.
