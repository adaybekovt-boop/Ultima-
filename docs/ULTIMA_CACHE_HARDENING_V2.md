# Ultima cache hardening v2

Static audit after `cursor/ultima-hardening-night-7e67` (`ca081a91887feb665882c6065d1ed0d4f8a77f83`). Minecraft was not launched. No FPS, stutter, or shader-reload speedup is claimed.

## 1. Starting git state

| Item | Value |
|---|---|
| `main` HEAD | `e6763a8cf5abc546350a50c5007b53ae86e01829` (`origin/main` matched) |
| Hardening branch | `cursor/ultima-hardening-night-7e67` at `ca081a91887feb665882c6065d1ed0d4f8a77f83` |
| Expected hardening HEAD | same SHA. Fetch matched. |
| Merge base of hardening and `main` | `e6763a8cf5abc546350a50c5007b53ae86e01829` |
| Working tree at audit start | clean |
| This branch | `cursor/ultima-cache-hardening-v2-7e67`, created from the hardening HEAD |
| Ahead/behind at start | hardening was the published tip; this branch had no unique commits yet |

`git reset --hard`, `git clean -fd`, and `checkout --force` were not used.

## 2. Diff / regression findings

`main...ca081a9` is 102 files, about +7640 / -125. The deletions in production Java are benchmark bookkeeping (`ClientFrameBenchmark` fixed-size sample arrays replaced by the replay timeline) and settings/command copy. Mixin configs, `fabric.mod.json`, and `build.gradle` only gain the killer-module entries and JavaExec contracts. Defaults that matter for Sodium+Iris+Lithium were not loosened: `server_metrics` stays default off, killer modules stay default off, Lithium-family and renderer-family incompatibilities are still on the modules that overlap those mods.

No deleted fail-open door, mixin `require`, or recipe purity check was found without a replacement on the hardening tip. This pass did not treat the size of that diff as a defect.

## 3. Iris contract reconstruction

Pinned artifacts, confirmed from `docs/KILLER_MODULES_IMPLEMENTATION.md` and the jars already used by the bytecode tests:

- Iris `1.11.4+mc26.2`, jar SHA-256 `f1f7ab57c974d193ba33aa285864a0ded949216f402116fceaf4dc7739b4dd7c`
- `TransformPatcher.class` SHA-256 `101fb251c68034e4c8346de1c550616059d896dbfbb3fae56e5982a89877c25f`
- Minecraft `26.2`

`javap` on that Iris jar:

- `transform` and `transformCompute` allocate an `EnumMap`, check the static glsl-transformer LRU (`cache.containsKey`) and only then call `transformInternal`.
- `transformInternal(String, Map, Parameters)` assigns `Parameters.name` and calls `EnumASTTransformer.transform`. It does not return a different map type than the map it was given.
- `Parameters` declared instance fields are exactly `patch`, `textureMap`, `type`, `name`. There is no `textureOverrides`.
- `type` and `name` are scratch. They are written at the start of `transformInternal` and are excluded from the key. The program name is hashed separately.
- Concrete parameter classes: `ComputeParameters`, `DHParameters`, `SodiumParameters`, `TextureStageParameters`, `VanillaParameters`, plus superclass `GeometryInfoParameters` (`hasGeometry`, `hasTesselation`).
- Output-affecting reads outside the parameter object, counted on `TransformPatcher`: `areDebugOptionsEnabled` and `isZZeroToOne`. Both are in `Environment` and therefore in the key. Backend name must contain `opengl` or the module disables itself.

| Dependency | Source | Mutable? | Affects output? | In key? | Why |
|---|---|---|---|---|---|
| Stage name + source | caller map | for the call | yes | yes | sorted by stage name; null source and empty source are different |
| `patch`, `textureMap`, subclass fields | `Parameters` | yes, but stable for one call | yes | yes | exact field set or the plan is unsupported |
| `Parameters.type`, `Parameters.name` | scratch | written inside `transformInternal` | no, name is a separate input | no | including them would change the key mid-transform |
| Iris debug options | `IrisConfig` | config | yes | yes | reflection, methods cached |
| `zZeroToOne` | `GpuDevice` | device | yes | yes | |
| Iris version, jar fingerprint, Minecraft version, schema 2 | adapter / environment | process | yes | yes | |
| Iris L1 cache | static LRU | yes | no, it is a memo | no | Ultima is not invoked on an L1 hit |

Same serialized key implies the same canonical input only for the supported field schema. An extra field, unknown class, unknown value shape, cycle, or duplicate stage name returns a null key (`RejectReason`) and the original Iris path runs. That is fail-closed, not a partial key.

