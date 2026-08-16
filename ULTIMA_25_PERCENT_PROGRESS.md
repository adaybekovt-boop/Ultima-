# Ultima 25% progress

Mission: **≥ +25% real average FPS** at identical visual quality.

This file tracks architecture experiments. Allocation-only wins are not keepers.

## Scoreboard (current)

| Metric | Vanilla / OFF | Ultima / ON | Delta | Status |
|---|---|---|---|---|
| Avg FPS | n/a (no GPU A/B this pass) | n/a | n/a | FAIL (not measured) |
| 1% low | n/a | n/a | n/a | — |
| P99 frame | n/a | n/a | n/a | — |
| Terrain prepare/submit CPU | n/a | n/a | n/a | harness ready (`terrainMetrics` in client JSON) |
| Entity-farm mean tick | 8.333 ms | 6.518 ms | **−21.78%** | KEEP (prior, simulation) |
| Visual parity | — | — | — | FAIL OPEN to vanilla |
| `scripts/check.sh` | | | | **BUILD SUCCESSFUL** |
| ≥25% real avg FPS | | | | **FAIL** until GPU A/B |

Prototype keep threshold (not the product target): ≥5% avg FPS **or** ≥10% terrain prepare/render-thread CPU in a CPU/submission-limited terrain scene.

## Benchmark scenarios (for a GPU host)

| ID | Scene | How |
|---|---|---|
| TERRAIN-SUBMISSION | High visible section count, Fancy, RD16+, stationary | `ultima.clientBenchmark.cameraMode=stationary` |
| CHUNK-FLIGHT | Deterministic move through terrain | `cameraMode=chunk_flight` |
| MULTI-LAYER | Foliage/water/solid mixed | same world, look at forest+lake |
| ENTITY | Large visible entity population | existing entity-farm, client extract |
| UPDATE | Block/chunk rebuild pressure | break/place or chunk load while sampling |
| SHADER | GPU-heavy terrain | Fancy + RGSS on; compare `rgss_endpoint` |

Do not use one easy stationary scene for the product claim.

## Experiments

See `REAL_PERFORMANCE_REPORT.md` for the hardware limits of this VM.

| ID | Experiment | Baseline | Implementation | Correctness | CPU | GPU | FPS | 1% low | p95/p99 | Allocations | Draw/command | Compatibility | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E0 | Import recon + architecture contract | n/a | docs | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | **KEEP** (docs) |
| E1 | Terrain measurement counters | uninstrumented | `terrain_metrics` default on (client) | no render change | nanoTime around prepare/submit | gpuFrameNs=0 here | n/a | n/a | n/a | counters only | draws/sections/UBOs | auto-off Sodium/Iris/Canvas | **KEEP** (instrumentation) |
| E2 | Retained opaque + section table + multi-draw/indirect | vanilla `prepareChunkRenders` | `retained_terrain` default off | fail-open; translucent vanilla | not measured (no GPU) | not measured | n/a | n/a | n/a | no per-draw Draw/Matrix4f for opaque | one header UBO + batch table | custom ultima shaders; auto-off replacement renderers | **PENDING GPU A/B** |
| E3 | Render snapshots | per-section `ImmutableMap.copyOf` | `render_snapshot` default off | same extract snapshot; palettes not shared | not measured | n/a | n/a | n/a | n/a | fewer BE maps | n/a | auto-off replacement renderers | **PENDING** |
| E4 | Java mesher packed loop | `BlockPos.betweenClosed` | `java_mesher` default off | visit-order test vs betweenClosed | not measured | n/a | n/a | n/a | n/a | no AbstractIterator | same tessellators | auto-off replacement renderers | **PENDING** |
| E5 | Task queue compact + park | iterator.remove + onSpinWait | `section_task_queue` default off | same nearest/quota policy | not measured | n/a | n/a | n/a | n/a | fewer shifts | n/a | auto-off replacement renderers | **PENDING** |
| E6 | RGSS endpoint specialization | always evaluate nearest+RGSS | `rgss_endpoint` default off, separate module | exact at blend 0 and 1 | n/a | reject if <3% | n/a | n/a | n/a | n/a | n/a | auto-off replacement renderers | **PENDING**; reject if GPU <3% |
| E7 | Temporal Native passthrough | none | `temporal` default on | no pixel change; Native size == output; zero MV when VP unchanged | capture only | n/a | n/a | n/a | n/a | none in Native | n/a | auto-off Sodium/Iris/Canvas; HUD stays vanilla | **KEEP** (architecture; no FPS claim) |
| E8 | Retained command reuse | refill batches every visible walk | skip recycle when fingerprint matches | same draws if set/mesh stable | not measured | n/a | n/a | n/a | n/a | fewer batch array writes | `commandBatchesReused` | same fail-open | **SUPERSEDED** by E9 |
| E9 | Retained foundation rework | mapped 256-record UBO + DrawIDARB + per-frame command rewrite | persistent texel table + BaseInstance + dirty `writeToBuffer` | source/shaderc portability tests; fail-open kept | not measured (no GPU) | async timestamps wired, unread here | n/a | n/a | n/a | map/unmap/fence = 0 on happy path | persistent indirect + visibility `instanceCount` | GL define / VK core BaseInstance | **REWORK** until RTX 3090 A/B |
| — | Restore client_chunk_* micro-Mixins | failed RTX 3090 | not restored | n/a | n/a | n/a | −0.28% | +0.68% | n/a | lower alloc, no FPS | n/a | n/a | **REVERT already done** |

Phase 14+ (DLSS/FSR) is not started. Native passthrough exists first. Collision stack stays as-is.
