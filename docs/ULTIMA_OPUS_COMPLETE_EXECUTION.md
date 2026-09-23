# Ultima Opus execution

Implementation pass on top of the framework-overhead re-audit. This is not a second audit. No FPS or TPS claim is made.

## 1. Starting SHA

`2d9fbe976de0e42ad10d7581f86314bf81c17f0e`

Verified with `git merge-base --is-ancestor`. The branch was created from that commit, not from `main`.

## 2. Final SHA

The documentation commit that adds this file is the branch tip. The last code commit before it is `6bca547290bae07c4d1809abae9cf925e0fceffc`.

## 3. Branch

`cursor/ultima-complete-opus-work-24b2`

## 4. Draft PR

https://github.com/adaybekovt-boop/Ultima-/pull/42

Base: `cursor/ultima-framework-overhead-hardening-8db4` (the branch that contains `2d9fbe9`). Not `main`. Not merged.

## 5. Environment versions

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.156.0+26.2 |
| Fabric Loom | 1.17.20 (repository pin; not upgraded) |
| MixinExtras | 0.5.4 (observed on the dedicated-server smoke) |
| Java | Temurin 25.0.4.1+1 |
| Gradle | 9.5.1, committed wrapper |
| OS | Linux 6.12.94+ x86_64 |
| GPU | none (`/dev/dri` absent, no `glxinfo`) |

## 6. Opus task matrix

| Phase | Result |
| --- | --- |
| 1 Build / wrapper / toolchain | COMPLETE |
| 2 Test harness | COMPLETE |
| 3 CI | COMPLETE in-repo; GitHub Actions was not executed here |
| 4 Correctness C4 C7 C11 C13 C14 | PARTIAL: code and JVM tests landed; GPU lifecycle was not executed |
| 5 D1–D9 | PARTIAL: only changes with a local equivalence argument were kept |
| 6 server_metrics | PARTIAL: correctness items landed; unmeasured contention left alone |
| 7 Source sets | COMPLETE as a no-move: dedicated server does not load client classes |
| 8 Benchmark infrastructure | PARTIAL: refusal and world-state gates landed; no live A/B was run |
| 9 Runtime validation | PARTIAL / BLOCKED where a GPU or mod profile was required |
| 10 Section F | NOT_PROVEN or BLOCKED; nothing new was kept |
| 11 Docs and verification | COMPLETE for the commands that were actually run |

## 7. Previously completed on 2d9fbe9

Left untouched:

- O(1) runtime module-enabled gate
- hot callers removed from full config graph resolution
- pending-restart semantics
- `FailOpenGuard` `anyTripped` fast path
- `server_metrics`, `terrain_metrics`, `temporal`, `client_benchmark` default-off
- `CursorMath` overflow fix and randomized oracle tests
- unknown recipe plan fails closed
- `cursor_step` auto-disable beside Lithium, Canary, and Radium
- recipe cache: safe prefix, full-pure miss, unknown bypass, `finalizeRecipeLoading` invalidation, smithing count, bounded fault saturation

## 8. Newly implemented

- Committed Gradle 9.5.1 wrapper; Java toolchain 25; `options.release = 25`; UTF-8
- `fabric-api` dependency floor `>=0.156.0+26.2` (the version the project already compiles against)
- Configuration cache kept on after `processResources` stopped reading the project at execution time
- Bench JVM and program files are lazy `CommandLineArgumentProvider` inputs
- `./gradlew test` is the one canonical regression route; `check` follows it
- Redstone-conductor cache is predicate-proven or bypassed
- Mesher circuit breaker resets on world change, disconnect, and resource reload
- Retained-terrain Ultima GPU tables reset on world change and disconnect
- FSR field and screenshot hooks are `@WrapOperation`
- FSR EASU/RCAS constant buffers upload only when size or sharpness changes, and are closed on fail-open
- Renderer conflicts use exact ids plus two canonical entry classes
- Opt-in lookup/allocation cuts in D1, D3, D5, D6, D7, D9 as listed below
- `server_metrics` phase recovery, zero-sample suppression, lag-log rate limit, Netty event-loop guard
- CI concurrency, caching, one verification path, Linux mixin smoke, artifact upload
- Client summarizer refuses critical environment and undeclared config mismatches
- Server A/B script fails when seed, entity score, player score, or kill count disagree