## 4. Artifact cache findings

| ID | SEVERITY | MODULE | FILE | LINES | FACT | FAILURE SCENARIO | IMPACT | FIX | TEST/CHECK | RUNTIME REQUIRED? |
|---|---|---|---|---|---|---|---|---|---|---|
| ART-TMP | P2 | artifact cache | `ArtifactCacheStore.java` | 249-256 before this pass; now 509-520 | Startup deleted every `*.tmp` | A second JVM is mid-write when this JVM opens the store | Lost in-flight write. Miss, not a wrong shader | Delete temps only when mtime is at least 60s old | `freshTemporarySurvivesStartup`, `staleTemporaryIsRemoved` | no |
| ART-ENUM | P2 | artifact cache | `IrisFrontendArtifactCache.java` | 315-326 | Hit path built a `LinkedHashMap`. Iris builds an `EnumMap` in `transform` / `transformCompute` | Downstream code that needs `EnumMap` behavior or enum iteration order | Fail-open only if the caller rejects the map type. Wrong shader text was not returned | `materializeStages` returns `EnumMap` | `materializedHitIsEnumMap` against pinned `PatchShaderType` | The cast site inside Iris was not executed. RUNTIME_BLOCKED for a live pack |
| ART-REASON | P2 | artifact cache | `IrisTransformKeyEncoder.java` | 66-128 | `encode` returned null with no reason | Unsupported class, bad structure, and IO failures were indistinguishable | Diagnostics could not tell a contract miss from a bug | `RejectReason` plus `lastMissReason` / `lastUnkeyableReason`. No per-miss log | `rejectReasonsAreExplicit` | no |
| ART-ORDER | P2 | artifact cache | `IrisTransformKeyEncoder.java` | 105-116 | Stage list order was part of the digest and only the caller sorted | Two equal stage maps with different iteration order missed, or a future caller forgot to sort | Extra misses, not a wrong hit, until a caller stopped sorting | Encoder sorts by stage name and rejects a duplicate name | `canonicalEncodingIsDelimiterSafe` | no |
| ART-DIGEST | P2 | artifact cache | `Sha256.java`, encoder, store | new helper | `MessageDigest.getInstance` on every key and every payload checksum | Shader-load allocation, not a frame loop | No correctness bug | Thread-local SHA-256 | Existing key and store tests still pass | no |
| ART-ID | P2 residual | artifact cache | `ArtifactKey.java`, store header | key is 32 bytes | Logical identity is the SHA-256 of the canonical form. The header compares those bytes. The payload has its own SHA-256 | A canonical-form collision would alias the filename | Wrong shader text only if SHA-256 collides. Storing the canonical bytes would duplicate the shader source | Not stored. Crash may drop a write. Checksum mismatch deletes the entry | corrupt and truncated store tests | no |
| ART-MEMO | info | artifact cache | `ArtifactCacheStore` hot map, 16 entries / 256k chars | kept | Iris L1 is in front of `transformInternal`. The memo is not a second L1. It avoids reopening a file when Iris later misses a key Ultima already loaded | Removing it would re-read disk on every Ultima call after an Iris eviction | Kept on purpose | `sameJvmHotHitDoesNotRereadPayload` | Whether Iris L1 actually absorbs pack reloads is RUNTIME_BLOCKED |
| ART-DOC | P2 | docs | `KILLER_MODULES_IMPLEMENTATION.md` | disk-format paragraph | The doc still said `FileChannel.force(true)` after fsync was removed | Operators would expect crash-durable writes | The store is rebuildable and does not fsync | Doc now matches the code | doc review | no |

## 5. Artifact cache fixes

- Startup temp cleanup is age-based (`STALE_TEMPORARY_MILLIS`).
- Persistent hits materialize an `EnumMap` of `PatchShaderType`.
- Unkeyable requests record `NULL_PARAMETERS`, `UNSUPPORTED_CLASS`, `UNSUPPORTED_STRUCTURE`, or `ENCODE_FAILURE`. Other bypasses set `lastMissReason` and do not log.
- Stage sources are canonicalized inside the encoder. Length-prefixed UTF-8 remains the string encoding. `|`, NUL, empty, and null do not alias.
- SHA-256 digests are reused per thread.
- Header key compare uses `ArtifactKey.sameBytes` so the disk hit does not clone the digest just to compare it.
- Verify mode still skips the cached return, runs Iris, and compares stage maps with `transformedStagesMatch`. A corrupted fragment fails that compare. Verify stays off unless `-Dultima.irisShaderFrontendArtifactCache.verify` is set.

