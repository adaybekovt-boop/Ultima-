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

### GPU performance claim status — provenance recovery in progress

Prompt #2.1–2.4 previously reported a retained-terrain KEEP result, including
**+27.85% average FPS** and **+12.87% 1% low** in chunk-flight. Those numbers are
currently **quarantined and are not release-valid claims**: the reported tested commit
`4d518325d974c2e6b504208fe3d9262c8bbbfcb5` is not present in the remote repository,
and the `main` tree that was merged did not contain the bounded command compaction
described by that test report.

Prompt #2.5 is recovering the documented compaction behavior, fixing CI so it validates
the actual triggering HEAD, and requires a repeat real-hardware A/B on the exact released
SHA before the GPU numbers may be advertised again. See `CHANGELOG.md` and the provenance
recovery report.

`retained_terrain` stays **opt-in** (default off). `mesher_fast_path` is a
separate opt-in (default off) on `cursor/ultima-mesher-fast-path-0e88`; it is
not enabled in `main`. Experimental lab PR #3 is not in `main` and stays draft /
default off.

The production artifact is `build/libs/ultima-0.1.0.jar`; do not install the `-sources.jar`.

## Important

The generated Minecraft sources under `.agent/` are local reference material only and are ignored by Git. Do not commit or redistribute them.
