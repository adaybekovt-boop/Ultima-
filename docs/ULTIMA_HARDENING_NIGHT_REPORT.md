# Ultima hardening night

Static correctness pass. Minecraft was not launched. No FPS claim is made.

## 1. Starting git state

Fetched `origin` before editing.

| Ref | SHA | Notes |
|---|---|---|
| `origin/main` | `e6763a8cf5abc546350a50c5007b53ae86e01829` | Audit SHA matched |
| `origin/agent/killer-modules-v1` | `4f6ca5c` | Audit SHA matched |
| Merge base | `6354801` | |
| Working tree at start | clean | No stash, no reset |

Branch: `cursor/ultima-hardening-night-7e67`, created from `main`, then merged `origin/agent/killer-modules-v1` (ort, no conflicts). That merge is `559178d`.

## 2. Main/killer reconciliation

The merge keeps main's safety fixes and the killer modules. Confirmed present after the merge:

- `ChestBlockEntityMixin.swapContents` invalidation
- `MixinBytecodeChecks`
- `MixinSmokeLoader`

Not every killer-vs-main difference was a regression. Killer-only modules, benchmark scripts, and docs were the feature. Main-only mixin removals and the chest swap fix were kept by merging killer into main rather than overwriting either side.

## 3. Artifact cache defects fixed

Pinned Iris `1.11.4+mc26.2` (`Parameters.class` and `TransformPatcher.class` bytes under `src/test/resources/upstream/iris-1.11.4`).

| Input | Source | Affects output? | Key representation | Why |
|---|---|---|---|---|
| `patch` | `Parameters.patch` | yes | enum name | Selects the transform family |
| `textureMap` | `Parameters.textureMap` | yes | sorted sampler bindings | Renames samplers in the source |
| `type`, `name` | `Parameters` scratch | no | omitted | Iris mutates these during transform |
| `textureOverrides` | not a field | no | not required | The old schema missed every real key |
| `stage` | `TextureStageParameters` / DH constant | yes | stage name or absence bit | Null and empty are different |
| `alpha`, `shadow` | `SodiumParameters` | yes | function, reference, boolean | Changes Sodium patches |
| vanilla geometry flags | `VanillaParameters` / `GeometryInfoParameters` | yes | booleans and attribute inputs | Changes the patched source |
| sources | stage map | yes | stage name + nullable text | The thing being cached |
| environment | Iris version, Minecraft, schema, debug, backend | yes | existing environment record | Mismatch must miss |

`KEY_SCHEMA` is 2. `supportedStructure` rejects an extra `textureOverrides` field. Tests load the real `SodiumParameters` class bytes, not a stub that invented the field.

Injection wraps `transformInternal` only. Iris `transform` / `transformCompute` call `cache.containsKey` before `transformInternal`, so the Iris 400-entry LRU stays in front. Same-JVM repeats do not reach Ultima. A 16-entry payload memo inside `ArtifactCacheStore` avoids reopening a file when Iris does miss and Ultima is asked again. Disk writes use a temp file, close, and atomic rename. `FileChannel.force(true)` is gone. A crash may drop the last entry. A checksum mismatch misses and deletes the entry. Startup index build removed every `*.tmp` (superseded by `docs/ULTIMA_CACHE_HARDENING_V2.md`: only temps older than 60 seconds are deleted).

## 4. Artifact cache remaining unknowns

`RUNTIME_BLOCKED`: no Minecraft process, so there is no proof that a real shader pack hits the persistent cache, that Iris's own key matches ours for every pack, or that skipping `fsync` is acceptable on the target filesystem beyond the atomic-rename contract. Downstream compile/link time is still unmeasured. `type`/`name` are excluded because Iris treats them as scratch; if a future Iris build folds them into the output, the schema check must fail closed (field-set mismatch already does).

## 5. Broker defects fixed

`submitDeferredSectionTasks` is no longer cancellable. Sodium 0.9.2 checks `hasBudgetRemaining` and `UploadResourceBudget.isAvailable` before `dequeueNextSectionPos`. Both are boolean. Forcing either false skips the rest of the pass, which is the same false frame-time win as `ci.cancel()`. `changesScheduling()` is false. Requested `control` and `static` stay unavailable with reason `no_safe_budget_boundary`. Trace and control share that boundary: neither one denies work.

Pressure enters on a frame above 2× target, a valid high GPU sample, known server pressure, or three frames above 1.25×. It exits after eight consecutive healthy frames (latest under 1.05×). A single spike does not stick on p95. Oscillation around the threshold does not flap. Under pressure, the only release is `maximumDefer`. `minimumPermitInterval` is not a release path.