Store failure, corrupt length, bad magic, unknown schema, and checksum mismatch still miss and fall through to Iris. No store error is thrown into shader loading.

## 6. Artifact hot-path analysis

Order on a supported call:

1. Iris `transform` / `transformCompute` LRU. A same-JVM repeat of the same Iris cache key never enters Ultima.
2. On an Iris miss, Ultima builds the canonical SHA-256 key (thread-local digest, length-prefixed fields).
3. `ArtifactCacheStore.read` takes the key stripe, then the 16-entry hot map. A hot hit returns the payload and does not increment `payloadFileReads`.
4. Otherwise one indexed file read, checksum, then the hot map.
5. Miss: Iris `transformInternal`, then an atomic rename with no fsync.

The hot hit still pays the key hash. That hash is the lookup identity. It does not re-hash by reading the cached file, and it does not `stat` the directory. Eviction sorts only when the entry or byte cap is already exceeded. Caps stay 256 MiB and 2048 entries. Payload cap stays 64 MiB. This is not a gameplay benchmark.

## 7. Artifact remaining runtime unknowns

RUNTIME_BLOCKED:

- Does Iris L1 absorb a real pack reload so Ultima's disk path stays cold in-game?
- Does any Iris caller downcast the `transformInternal` result in a way `EnumMap` still fails?
- Does a live `SodiumParameters` / `VanillaParameters` graph contain a value shape the encoder rejects (miss, not a wrong shader)?
- Multi-process writes longer than 60 seconds can still lose an in-flight temp (miss).

## 8. Recipe vanilla semantics reconstruction

`RecipeMap.getRecipesFor` streams `byType` order and `findFirst` of `Recipe.matches(input, level)`. `RecipeManager.getRecipeFor` uses that first holder. The correct cache value is that holder, or empty, not "any recipe with the same output".

`javap` of `matches(CraftingInput, Level)` or `matches(SingleRecipeInput, Level)` on the pinned 26.2 common jar: the `Level` local is loaded only by `MapExtendingRecipe` (`MapItem.getSavedData`). Every allowlisted class leaves that local unread. The `RecipeInput` bridge loads `Level` only to forward it.

`Ingredient.test` is `ItemStack.is(HolderSet)`. `RepairItemRecipe.canCombine` requires `count == 1` plus damage components. `TransmuteRecipe` counts occupied slots, not stack size. `SingleItemRecipe.matches` does not read count. `ShapelessRecipe` uses `StackedItemContents.canCraft`. Crafting keys keep geometry and count. Furnace keys force count to 1. Components are the immutable component map, not a live `ItemStack`.

The cache object lives on the `RecipeManager` and is rebuilt in `onRecipesReplaced` when vanilla replaces the map. It does not outlive that manager.

## 9. Recipe purity analysis

Allowlist stays the exact classes whose `matches` bytecode does not read `Level`. `MapExtendingRecipe` stays off the list. Filled maps still bypass when a map-extending recipe is present anywhere in the type.

Exact class is not a proof that a mixin left `matches` alone. This pass adds a pinned instance-field walk (leaf and vanilla parents) and a `MixinMerged` scan. An extra field or a merged method fails closed for that class only. One unsafe recipe does not disable the `RecipeType`.

Not proven, and not removed from the allowlist because every shaped/shapeless recipe uses it:

- `Ingredient` tag `HolderSet` can change if a mod rewrites tags without `RecipeManager.apply`. Vanilla reloads recipes with tags. That residual is a miss we do not currently detect.
- An `@Inject` into `matches` that adds neither a field nor `MixinMerged` is invisible. Unknown recipe classes still bypass.

Prefix rule, unchanged and now also covered by `RecipeCachePolicy.mayStore` against real `RecipeHolder`s:

- safe A, safe B, unsafe C: A and B may be stored; C may not; a miss may not.
- unsafe C then safe D: D may not be stored, because C might have matched.
- miss stored only when `fullyPure`.

## 10. Recipe cache fixes

- `RecipeCachePolicy.isExactPureClass` requires `RecipeVanillaShape.matchesPinnedShape`.
- The model scanner in `RecipeMatchCacheTest` no longer stores a safe hit that sits after an unsafe recipe, and no longer stores a miss unless the crafting list is fully pure. The vanilla reference path is still the separate `vanillaCraft` scan.
- `mayStore` is checked with `RecipeMap.create` of real `RepairItemRecipe` holders plus a subclass. The subclass is not stored. A later repair holder is not stored. A fully pure map may store a miss.

No inverted index was added. `FirstMatchTable` still caps at 4096 and clears on overflow (bounded, correctness-safe, hit-rate cliff). That was left in place.

