# Ultima

Performance mod for Minecraft Java Edition 26.2 (Fabric). Ultima ships measured,
behaviour-preserving optimizations to server/client simulation (collision and
entity-index lookups) and an opt-in retained-terrain renderer path, plus an
agent-ready workspace for continuing the work.

## Vanilla guests on an Ultima host

Install Ultima on **your** dedicated server or on the Fabric client you use to
Open to LAN. Friends can join with ordinary vanilla Minecraft 26.2 — they do
**not** install Ultima.

- Render modules (`retained_terrain`, `java_mesher`, temporal / FSR contract)
  run only on the machine that has the mod.
- Simulation modules (collisions, entity movement lookups) run on the server
  and apply to every connected player automatically.

See [`SERVER_HOSTING.md`](SERVER_HOSTING.md) for the handshake audit, who
installs what, and the two-client hardware join scenario.

## What is already wired

- Fabric Loom + Fabric API
- Java 25
- GitHub Codespaces devcontainer
- Gradle wrapper 9.5.1
- Automatic Minecraft source generation
- Local export of vanilla source to `.agent/vanilla-src` for AI-agent search
- GitHub Actions build check
- `AGENTS.md` operating contract for coding agents
- Strict ignore rules so generated Minecraft source never gets committed

## Start from a phone

Open this repository in **GitHub Codespaces**. The devcontainer runs `scripts/bootstrap.sh` automatically. It generates Minecraft sources, exports them for agent search, and builds the mod.

When setup finishes, the agent can inspect `.agent/vanilla-src` and implement tasks without you manually providing Minecraft source code.

## Prompt to give an agent

> Read AGENTS.md first. Implement this task completely: **[describe the mod feature]**. Search `.agent/vanilla-src` for the relevant Minecraft 26.2 implementation before coding. Make all changes inside Ultima, build the project, fix errors until the build passes, and report what you changed and what still needs an in-game test.

## Commands

```bash
bash scripts/bootstrap.sh
bash scripts/check.sh
./gradlew build
```

Built mod JARs appear in `build/libs/`.

## Release-candidate defaults

Ultima's modules are configured in `config/ultima.properties`.

- Enabled by default (dedicated server, integrated server, and client physics):
  `cursor_step`, `entity_section_lookup`, `block_collision_shape`, `collision_shell_skip`,
  `supporting_block_shape_skip`, `full_cube_move`
- Enabled by default on the client only (instrumentation / no visual change): `terrain_metrics`, `temporal`
- Opt-in renderer experiments (default off, auto-off with Sodium/Iris/Canvas):
  `retained_terrain`, `render_snapshot`, `java_mesher`, `mesher_fast_path`,
  `section_task_queue`, `rgss_endpoint`
- Opt-in instrumentation only: `client_benchmark`
- Removed after a failed RTX 3090 FPS A/B: `client_chunk_matrix_reuse`,
  `client_chunk_layer_array_reuse`, `client_chunk_dirty_dedup`

Lithium, Canary, and Radium automatically disable the overlapping collision/entity-index modules.
Default-on client Mixins (`terrain_metrics`, `temporal`) auto-disable when Sodium, Iris, or Canvas
is loaded. They do not change pixels. `retained_terrain` remains opt-in and also auto-off with those
renderer mods.

A 6-pair dedicated-server entity-farm A/B measured **8.333 → 6.518 ms/tick (−21.78%)**. That is
integrated-server / hitch work, not a GPU FPS claim.

### GPU performance claim status — retained-terrain foundation: KEEP

An earlier reported retained-terrain result (`4d518325d974c2e6b504208fe3d9262c8bbbfcb5`,
+27.85% FPS / +12.87% 1% low) could not be traced to the released tree and was
quarantined (Prompt #2.5, see `CHANGELOG.md`). The foundation was rebuilt with bounded
command compaction and re-tested on real RTX 3090 hardware; that retest is the
currently valid performance claim:

- average FPS: 301.36 → 394.14, **+30.8%** (paired 95% CI +70.44…+115.13 FPS)
- 1% low: 77.79 → 84.26, **+8.3%** (CI +1.03…+11.92 FPS)
- terrain CPU total: **−42.9%**; 0 crashes, 0 `GL_INVALID_OPERATION`, 0 Mixin failures
  across all 12 logs

**FOUNDATION VERDICT: KEEP.** Full methodology, the visual-diff audit, and the
provenance chain from the tested SHA to the released SHA are in the
[`ultima-foundation-final-2.6.1` release](https://github.com/adaybekovt-boop/Ultima-/releases/tag/ultima-foundation-final-2.6.1)
(tag `ultima-foundation-final-2.6.1`, commit `55e7605cd0e8d9fb0a5e3d39a16daa8b5b2f9c79`).
The hardware A/B dataset itself was collected on ancestor commit `6572f2e`; commits after
it are opt-in diagnostics only and do not change the release path — see the release notes'
provenance section for the exact chain.

`retained_terrain` stays **opt-in** (default off). `mesher_fast_path` is a
separate opt-in (default off). Phase 3.2 admits 26.2 weighted unit cubes
(stone/dirt/deepslate) with vanilla seed picking; see `MESHER_FAST_PATH.md`.
Experimental lab PR #3 is not in `main` and stays draft / default off.

The production artifact is `build/libs/ultima-0.1.0.jar`; do not install the `-sources.jar`.

## Important

The generated Minecraft sources under `.agent/` are local reference material only and are ignored by Git. Do not commit or redistribute them.