## 9. Rejected claims

Reconfirmed on this tree and not reopened:

- C5 `int[]` write race
- C6 `java_mesher` `clearCache`
- C8 translucent group key 173
- C9 `identityHashCode` stale draw
- C10 pipeline invalidate leak
- C12 `removeTaskByIndex(-1)`
- steady-state "capturing lambda allocates every healthy call"
- duplicate `TICK_AI` spans: `Mob.serverAiStep` does not call `Brain.tick`; both spans stay

## 10. Deferred or blocked

- D2 denser metrics sampling: the existing isolated probe on this JVM was slower than the `HashSet` baseline (section 39). No further change.
- D3 `WeakHashMap` slot tracker replacement
- D4 extra hopper wake gates beyond the existing zero-watcher check
- D7 worker scratch, benchmark-only allocation counters, face unroll
- D8 staging buffers, GPU timers, translucent rebuild, shader-pack specialization
- `server_metrics` shared `AtomicLong` packet timing and per-chunk `nanoTime`
- Live GPU checks for FSR, retained terrain, and mesher upload
- Sodium + Iris + Lithium process launch
- Live recipe `/reload` inside a running world
- Section F implementations

## 11. Build changes

- `gradlew`, `gradlew.bat`, and `gradle/wrapper/` are tracked. `scripts/ensure-wrapper.sh` only recreates them when they are missing.
- `org.gradle.jvmargs=-Xmx4G -Dfile.encoding=UTF-8`, `parallel=true`, `caching=true`, `configuration-cache=true`
- `java.toolchain.languageVersion` 25, `withSourcesJar()`, compile `release` 25
- Maven publication coordinates are explicit: `dev.ultima:ultima:<mod_version>`. No repository was added.
- `Ultima-24-modules-2026-09-21.zip` removed
- Ignores: `*.jfr`, `*.hprof`, `hs_err_pid*`, `crash-reports/`, `*.zip`
- `.gitattributes` forces `*.sh` and `gradlew` to LF
- Controlled benches pass `-Pultima.controlledBench=1`, which adds `-Xms6G` and `-XX:+AlwaysPreTouch` only for that run. The global run config is still `-Xmx6G` only.

## 12. CI changes

`.github/workflows/ultima-ci-validation.yml`

- Push trigger is `main` only, plus every pull request
- Repeat matrix removed
- `gradle/actions/setup-gradle` caching left enabled
- `--no-daemon` removed from CI, `scripts/check.sh`, and `scripts/mixin-smoke.sh`
- `concurrency` cancel-in-progress
- 45 minute job timeout; mixin smoke is Linux-only with a 20 minute step timeout
- Ancestry SHA checks and deleted `cursor/*` branch fetches removed
- One verification command: `bash scripts/check.sh`
- Artifacts: `build/libs/*.jar`, `build/reports/**`, `build/test-results/**`, `run/logs/latest.log`
- Windows still runs the same check

## 13. Test harness changes

- `CanonicalRegressionTest` is the JUnit entry. It calls `MergedRegressionTest` once.
- `Wave2FailOpenTest.run()` is no longer nested inside recipe, tag, state, or slot mains.
- `CommandCompactionRecoveryTest` is inside `MergedRegressionTest`.
- Specialized `JavaExec` tasks remain and are not `check` dependencies.
- `./gradlew test` is not a disabled no-op.
- `MixinBytecodeChecks` fails if the compiled common or client mixin directory is missing, and if a configured mixin class is absent.
- Hosting, telemetry permission, hopper unload, and Wave2 wiring checks read bytecode instead of source text.
- `RecipeMatchCacheTest.testProductionOrderedScanAgrees` stores and loads through `RecipeFirstMatchCache` and compares that to `RecipeMap.byType` plus `matches`.

