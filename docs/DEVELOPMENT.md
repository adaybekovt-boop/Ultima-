# Developing Ultima

For contributors and coding agents. Players want the root [`README.md`](../README.md).

## Toolchain

| Piece | Version |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric Loom | 1.17.x (`gradle.properties`) |
| Fabric API | 0.156.0+26.2 |
| Java | 25 |
| Gradle | 9.5.1 (wrapper is tracked) |

Do not upgrade these unless the task requires it. `AGENTS.md` is the operating contract for
coding agents and `OPTIMIZATION_GUARDRAILS.md` lists what an optimization may and may not change.

## Everyday commands

```bash
bash scripts/bootstrap.sh     # genSources + build; exports vanilla sources to .agent/vanilla-src
./gradlew test                # the canonical regression aggregate (CanonicalRegressionTest)
bash scripts/check.sh         # build + regression + benchmark-script self-tests + A/B dry run
bash scripts/mixin-smoke.sh   # headless runServer that force-loads every common Mixin target
```

`./gradlew test` runs the merged regression checkpoint once. The specialised `JavaExec` tasks
(`forensicRegressionTest`, `recipeMatchCacheTest`, ...) stay available locally and are not a
second CI path. These suites are plain Java checks against real Minecraft classes; they do not
apply Mixins. The Mixin smoke is what proves the common Mixins apply on a live server.

The generated Minecraft sources under `.agent/` are local reference material only and are
ignored by Git. Do not commit or redistribute them.

## Source layout

- `src/main`: common code and simulation Mixins. Must never reference `net.minecraft.client`
  or `com.mojang.blaze3d` (`VanillaClientHostingChecks` enforces it) so a dedicated server
  never loads client classes.
- `src/client`: render, mesher, FSR and settings-screen code, plus the client Mixins.
- `src/test`: the regression suites, the synthetic mesher kernel and its oracles, and the
  test-only fixtures. Nothing in here ships in the mod jar.
- Every Mixin lives in a package named after its module key
  (`dev.ultima.mixin.<module_key>`); `UltimaMixinPlugin` uses that to skip the Mixins of a
  disabled module.

## Localization

Settings-screen text comes from `src/client/resources/assets/ultima/lang/*.json`. English is
owned by the Java classes because the server-side `/ultima config` output needs it too;
`LocalizationChecks` fails the build when `en_us.json` drifts from them or another language
has different keys or placeholders. To add a language, add `<code>.json` and list it in
`LocalizationChecks.LANGUAGES`.

## Continuous integration

`.github/workflows/ultima-ci-validation.yml` runs `scripts/check.sh` on Linux and Windows and
the Mixin smoke on Linux for every pull request and every push to `main`.
`.github/workflows/release.yml` builds and publishes a GitHub Release when a `v*` tag is pushed;
the jar version comes from the tag.

## Related documents

- [`FSR_UPSCALING.md`](FSR_UPSCALING.md): FSR1 module design and policy
- [`MESHER_FAST_PATH.md`](MESHER_FAST_PATH.md): hybrid mesher and what its tests do and do not prove
- [`SERVER_HOSTING.md`](SERVER_HOSTING.md): vanilla-guest handshake audit
- [`SERVER_TELEMETRY.md`](SERVER_TELEMETRY.md): `server_metrics` and `/ultima profile`
- [`history.md`](history.md): provenance and the pre-1.0 changelog
