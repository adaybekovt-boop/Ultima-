# Ultima

Agent-ready Fabric mod workspace for Minecraft Java Edition 26.2.

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
- Enabled by default on the client only (instrumentation, no visual change): `terrain_metrics`
- Opt-in renderer experiments (default off, auto-off with Sodium/Iris/Canvas):
  `retained_terrain`, `render_snapshot`, `java_mesher`, `section_task_queue`, `rgss_endpoint`
- Opt-in instrumentation only: `client_benchmark`
- Removed after a failed RTX 3090 FPS A/B: `client_chunk_matrix_reuse`,
  `client_chunk_layer_array_reuse`, `client_chunk_dirty_dedup`

Lithium, Canary, and Radium automatically disable the overlapping collision/entity-index modules.
There is no production vanilla-renderer Mixin left to conflict with Sodium or Iris.

A 6-pair dedicated-server entity-farm A/B measured **8.333 → 6.518 ms/tick (−21.78%)**. That is
integrated-server / hitch work, not a GPU FPS claim. The only RTX 3090 FPS A/B on this project
was of the three deleted client modules and was inconclusive (−0.28% average FPS). Do not
advertise a client FPS gain until a GPU A/B of the current defaults exists. See
`REAL_PERFORMANCE_REPORT.md`.

The production artifact is `build/libs/ultima-0.1.0.jar`; do not install the `-sources.jar`.

## Important

The generated Minecraft sources under `.agent/` are local reference material only and are ignored by Git. Do not commit or redistribute them.
