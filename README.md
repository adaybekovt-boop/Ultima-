# Ultima

Performance mod for Minecraft Java Edition 26.2 (Fabric). Ultima ships measured,
behaviour-preserving optimizations to server/client simulation and an opt-in
renderer/mesher stack, plus instrumentation for deciding what to optimize next.

## Vanilla guests on an Ultima host

Install Ultima on **your** dedicated server or on the Fabric client you use to
Open to LAN. Friends can join with ordinary vanilla Minecraft 26.2 — they do
**not** install Ultima.

- Client-only render modules run only on the machine that has the mod.
- Simulation modules run on the server and apply to every connected player automatically.

See [`SERVER_HOSTING.md`](SERVER_HOSTING.md) for the handshake audit and hosting scenarios.

## What is already wired

- Fabric Loom + Fabric API
- Java 25
- GitHub Codespaces devcontainer
- Gradle wrapper 9.5.1
- Automatic Minecraft source generation
- Local export of vanilla source to `.agent/vanilla-src` for AI-agent search
- GitHub Actions build/regression checks
- `AGENTS.md` operating contract for coding agents
- Strict ignore rules so generated Minecraft source never gets committed

## Commands

```bash
bash scripts/bootstrap.sh
./gradlew test
./gradlew build
bash scripts/check.sh
```

Built mod JARs appear in `build/libs/`.

## Module defaults after the multi-branch integration

Ultima's modules are configured in `config/ultima.properties`.

### Default ON

Simulation:
- `cursor_step`
- `entity_section_lookup`
- `block_collision_shape`
- `collision_shell_skip`
- `supporting_block_shape_skip`
- `full_cube_move`

Instrumentation / client contract:
- `server_metrics`
- `terrain_metrics`
- `temporal`
- `settings_ui`

### Default OFF

Simulation experiments:
- `blockentity_sleeping`
- `recipe_match_cache`
- `tag_bitsets`
- `state_property_cache`
- `container_slot_mask`
- `entity_query_early_out`

Client renderer / mesher experiments:
- `retained_terrain`
- `render_snapshot`
- `java_mesher`
- `mesher_fast_path`
- `section_task_queue`
- `rgss_endpoint`
- `fsr_upscaling`

Instrumentation:
- `client_benchmark`

### Auto-disable policy

`Lithium`, `Canary`, and `Radium` disable the overlapping collision/entity and new
simulation optimizations, including hopper sleeping, slot masks, entity-query early-outs,
tag bitsets, and the state-property cache. `recipe_match_cache` deliberately remains
compatible because Lithium has no equivalent first-match recipe lookup cache.

`Sodium`, `Iris`, and `Canvas` disable Ultima's geometry renderer integrations
(`retained_terrain`, `mesher_fast_path`, and the other terrain/mesher modules).
`fsr_upscaling` is separate: Canvas still auto-disables it; Sodium-only is allowed;
Iris (with or without Sodium) stays off for a specific capability reason — no
official post-final hook and no external control of Iris internal resolution —
not the old blanket `incompatible_mod`.

The settings screen exposes all 24 registered modules under Rendering, Simulation, or
Advanced. Every toggle that changes Mixins uses the restart-required apply policy.

## Validation status

The retained-terrain foundation has an earlier real RTX 3090 A/B result:

- average FPS: 301.36 → 394.14, **+30.8%**
- 1% low: 77.79 → 84.26, **+8.3%**
- terrain CPU total: **−42.9%**

Those measurements belong to the retained-foundation provenance chain documented in the
[`ultima-foundation-final-2.6.1` release](https://github.com/adaybekovt-boop/Ultima-/releases/tag/ultima-foundation-final-2.6.1).
They are **not** a hardware-performance claim for the newly integrated modules in this merge.
The new modules remain default off until their separate runtime/hardware validation is done.

`mesher_fast_path` Phase 3.2 includes weighted vanilla unit cubes while preserving vanilla
seed-based variant selection; see `MESHER_FAST_PATH.md`. FSR1 details are in
`FSR_UPSCALING.md`, server telemetry in `SERVER_TELEMETRY.md`.

The production artifact is `build/libs/ultima-0.1.0.jar`; do not install the `-sources.jar`.

## Important

The generated Minecraft sources under `.agent/` are local reference material only and are ignored by Git. Do not commit or redistribute them.