GPU: negative is `NO_DATA`, zero is `ZERO`, positive is `VALID`. `GpuQuerySample` uses `getStatus` (or `isReady` / `isCompleted`) when present. Without a status method, a non-positive `get()` is no data, not a healthy 0 ms. No-data does not count as a healthy GPU and does not by itself enter pressure.

## 6. Broker remaining unknowns

`RUNTIME_BLOCKED`: whether a future Sodium build grows a public remaining-budget setter. Until that exists, adaptive and static control must stay unwired. The controller's `permit()` method is unit-tested and not called from the mixin. GPU `getStatus` was not confirmed against a Minecraft 26.2 jar in this environment (the client jar was not downloaded). The reflection probe fail-closes.

## 7. Warmup final status

Profiler only. The active adapter that read 18 `RenderTypes` getters was removed. `changesRenderInitialization()` is false. `mode=warm` does not start adapters. Iris, GeckoLib, and ModernFix stay unwired. Diagnostics say `profiler_only` / `no_safe_warmup_adapters`. This is not a warmup optimization.

## 8. Benchmark harness corrections

- `artifact_shader_reload` calls `Iris.reload()` once inside the measured sample via reflection. Both A/B sides use the same call. Cold runs still move only Ultima's artifact directory.
- `broker_chunk_flight` uses tick-based `chunk_flight` (the existing 4.8 blocks/tick step) on both sides.
- Warmup stays `profile` on both sides. The harness must not score it as a pass.
- `KillerBenchmarkGates`: artifact hits or reloads at 0 → `INVALID`; broker active control unavailable → `NOT_APPLICABLE`; warmup warmed operations at 0 → `NOT_APPLICABLE`.
- The Python summarizer uses the same empty-work rule. The benchmark was not executed.

## 9. Hot-path overhead corrections

Killer frame/task/shader paths do not call `UltimaConfig.isEnabled(String)`. Module mixins are omitted by `UltimaMixinPlugin` when the module is off at launch.

`FailOpenGuard.recordSuccess` returns immediately when the per-module fault map is empty, so a healthy call does not `ConcurrentHashMap.remove`.

Broker GPU totals split no-data / zero / valid. Deferral is not counted, because the observer never defers.

## 10. Server metrics changes

`server_metrics` default is off. It is instrumentation. Its mixins were already plugin-gated; the default was the problem on a Sodium + Iris + Lithium stack. `terrain_metrics` stays default-on because the renderer-family rule already turns it off when Sodium, Iris, or Canvas is present.

## 11. Recipe cache corrections

Purity is exact `Class` equality for known vanilla leaves (`ShapedRecipe`, `ShapelessRecipe`, cooking leaves, smithing transform/trim, stonecutter, and the listed crafting specials). `AbstractCookingRecipe`, `SmithingRecipe`, and other bases are not pure. A subclass or mixin is not pure.

One unknown recipe no longer sets `neverCache` for the whole `RecipeType`. Holders strictly before the first unsafe recipe may be stored. A miss is stored only when every recipe of that type is exact-pure. `MapExtendingRecipe` still bypasses filled maps and stops the prefix.

Hits store the `RecipeHolder` vanilla returned. Brewing remains an instance field on `PotionBrewing`, so a new brewing registry drops the cache. Recipe reload still rebuilds `RecipeCachePolicy` and clears the table.

Lookup keys were already immutable `CapturedStack` records. Reload planning allocates; the match path does not build a `List` or `HashMap` for the key.

## 12. Bytecode contract corrections

`MixinBytecodeChecks` still covers the main mixin contracts and now also checks pinned fixtures:

- Iris `Parameters` fields are exactly `patch`, `textureMap`, `type`, `name`
- `transform`, `transformCompute`, and `transformInternal` descriptors
- `containsKey` before `transformInternal`
- Ultima wraps `transformInternal` and does not cancel the owner method
- Sodium `submitDeferredSectionTasks` checks budget and upload availability before `dequeueNextSectionPos`
- the broker mixin does not mark that inject cancellable

Fingerprint-style class bytes plus a failed semantic check fail the static test.

## 13. Lifecycle / memory findings