## 14. Correctness fixes

### C4 `isRedstoneConductor`

`RedstoneConductorPurity.allows` returns false unless the block is vanilla, not dynamic, and the accessor can read the predicate. The predicate is cached only when probes at two positions against a `BlockGetter` that throws on every call all return the same boolean. Package name is not used. Unit tests reject a world-reading predicate and a position-dependent predicate. Without the accessor mixin, `allows` stays false.

### C7 mesher circuit breaker

`mesher_fast_path.MinecraftMixin` calls `MesherCircuitBreaker.reset()` from `setLevel`, `disconnect(Screen, boolean, boolean)`, and `reloadResourcePacks`. It does not reset every frame.

### C11 retained terrain

`retained_terrain.MinecraftMixin` calls `RetainedTerrainRenderer.reset()` from `setLevel` and `disconnect`. That closes Ultima GPU tables only. Vanilla vertex and index buffers are not closed. `LevelRenderer.close` still resets on full teardown. No GPU run confirmed join/leave.

### C13 FSR

The two `mainRenderTarget` `GETFIELD` sites and `tryTakeScreenshotIfNeeded` are `@WrapOperation` with `require = 1` on the field reads. FSR off returns the chained original target. Fail-open still returns the vanilla target and now closes both constant buffers. Bytecode check forbids a remaining `@Redirect` on this mixin.

### C14 renderer family

`RendererFamilyEvidence` conflicts on exact ids `sodium`, `iris`, `canvas`, or on `RenderSectionManager` / `TransformPatcher`. `embeddium` and `rubidium` do not conflict. Synthetic predicate tests cover that.

## 15. D1 `state_property_cache`

`PathType.values()` is cached once on `BlockStatePropertyPack`. `WalkNodeEvaluator.getPathTypeFromState` has one vanilla `getBlockState`; the mixin shares that state with the return store through MixinExtras `@Share`. Redstone caching is the C4 gate, not a package-name guess. Module stays default-off.

Isolated pack lookup on this JVM: packed 2.86 ns/op versus a constant assign at 2.03 ns/op. That is not a gameplay win and is not used as one.

## 16. D2 `tag_bitsets`

Not changed. The existing isolated membership loop on this JVM reported bitset 19.42 ns/op and `TagKey` `HashSet` 9.71 ns/op (ratio 0.50, set time / bitset time). Adding a denser snapshot or metric sampling on top of a slower probe was rejected. Differential tests against vanilla `contains` still pass. Module stays default-off.

## 17. D3 `container_slot_mask`

`SlotMaskQueries` counts occupied slots first and returns the original `int[]` when the filter changes nothing. The synchronized `WeakHashMap` tracker is still there; replacing it with per-container mixin state was not proven leak-free for custom containers in this pass, so it was left. Module stays default-off.

## 18. D4 `blockentity_sleeping`

Not changed. `needsWakes()` already gates the hot path. Moving controller state onto the hopper instance was not required to fix a confirmed bug. Module stays default-off.

## 19. D5 `entity_query_early_out`

`EntityQueryKind` is a `ClassValue`. Unknown classes still take the vanilla query. `EntitySectionCounters.unknownBlocking()` is one immutable sentinel and is not mutated. Module stays default-off.

## 20. D6 `recipe_match_cache`

`lookupUnchecked` and the brewing lookups use one `FirstMatchTable.get`. A null means miss. `Optional.empty()` remains a stored miss for a fully exact-pure type. Safe prefix, unknown bypass, smithing count, and `dropLookups` were not weakened. The new test checks production `storeUnchecked` / `lookupUnchecked` against an ordered `RecipeMap` scan. Module stays default-off.

## 21. D7 `mesher_fast_path` / `java_mesher`

`HybridSectionMesher` uses `BlockRenderFlags.model(flags)` instead of a second `getRenderShape()`. `getFluidState` is still called when the fluid flag is set, because the fluid object is required. Worker scratch, shared `AtomicLong` removal, `threadAllocatedBytes`, and face unroll were not measured here and were not changed. Module stays default-off. Existing mesher equivalence tests passed. Those tests are CPU mesh counters, not FPS.

