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

## Important

The generated Minecraft sources under `.agent/` are local reference material only and are ignored by Git. Do not commit or redistribute them.
