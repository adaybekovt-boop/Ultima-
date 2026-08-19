# Server telemetry

This is **diagnostics, not an optimization**. Ultima records where server tick time
goes so later work on the chunk pipeline, per-player delivery, networking, entities,
or block entities can be chosen from measurements instead of guesses.

Vanilla guests can still join a host that has Ultima (see `SERVER_HOSTING.md` when
present). These counters observe the server; they do not change gameplay, packets,
saves, or tick order.

## What is measured

Always-on cheap counters (module `server_metrics`, default on):

| Id | Meaning |
| --- | --- |
| `tick.total` | `MinecraftServer.tickServer` wall time |
| `tick.entities` | Entity tick list |
| `tick.block_entities` | Block-entity tickers (server levels only) |
| `tick.ai` | Mob `serverAiStep` + brain tick (nested inside entities) |
| `tick.random_ticks` | `ServerLevel.tickChunk` (random block/fluid ticks) |
| `tick.chunk_manager` | `ChunkMap.tick(BooleanSupplier)` unload/POI/save bookkeeping |
| `chunk.load` | `SerializableChunkData.parse` |
| `chunk.io` | `RegionFileStorage` read/write |
| `chunk.generate` | Worldgen tasks + noise fill |
| `chunk.serialize` | `SerializableChunkData.copyOf` / `write` |
| `chunk.send_prepare` | `ClientboundLevelChunkWithLightPacket` construction |
| `network.encode` | Packet encoder |
| `network.compress` | Packet compression |
| `network.encrypt` | Packet encryption |
| `network.queued_bytes` | Sum of Netty outbound pending bytes on serverbound connections |
| `tracking.entity_candidates` | Players considered by entity tracker updates |
| `tracking.added` / `tracking.removed` | Tracker pairings added/removed |
| `be.sleeping` / `be.wakeups` | Ready for future block-entity sleeping (currently stay 0 unless written) |
| `ai.clean_skips` / `ai.invalidations` | Ready for future AI dirty-tracking |
| `tracking.player_chunks_entered` | Chunks newly pending send to a player |
| `chunk.generated_count` | Feature-generation passes completed |
| `chunk.packets_prepared_bytes` | Extracted chunk payload bytes |

These are **not** a partition of `tick.total`. Entity time includes AI. Worldgen, IO,
and Netty encode/compress/encrypt often run on worker threads and may land in a later
tick than the work that queued them. On an integrated server, encode/compress/encrypt
also include the host client's outbound packets.

Histogram / ring buffer (last 1200 samples, about 60s at 20 TPS) for
`tick.total`, `chunk.generate`, `chunk.send_prepare`, `network.encode`, and
`network.compress`. p50/p95/p99/max are computed on demand, not every tick.

**Expected always-on cost (not a performance claim):** two `nanoTime` calls and one
atomic add per instrumented interval, plus a boolean check. No allocations on that
path. Mixins are skipped entirely when `server_metrics=false`. Order of magnitude:
tens of microseconds per tick.

## `/ultima profile`

Operators and the server console only (`Commands.LEVEL_GAMEMASTERS`). Regular players
cannot run it.

```
/ultima profile
/ultima profile <seconds>
/ultima profile stop
```

- Default duration: 60 seconds. Maximum: 300 seconds (the command will not stay on
  forever by accident).
- Turns on **detailed mode**: per-tick subsystem breakdown plus semantic events.
- When the timer elapses (or you `/ultima profile stop`), detailed mode turns off,
  a summary is printed to chat/console, and a JSON file is written next to the
  server directory (`ultima-server-profile-yyyyMMdd-HHmmss.json`). Override with
  `-Dultima.serverMetrics.output=/path/to/file.json`.

Lag-tick alert (always-on, cheap): if `tick.total` exceeds 50ms, Ultima logs that
tick's last-tick counters even outside a profile. Disable or change with
`-Dultima.serverMetrics.lagTickMs=50` (set `0` to disable).

## Semantic events

Detailed mode records *why* work happened, not only how long it took. The framework
is `ServerTelemetry` / `SemanticEventKind`. Built-in examples:

- hopper woke because adjacent inventory changed
- entity tracker recalculated visibility because player crossed section boundary
- chunk serialization cache missed because block state version changed
- player A moved into N new chunks
- N chunks generated
- packet bytes prepared

Future optimizations should call `ServerTelemetry` instead of inventing a second log.

## JSON format

`schemaVersion` 1, `kind` `ultima_server_profile`, `purpose`
`diagnostic_not_optimization`. Compare two files (baseline vs a later optimization)
by `counters.*.windowAvg` / `p95` / `p99` and by `topSubsystems`.

Top-level fields: `requestedSeconds`, `ticksSampled`, `lagTicksOverThreshold`,
`lagTickThresholdNs`, `startedAtEpochMs`, `endedAtEpochMs`, `environment`,
`modules`, `counters`, `topSubsystems`, `ticks`.

Each `ticks[]` entry has `tick`, `totalNs`, `phases`, `otherNs` (server-thread
remainder after entities, block entities, random ticks, chunk manager, and
send-prepare), and `events`.

## Live capture checklist (not run in this environment)

Use a dedicated Fabric 26.2 server with Ultima and 1–3 friends on vanilla or Fabric
clients. For each scenario, run `/ultima profile 60` (or 120 for exploration) and
keep the JSON:

1. One player AFK on a built base (idle MSPT baseline).
2. Two players together in the same area (shared chunks, entity tracking).
3. Two players walking in opposite directions (divergent chunk send + gen).
4. One player exploring new terrain while another loads a hopper/redstone machine.
5. Login/teleport burst: friend joins or `/tp` across the world (chunk send spike).
6. One player in the Nether and one in the Overworld at the same time.

Record view distance, simulation distance, player count, and whether the second
client is vanilla. Compare JSON files; do not treat a single session as proof.