## 22. D8 `retained_terrain`

Lifecycle reset is C11. Reusable staging buffers, fences, GPU timers, and shader-pack specialization were not changed: there is no GPU in this environment to prove them. Module stays default-off.

## 23. D9 `fsr_upscaling`

EASU and RCAS each have a constant buffer. Upload runs only when input size, output size, or RCAS sharpness bits change. One `CommandEncoder` performs the upload and the draw. `GlCommandEncoder.writeToBuffer` and `VulkanCommandEncoder.writeToBuffer` both consume the `ByteBuffer` before they return, so the stack memory is not used after the write. `failOpen` and `shutdown` close both buffers. HUD/UI still composite after the upscale; that contract was not changed. Module stays default-off. No GPU frame was captured.

## 24. `server_metrics`

- `tickServer` is a `@WrapMethod` with `try/finally`, so `endTick` runs when the tick throws.
- `PhaseClock.closeAll` recovers nested depth. A phase that was opened is sampled even if its duration is zero. A phase that was never opened and whose accumulator is zero is not pushed into the ring.
- Lag lines use one cached logger and a one-second gap.
- `ConnectionMixin` returns before `channel.unsafe().outboundBuffer()` unless `eventLoop().inEventLoop()`.
- The two AI spans were not merged (section 9).
- Shared `AtomicLong` packet counters and per-chunk `nanoTime` were left. They are real contention questions, and this pass did not measure them.
- Default remains off. The dedicated-server log shows `requested=false enabled=false reason=disabled_by_default`.

## 25. Source-set result

No packages were moved. `src/main` has no `import net.minecraft.client`. Client FSR, temporal, retained, and meshing runtime classes are already under `src/client`. Shared FSR settings stay in `src/main` because `UltimaConfig` is loaded on the dedicated server. The server smoke reports every client module as `not_client_environment`. A large move would not change that classloading boundary.

## 26. Benchmark-infrastructure result

`ClientFrameBenchmark` now records `cpu`, `shaderPack` (from `ultima.clientBenchmark.shaderPack` when the bench script sets it), and `abProtocol.variedKeys`.

`summarize-client-bench.py` returns exit 3 when a pair disagrees on git SHA, Minecraft, Fabric Loader, Fabric API, Java, OS, CPU, GPU name, driver, framebuffer size, render distance, simulation distance, vsync, shader pack, mod list, or an enabled module outside `variedKeys`. Legacy fixtures with no environment block still compare. The self-test covers a GPU mismatch, an undeclared module, and a declared A/B key.

`bench-client-ab.sh` computes `variedKeys` from the off/on modes and alternates which side runs first.

`bench-server.sh` prints seed and scoreboard entity/player markers after the datapack load marker, then warmups, then the measured sprint. `compare-server-bench-state.py` fails the A/B if those markers or the trailing kill count differ. The summary prints pair count, mean, median, p95, p99, and standard deviation of the paired tick deltas. No server A/B was executed, so those statistics have no new numbers.

Controlled bench JVM flags are opt-in via `-Pultima.controlledBench=1`.

## 27. Recipe runtime result

JVM only. `RecipeMatchCacheTest` passed, including:

- production prefix store and `dropLookups`
- unplanned type stays a miss
- smithing key ignores count
- ordered `RecipeMap.byType` scan versus `RecipeFirstMatchCache.lookupUnchecked` for a pure repair miss and an unsafe type

A live world `/reload` that watches apply, tag publication, and `finalizeRecipeLoading` was not run. PASS is not claimed for that ordering.

## 28. Sodium + Iris + Lithium live profile

BLOCKED. This environment did not launch those mods. The dedicated-server smoke used Fabric API only.

Default dedicated-server log:

- `Ultima initialized with 6 of 13 optimization modules enabled`
- enabled: `entity_section_lookup`, `block_collision_shape`, `collision_shell_skip`, `supporting_block_shape_skip`, `full_cube_move`, `cursor_step`
- `server_metrics` and the other opt-in simulation modules: `disabled_by_default`
- client modules: `not_client_environment`