## 11. Recipe hot-path analysis

Module off: `UltimaMixinPlugin.shouldApplyMixin` skips `recipe_match_cache` mixins at launch. No per-query config string check.

Module on, hit: fail-open door (one map `get` only if that module has already faulted), bypass check for filled maps, immutable key, `HashMap` get. Keys are not mutable stacks. Telemetry counters are plain increments, not string lookups.

Module on, unknown type or unkeyable input: null key, vanilla scan, nothing stored.

## 12. Default S+I+L overhead analysis

With Sodium, Iris, and Lithium loaded and experimental modules off, `UltimaMixinPlugin` does not apply mixins whose module is disabled or incompatible.

Not applied on that stack:

- Lithium family (entity section lookup, collision shape/shell/support, full-cube move, block-entity sleeping, tag bitsets, state property cache, container slot mask, entity query early-out).
- Renderer family (terrain metrics, retained terrain, snapshot, java mesher, mesher fast path, section task queue, RGSS, temporal). `temporal` is default on and still incompatible with Sodium/Iris/Canvas, so it stays off on this stack.
- Default-off modules: `server_metrics`, `recipe_match_cache`, `client_benchmark`, FSR, and all three killer modules.

Still applied:

- `cursor_step` (`Cursor3DMixin`). No Lithium incompatibility. This is the shipped cursor carry, not an experimental observer. It is the remaining server hot mixin. It was not rewritten in this pass.
- `settings_ui` (`TitleScreenMixin.init`). Title-screen button only.

Killer observer mixins are not in the default profile. There is no hidden broker or warmup instrumentation when those modules are off.

## 13. Fail-open analysis

`recordSuccess` used to `ConcurrentHashMap.remove` whenever the consecutive map was non-empty, including for case ids that had never faulted. It now `get`s and removes only a present streak. An empty map still returns immediately, so the no-fault path does not touch the map.

`consecutive` is capped at 256 keys. A new id past the cap is tripped immediately when the tripped set (also 256) has room, so it does not WARN on every later call. The counter saturates at `Integer.MAX_VALUE` instead of wrapping through `Integer::sum`. After both caps are full, a further distinct id can still log on each fault. That is the residual.

The guard is not a telemetry sink. Success does not allocate and does not log.

## 14. Broker observer-only verification

No scheduling code was added.

- `CrossPipelineBroker.changesScheduling` returns false (line 267).
- `mode` returns `observer` (line 259).
- Control and static requests report `no_safe_budget_boundary` (line 271).
- The Sodium submit mixin is not cancellable. There is no `ci.cancel`.
- `permit` is not used to deny work.
- GPU samples stay NO_DATA / ZERO / VALID as on the hardening tip.

## 15. Warmup profiler-only verification

No adapter was added.

- `ADAPTERS` is empty (line 19).
- `changesRenderInitialization` returns false (line 93).
- `failClosedReason` is `no_safe_warmup_adapters` (line 90).
- Mode stays `profiler_only`.
- The mixin set is not applied unless the module is on. Default is off, so the profiler allocates nothing in the default profile.

## 16. Benchmark harness findings

| ID | SEVERITY | MODULE | FILE | LINES | FACT | FAILURE SCENARIO | IMPACT | FIX | TEST/CHECK | RUNTIME REQUIRED? |
|---|---|---|---|---|---|---|---|---|---|---|
| BENCH-RELOAD | P1 | benchmark | `summarize-client-bench.py`, `ShaderManagerMixin` (artifact), `ClientFrameBenchmark` | gate 273-278; sample window in `beginFrame` | `artifactCache.reloads` counted every Iris `ShaderManager.apply`, including startup | Startup reload plus any hit could PASS with no reload inside the sample | A harness success without the measured reload | `sampleReloads` increments only while `ClientFrameBenchmark.isSampleWindow()` is true. The gate requires that integer and `hits > 0`. A missing field is INVALID | Python self-test: lifetime `reloads: 9` with `sampleReloads: 0` is INVALID | The in-game scene still has to be run. RUNTIME_BLOCKED for a real pack reload |

`artifact_shader_reload` still calls `BenchmarkShaderReload.requestOnce` only after the sample window is opened, and the window flag is set before that call. Broker control stays NOT_APPLICABLE. Warmup with zero warmed operations stays NOT_APPLICABLE. No recipe workload benchmark was added.

## 17. Test quality findings

