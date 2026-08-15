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

- Enabled by default everywhere: `cursor_step`
- Enabled by default on the vanilla client renderer:
  `client_chunk_matrix_reuse`, `client_chunk_layer_array_reuse`, `client_chunk_dirty_dedup`
- Disabled by default: `entity_section_lookup`, `block_collision_shape`, `collision_shell_skip`

Client terrain modules automatically disable when Sodium or Iris is loaded. The other disabled
modules are expert opt-ins because they replace common mod targets, defer a wrapped constructor
operation, or snapshot lazy collision state. See `REVIEW_GPT56.md`, `ARCHITECTURAL_AUDIT.md`, and
`CLIENT_PERFORMANCE_REPORT.md` before changing defaults in a modpack. The first real GPU A/B did
not show a reliable FPS gain; do not advertise one.

The production artifact is `build/libs/ultima-0.1.0.jar`; do not install the `-sources.jar`.

## Important

The generated Minecraft sources under `.agent/` are local reference material only and are ignored by Git. Do not commit or redistribute them.