`cursor_step` stayed enabled because Lithium was not loaded. That matches the existing auto-disable rule.

## 29. Iris artifact-cache decision

`KEEP_FROZEN_SAFE`

The integration was not modified. `ArtifactCacheStoreTest` ran inside the merged regression and passed, including corrupted payload, truncated entry, and stale temporary removal. Unknown Iris builds and a live transform exception were not executed on a GPU. The module stays default-off and frozen.

## 30. `cursor_step` decision

`RUNTIME_INCONCLUSIVE`

The algorithm was not modified. No invocation count, CPU sample, or collision trace was collected. Default stays on when Lithium, Canary, and Radium are absent, and the existing auto-disable stays. No FPS number is attached.

## 31. F1 random-tick section skip

`NOT_PROVEN`

Skipping a section changes the random-tick RNG unless the skipped draws are still consumed. Proving a section can never perform random-tick work, including palette and modded block uncertainty, was not established. Nothing was implemented.

## 32. F2 full-cube collision shortcut

`NOT_PROVEN` as a new path.

`full_cube_move` and `block_collision_shape` are already default-on and already cover the full-cube movement and collision shape paths. A third shortcut was not added.

## 33. F3 chunk packet serialization cache

`NOT_PROVEN`

`CHUNK_SERIALIZE` is a metric id, not a demonstrated hotspot on this machine. Invalidation for blocks, biomes, light, block entities, heightmaps, and packet metadata was not proven. Nothing was implemented.

## 34. F4 block-entity renderer frustum culling

`BLOCKED`

Exact bounds for custom, global, and infinite renderers were not established, and there is no GPU run. Unknown renderers would have to stay on vanilla. Nothing was implemented.

## 35. F5 particle render frustum culling

`NOT_PROVEN`

Particle ticking must stay vanilla and only the render submission may be skipped. Custom particles were not classified safely, and there is no GPU run. Nothing was implemented.

## 36. F6 section mesh upload batching

`BLOCKED`

Vertex order, synchronization, and buffer lifetime need a GPU. Nothing was implemented.

## 37. Exact tests

PASS:

```text
JAVA_HOME=$HOME/.local/jdk-25 ./gradlew test
JAVA_HOME=$HOME/.local/jdk-25 bash scripts/check.sh
```

`./gradlew test` finished `BUILD SUCCESSFUL`. `CanonicalRegressionTest > aggregate() PASSED`.

`bash scripts/check.sh` finished `BUILD SUCCESSFUL` and then passed `summarize-client-bench.py --self-test`, `summarize-mesher-bench.py --self-test`, and `DRY_RUN=1 bash scripts/bench-mesher-ab.sh`.

`git diff --check` reported no whitespace errors before the commits.

Not run, and therefore not marked PASS:

- `./gradlew runClient`
- a live recipe reload
- a Sodium + Iris + Lithium profile
- a client or server FPS/TPS A/B

## 38. Runtime tests

PASS:

```text
JAVA_HOME=$HOME/.local/jdk-25 bash scripts/mixin-smoke.sh
```

`run/logs/latest.log` contains:

```text
ULTIMA_MIXIN_SMOKE_OK: force-loaded 45 common Mixin target classes
```

The dedicated server then stopped on a missing `server.properties` / EULA path after mod initialization. That is the existing smoke shape: the marker is logged from mod init after mixin application. No `Mixin apply for mod ultima failed` line was present.

Default-off mixins, including the new redstone accessor and the `server_metrics` `WrapMethod`, are not applied while those modules stay off. The smoke force-loaded their target classes. It does not prove an enabled-module apply of those two mixins.

Client mixin apply (FSR, mesher reset, retained reset) was not run. No GPU.

## 39. Benchmarks

Real numbers from this environment only. None of these are FPS or TPS.

Java Temurin 25.0.4.1+1, Linux 6.12.94+, no GPU.

From `./gradlew test` on 2026-09-23:

- Tag membership microbench, not an FPS claim: bitset 19.42 ns/op, vanilla `HashSet` 9.71 ns/op, ratio 0.50x (set/bitset). D2 was left unchanged because of this.
- Property-pack lookup microbench, not an FPS claim: packed 2.86 ns/op, constant assign 2.03 ns/op.

`summarize-client-bench.py --self-test` still prints the checked-in RTX 3090 fixture (average FPS mean-of-means about -0.28%, verdict `INCONCLUSIVE`). Those figures are fixtures, not a measurement of this branch.

No new client or server A/B JSON was produced.

## 40. Reverted experiments

No experimental optimization was committed and then reverted. The section F candidates and the unproven D2/D4/D7/D8 items were not committed.

## 41. Blockers

- No GPU, so `runClient`, FSR output, retained-buffer join/leave, and F4/F6 stay blocked
- Sodium, Iris, and Lithium jars were not part of the smoke classpath
- No live world for recipe reload ordering or `cursor_step` invocation counts
- GitHub Actions for the new workflow was not watched from this environment

## 42. Changed files

`git diff --stat 2d9fbe9..6bca547` before this report:

60 files, +1923 / -431, including deletion of `Ultima-24-modules-2026-09-21.zip`.

This report, `README.md`, and `CHANGELOG.md` are the documentation commit on top of that range.

## 43. Commits

- `3d80fda` build: commit the Gradle 9.5.1 wrapper and pin Java 25
- `11a9c17` test: run one regression aggregate from Gradle check
- `3e0f3f0` fix: fail closed on unproven redstone, renderer, and FSR edges
- `26af26d` perf: cut proven opt-in lookup and allocation work
- `9af7e50` fix: keep server_metrics samples on the phases that actually ran
- `6bca547` ci: make verification and benchmark comparisons reproducible
- documentation commit on top of `6bca547`

## 44. Gameplay semantics changed?

No intended vanilla-visible change.

Default module set is unchanged. Opt-in caches return the vanilla value when their proof holds, and call vanilla when it does not. FSR off still returns the original render target. `server_metrics` remains off, so its sampling changes are inactive in the default profile.

## 45. FPS/TPS claims?

No.

## 46. Final default module profile

On:

- `entity_section_lookup`
- `block_collision_shape`
- `collision_shell_skip`
- `supporting_block_shape_skip`
- `full_cube_move`
- `cursor_step` (off automatically when Lithium, Canary, or Radium is loaded)
- `settings_ui` (client only; the dedicated server reports it as `not_client_environment`)

Off: `server_metrics`, `terrain_metrics`, `temporal`, `client_benchmark`, `recipe_match_cache`, `tag_bitsets`, `state_property_cache`, `container_slot_mask`, `entity_query_early_out`, `blockentity_sleeping`, `fsr_upscaling`, `retained_terrain`, `mesher_fast_path`, `java_mesher`, and the other opt-in modules including the frozen Iris artifact cache.

Dedicated-server smoke: 6 of 13 optimization modules enabled.

## 47. Top remaining risks

- Default-off mixin apply for the new redstone accessor and `server_metrics` `WrapMethod` was not observed with those modules enabled
- FSR and retained resets have no GPU confirmation
- `container_slot_mask` still uses a synchronized `WeakHashMap`
- Tag bitset probe is not faster than the vanilla set in the isolated loop on this JVM
- `cursor_step` default-on without Lithium is still unmeasured

## 48. Recommended next step

On a machine with a GPU and the pinned Sodium, Iris, and Lithium jars:

1. Enable `server_metrics` and `state_property_cache` for one dedicated-server smoke so those mixins actually apply, then turn them back off.
2. Run the client once with FSR off and once with FSR on at a fixed resolution and confirm the HUD stays at native resolution.
3. Join and leave a world twice with `retained_terrain` explicitly enabled and confirm Ultima buffers are recreated without a GL error.
4. Only after that, collect a disabled-versus-default client A/B of at least six alternating pairs. Do not treat the fixture FPS numbers in the summarizer self-test as that run.