| Test | What it proves | What it does not prove |
|---|---|---|
| `IrisTransformKeyEncoderTest` | Pinned `Parameters` bytes have no `textureOverrides`. A real `SodiumParameters` from those bytes builds a non-null key. Map order, null vs empty, scratch fields, tampered fields, delimiter strings | That Iris invokes Ultima, or that the transformed GLSL matches a GPU compile |
| `materializedHitIsEnumMap` | The hit-path map type is `EnumMap` for pinned `PatchShaderType`, and an unknown stage name fails closed | A downstream Iris cast |
| `transformedStagesMatch` | Verify comparison includes source text. A corrupted fragment and an empty-vs-absent stage fail | That verify mode is wired in a running client. The production branch calls this method |
| `ArtifactCacheStoreTest` | Checksum, truncation, schema, hot memo vs disk, bounds, young vs stale temps, parallel same-key writes | Multi-JVM timing above 60s, Windows atomic-move fallback on this Linux VM |
| `RecipeMatchCacheTest` model | `vanillaCraft` vs the cache on first match, geometry, repair count, world-dependent map recipe, prefix store rules | Vanilla `Recipe.matches` bytecode execution. The model is a separate scanner |
| `testProductionPrefixStore` | `RecipeCachePolicy.mayStore` and `RecipeFirstMatchCache` on real `RecipeHolder`s from `RecipeMap.create` | A full crafting grid through `RecipeManager` |
| `testPinnedVanillaShape` | Every allowlisted class still matches the pinned field sets, and a generated `MixinMerged` method is rejected | Injects that leave no field and no merged method |
| Python gate self-test | Lifetime reloads cannot pass | An actual benchmark JSON from Minecraft |

## 18. Bytecode contract findings

Existing `MixinBytecodeChecks` still require `transformInternal` to be the wrap target and Iris L1 `containsKey` to precede it. This pass did not weaken that.

New static checks against the Minecraft 26.2 jar and the Iris 1.11.4 fixtures:

- Allowlisted recipe classes load and match the pinned instance-field sets. A future yarn/field change fails `testPinnedVanillaShape` and fails closed in `isExactPureClass`.
- `Level` is unread in those `matches` methods (javap this pass, not a committed golden dump of every instruction).
- Iris `PatchShaderType` used by the EnumMap test is the pinned class, not a handwritten stub.

## 19. Build / static validation results

Java 25 (`/home/ubuntu/opt/jdk-25.0.4.1+1`). `./gradlew --no-daemon check build` finished `BUILD SUCCESSFUL` (24 tasks). That run includes the JavaExec contracts: Iris key and store, recipe differential and prefix, wave-2 fail-open, mixin bytecode, and the other registered checks. `scripts/summarize-client-bench.py --self-test` passed, including the new artifact gate cases. `git diff --check` reported no whitespace errors.

Minecraft, `runClient`, `runServer`, shader packs, and RenderDoc were not run.

## 20. Remaining P0 / P1 / P2

No P0 was confirmed.

P1 fixed: benchmark artifact gate no longer accepts lifetime reloads.

P1 residual: a mixin `@Inject` into an allowlisted `matches` that adds neither an instance field nor `MixinMerged` is not detected. Unknown classes still bypass. Proving the absence of such an inject needs the transformed runtime bytecode. RUNTIME_BLOCKED.

P2 fixed: stale temp deletion, EnumMap materialize, reject reasons, canonical stage order, thread-local SHA-256, fail-open success path, consecutive cap and saturation, recipe prefix tests aligned with `mayStore`, doc/fsync mismatch.

P2 left open on purpose:

- SHA-256 remains the sole logical identity of a canonical key. A collision would alias a file. Not practical, and a second copy of the canonical bytes would duplicate shader source.
- In-place tag `HolderSet` edits without `RecipeManager.apply` can stale a recipe hit.
- `FirstMatchTable` drops the whole table at 4096 entries.
- `cursor_step` still runs beside Lithium. That is the shipped cursor, not a disabled experiment.
- After 256 consecutive keys and 256 tripped ids, another distinct fail-open id can still log on each fault.

## 21. Runtime-blocked questions

1. Does a real Iris 1.11.4 pack reload hit Ultima only after Iris L1 misses, and does the returned `EnumMap` compile?
2. Does verify mode (`-Dultima.irisShaderFrontendArtifactCache.verify`) catch a hand-corrupted on-disk stage in that reload?
3. Does `recipe_match_cache` return the same first `RecipeHolder` as vanilla across a datapack reload and a tag reload?
4. What does `cursor_step` cost next to Lithium in a collision-heavy server profile?
5. Does `artifact_shader_reload` record `sampleReloads >= 1` in a real benchmark JSON?

None of these were answered by launching the game.