- Artifact disk index is capped by the existing entry and byte limits. The new memo is 16 payloads and 256 KiB of characters. `*.tmp` is removed when the index is built, not on every transform.
- Broker latency rings stay at 2048 samples. Observer counters do not retain worlds or sections.
- Warmup no longer touches render types. Passive status list is cleared on resource reload.
- Recipe cache is replaced on `RecipeManager.apply`. Brewing cache is the brewing instance.
- No new strong reference from these paths to a world, resource manager, or shader pack was added. A full heap proof after disconnect was not run (`RUNTIME_BLOCKED`).

## 14. Tests added or changed

- `IrisTransformKeyEncoderTest` loads real Iris 1.11.4 class bytes. Fake `Parameters` / `SodiumParameters` / `AlphaTest` / `Tri` stubs were deleted.
- `ArtifactCacheStoreTest.sameJvmHotHitDoesNotRereadPayload`. Corrupt and truncated cases reopen the store so they test disk, not the hot memo.
- `AdmissionControllerTest`: spike exit, continuous overload, oscillation, GPU no-data vs zero, maximum-defer release.
- `RecipeMatchCacheTest.testExactClassPrefixDoesNotDisableTheType`.
- `ReplayTimelineTest` scene camera and `KillerBenchmarkGates`.
- `MixinBytecodeChecks.checkPinnedKillerContracts`.
- Default-contract tests expect `server_metrics=false`.

## 15. Build results

`JAVA_HOME=/home/ubuntu/opt/jdk-25.0.4.1+1`

`./gradlew --no-daemon check build` — BUILD SUCCESSFUL.

Included: `irisTransformKeyTest`, `irisArtifactCacheTest`, `admissionBrokerTest`, `brokerMetricsTest`, `recipeMatchCacheTest`, `benchmarkReplayTest`, `killerDiagnosticsTest`, `mixinBytecodeTest`, `renderWarmupTest`, `mixinBytecodeTest`, forensic/merged regressions, and `build`.

`python3 scripts/summarize-client-bench.py --self-test` passed. `git diff --check` passed before the commits that add this report.

## 16. What was not runtime tested

Minecraft client, dedicated server, GPU, Iris shader packs, Sodium chunk flight, shader reload wall time, and any FPS comparison. No `runClient`, `runServer`, or benchmark launch.

## 17. Remaining runtime-blocked questions

1. Does a real Iris 1.11.4 pack produce a non-null key and a persistent hit after restart?
2. Does the Iris L1 actually absorb same-JVM repeats at the `transformInternal` boundary under a pack reload?
3. Is there a Sodium budget API that can shrink work without skipping the rest of `submitDeferredSectionTasks`?
4. What is the Minecraft 26.2 `TimerQuery` readiness method name on a real client?
5. Which warmup `prepare` / `precompilePipeline` call is state-safe before first use?
6. Does the recipe prefix match `RecipeMap.getRecipesFor` order for every vanilla and modded type?

## Issue table

| ISSUE | SEVERITY | STATUS | COMMIT | TEST/CHECK | RUNTIME STILL REQUIRED? |
|---|---|---|---|---|---|
| Iris key required missing `textureOverrides` | P0 | fixed | `d37e330` | `irisTransformKeyTest` | yes, pack reload hit |
| Tests used a fake Parameters field | P0 | fixed | `d37e330` | real class bytes | no |
| Mixin skipped Iris L1 and fsync'd every write | P1 | fixed | `d37e330`, `9993cd3` | `irisArtifactCacheTest` | yes, same-JVM Iris hit |
| Broker cancelled whole deferred submit | P0 | fixed | `d4c2d0f` | mixin bytecode, no `cancellable` | yes, chunk flight |
| Pressure stuck after one hitch; GPU 0 treated as healthy | P1 | fixed | `d4c2d0f` | `admissionBrokerTest` | yes, real timer query |
| Warmup advertised no-op RenderType reads | P1 | fixed | `694b7ee` | coordinator has no adapter | yes, before any prepare adapter |
| Harness could pass with no reload and a stationary camera | P1 | fixed | `2b57644` | `KillerBenchmarkGates` | yes, the harness was not run |
| `server_metrics` default on | P1 | fixed | `1b57e43` | merged module contract | no |
| Fail-open success path always removed a map entry | P2 | fixed | `1b57e43` | existing fail-open tests | no |
| Recipe purity used broad `instanceof` and disabled the type | P1 | fixed | `5363f32` | `recipeMatchCacheTest` | yes, modded recipe order |
| Killer branch dropped main mixin checks | P0 | fixed | `559178d`, `357549f` | `mixinBytecodeTest` | no |
| UI called unloaded killer modules active | P1 | fixed | `71036ff` | `killerDiagnosticsTest` | yes, in-game tooltip |
