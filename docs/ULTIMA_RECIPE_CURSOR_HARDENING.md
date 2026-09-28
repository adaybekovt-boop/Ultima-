# Ultima recipe and cursor hardening

Static audit after `cursor/ultima-cache-hardening-v2-7e67` (`c7692d4c6769b646d3d6014e42bf2d42adf66a31`). Minecraft was not launched. No FPS, stutter, or reload-speedup claim is made.

## 1. Starting git state

| Item | Value |
| --- | --- |
| `origin/main` | `e6763a8cf5abc546350a50c5007b53ae86e01829` |
| Hardening-v2 HEAD audited | `c7692d4c6769b646d3d6014e42bf2d42adf66a31` |
| Merge base with `main` | `e6763a8cf5abc546350a50c5007b53ae86e01829` |
| Working tree at start | clean, tracking `origin/cursor/ultima-cache-hardening-v2-7e67` |
| This branch | `cursor/ultima-recipe-cursor-hardening-7e67` |

`cursor/ultima-hardening-night-7e67` remained `ca081a91887feb665882c6065d1ed0d4f8a77f83`.

## 2. Hardening-v2 verification

Checked, not extended:

| Guarantee | Result |
| --- | --- |
| Artifact cache default off | held (`UltimaModules`) |
| Iris key still fail-closed on an unsupported structure | held; this pass did not edit the encoder or store |
| Supported key can build | held by the existing pinned `SodiumParameters` test |
| Same-JVM Iris L1 stays in front of disk | held; injection is still `transformInternal` |
| Store bounds and corruption miss | held |
| Broker `changesScheduling()` | still `false`; mode `observer`; no `ci.cancel` |
| Warmup adapters | still empty; `changesRenderInitialization()` false; reason `no_safe_warmup_adapters` |
| `server_metrics` | still default off |

## 3. Recipe vanilla contract reconstruction

Target jar: Loom named `minecraft-common` for 26.2 (`minecraft-common-043a8b3edf-26.2.jar` on the test classpath).

| Point | File | Method | Descriptor | Order | Owner |
| --- | --- | --- | --- | --- | --- |
| Ordered scan | `RecipeMap` | `getRecipesFor` | `(RecipeType, RecipeInput, Level)Stream` | `byType` order, `matches`, `findFirst` | the `RecipeMap` installed by `apply` |
| Public lookup | `RecipeManager` | `getRecipeFor` | `(RecipeType, RecipeInput, Level)Optional` | delegates to that stream | one `RecipeManager` per `ReloadableServerResources` |
| Hinted lookup | `RecipeManager` | `getRecipeFor` | the `RecipeHolder` overload | hint first, else the scan | same manager |
| Reload | `RecipeManager` | `apply` | `(RecipeMap, ResourceManager, ProfilerFiller)V` | replaces `recipes` | same manager object during one reload |
| After tags | `RecipeManager` | `finalizeRecipeLoading` | `(FeatureFlagSet)V` | reads `recipes`, does not replace the map | called from `MinecraftServer` after tag publication |
| Tag publish | `ReloadableServerResources` | `updateComponentsAndStaticRegistryTags` | `()V` | `PendingTags` `bind` | before `finalizeRecipeLoading` in `lambda$reloadResources$4` |
| New owner | `ReloadableServerResources` | constructor | — | new `RecipeManager` field | `loadResources` builds a new resources object |

The correct cache value is that first `RecipeHolder`, or the same empty `Optional`. Output item identity is not enough.

## 4. Recipe purity analysis

Allowlist is unchanged. `matches` bytecode for the typed overloads leaves the `Level` local (`aload_2`) unread. The `RecipeInput` bridge forwards `Level` and is not the body that decides a hit. `MapExtendingRecipe.matches(CraftingInput, Level)` does read `Level` and stays off the allowlist.

| Class | Matches inputs | Level read? | Tag? | Components? | Global/static? | Safe to cache? | Why |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Shaped, shapeless, transmute, dye, imbue, repair, fireworks, book, shield, banner, pot | crafting grid | no | via `Ingredient` / `HolderSet` | yes, through `ItemStack.is` | no RNG | yes, exact class + pinned fields + no `MixinMerged` | typed `matches` does not read `Level` |
| Smelting, blasting, smoking, campfire, stonecutter | `SingleItemRecipe.matches` | no | via `Ingredient` | yes | no | yes, same gate | count is ignored by `Ingredient.test` |
| Smithing transform / trim | `SmithingRecipe.matches` | no | via `Ingredient` | yes | no | yes, same gate | only `Ingredient.test` / `testOptionalIngredient` |
| `MapExtendingRecipe` | crafting grid + map data | yes | yes | yes | saved map data | no | `MapItem.getSavedData` |

`Ingredient.test(ItemStack)` on this jar is `ItemStack.is(HolderSet)` and does not call `getCount`.

An exact class is not a proof that another mod's `@Inject` left `matches` alone. The runtime gate is still: exact allowlisted class, pinned 26.2 instance fields, and no `MixinMerged`. A rewrite that adds neither a field nor that annotation is **RUNTIME_BLOCKED**. Unknown classes bypass.

## 5. Recipe tag/reload lifecycle analysis

`MappedRegistry.bindTags` writes a new list into the existing `HolderSet.Named`. `Ingredient` keeps that set. A cached hit becomes stale if membership changes and the cache is not cleared.

Vanilla order, from `MinecraftServer.lambda$reloadResources$4`:

1. `ReloadableServerResources.loadResources` runs `RecipeManager.apply`.
2. `updateComponentsAndStaticRegistryTags` binds postponed tags.
3. `finalizeRecipeLoading` runs.
4. The server then points at the new resources object.

A full reload also replaces `RecipeManager`, so the old cache object dies. The hole is the same manager between step 1 and step 3: a listener can call `getRecipeFor` before tags are bound. `finalizeRecipeLoading` does not replace `RecipeMap`, so the policy built at `apply` is still the right class order. Only stored holders must be dropped.

No extra global tag scan was added. A mod that calls `bindTags` later without `apply` or `finalizeRecipeLoading` is **RUNTIME_BLOCKED**.

## 6. Recipe prefix correctness

Unchanged rule, re-checked:

- A hit is stored only when that holder is in the pure prefix.
- A safe recipe after the first unsafe recipe is not stored.
- A miss is stored only when every recipe of that type is exact-pure.
- The stored value is the holder the independent linear scan returned.

`RecipeMatchCacheTest` compares that scan with the cache for first match, second match, unsafe-before-safe, unsafe-after-safe, duplicate outputs, pure miss, partial miss, subclass, unknown recipe, and reload. `testProductionPrefixStore` uses real `RecipeHolder`s and `RecipeMap.create`, not the model helper.

## 7. Recipe key hot-path analysis

| Family | Key fields | Why | Count | Components | Geometry |
| --- | --- | --- | --- | --- | --- |
| Crafting (shaped, shapeless, repair, specials share one type) | type, width, height, per-slot item, components, count | repair requires `count == 1`; shaped matching is positional | kept | `immutableComponents()` | kept; shapeless order is not canonicalized |
| Cooking / stonecutter | type, item, components, count forced to 1 | `Ingredient.test` ignores count | omitted | yes | one slot |
| Smithing | type, template, base, addition, count forced to 1 | `SmithingRecipe.matches` only calls `Ingredient.test` | omitted in this pass | yes | three slots |
| Unknown input | null key | caller runs vanilla | — | — | — |

Crafting still allocates one `CapturedStack` per occupied slot and one array. That is the input snapshot. It is not an `ItemStack.copy`. There is no stream and no string key on the lookup.

## 8. Recipe memory policy

`FirstMatchTable` stays a `HashMap` capped at 4096. A new key at the cap clears the table, then inserts. A hit does not reorder entries. An access-order `LinkedHashMap` would take a lock-shaped update on every hit to save a cold miss after thousands of distinct grids. That trade is worse than a rare full clear. The table is owned by the `RecipeManager` mixin field, so it dies with that manager. `dropLookups` and `onRecipesReplaced` both bump the generation and clear the last-key slot.

## 9. Recipe fixes applied

| ID | What |
| --- | --- |
| RECIPE-TAG | `RecipeManagerMixin` clears lookups at `finalizeRecipeLoading` HEAD |
| RECIPE-SMITH | smithing keys use `CapturedStack.ofIdentity` |
| RECIPE-PIN | typed `matches` / `Ingredient.test` code attributes are SHA-256 pinned, and `Level` unread is checked on those bytes |

## 10. Cursor vanilla algorithm reconstruction

`Cursor3D` fields: `originX/Y/Z`, `width/height/depth`, `end`, `index`, `x/y/z`.

Constructor: `width = x1 - x0 + 1` (same for y/z), `end = width * height * depth` in `int`.

`advance`: if `index == end` return false; else `x = index % width`, `y = (index / width) % height`, `z = (index / width) / height`, then `index++`.

`nextX/Y/Z` add the origin. `getNextType` counts how many of x/y/z sit on a face.

Ultima, only when `CursorMath.canUseCarry` is true (`width,height,depth > 0` and the `long` product fits in a signed int): replace that divide with a carry of `x`, then `y`, then `z`. The first step leaves the zero origin. `nextX/Y/Z` and `getNextType` are not replaced. Ineligible cursors fall through to vanilla, including divide-by-zero and wrapped `end`.

Callers of `Cursor3D` in the 26.2 common jar: `BlockCollisions` and `SectionPos$1`.

## 11. Lithium overlap analysis

Pinned jar inspected, not added as a dependency: Lithium `0.25.3+mc26.2` Fabric, SHA-256 `fdde92e238e8075f89ad7f701f2a3d5854af88ba9a67657184a4407b104ac563`. The jar contains zero `Cursor3D` references.

Default-on Lithium mixins replace the hot consumers:

| Ultima | Lithium 0.25.3 | Overlap |
| --- | --- | --- |
| `Cursor3D.advance` carry | does not patch `Cursor3D` | not the same instruction |
| entity movement collisions | `entity.collisions.movement` redirects `Entity.collide` to a chunk-aware sweeper | the hot path no longer calls `Cursor3D` |
| `noCollision` | `entity.collisions.intersection.LevelMixin` | sweeper, not `BlockCollisions` |
| supporting block | same mixin, `ChunkAwareBlockCollisionSweeperBlockPos` | not `Cursor3D` |
| free position | `minimal_nonvanilla.collisions.empty_space` default true | sweeper |
| `SectionPos` streams and any `getBlockCollisions` caller Lithium did not overwrite | still vanilla `Cursor3D` | residual, not the collision hot path |

`collision_shell_skip` already depends on `cursor_step` and is already Lithium-incompatible. Its interior-only cursor is unused beside Lithium.

## 12. Cursor correctness analysis

The carry is equivalent only while `canUseCarry` is true. Tests walk vanilla `%`/`/` against the carry for random positive boxes, `1x1x1`, `40x3x40`, and origins `0`, `-64`, `-30000000`, `29999999`. Visit count equals `width*height*depth`. No skipped or duplicate relative cell in those trials. Zero and negative dimensions stay on vanilla. Interior-only order was already checked for boxes up to 12³.

## 13. Cursor final decision

**AUTO-DISABLE WITH LITHIUM** (and Canary / Radium).

The module stays default-on when those mods are absent, because `BlockCollisions` is then the collision iterator and the carry removes the per-cell divide. Beside Lithium the hot iterators do not call `Cursor3D`, and the mixin would still intercept every remaining `advance`. That benefit is not established, so the mixin is not applied.

## 14. Default S+I+L effective profile

With Sodium, Iris, and Lithium loaded and every module left at its default request:

| Module | Default request | Applied on S+I+L? | Why |
| --- | --- | --- | --- |
| Lithium-family simulation modules, including `cursor_step` | on, except the opt-in ones | no | `incompatible_mod` |
| `recipe_match_cache` and the other opt-in simulation modules | off | no | not requested; recipe cache is intentionally not in the Lithium denylist |
| `server_metrics`, `client_benchmark` | off | no | not requested |
| renderer-family modules, including default-on `terrain_metrics` and `temporal` | mixed | no | Sodium/Iris/Canvas |
| `iris_shader_frontend_artifact_cache`, broker, warmup | off | no | not requested |
| `settings_ui` | on | yes on a client without Mod Menu | `TitleScreen.init` only; dedicated server reports `not_client_environment` |

`testDefaultSilProfile` resolves that matrix through `UltimaConfig` and `LoadedModCache`. The enabled set is empty or exactly `settings_ui`.

## 15. Default hot-path overhead analysis

Mixin apply is gated by `UltimaMixinPlugin.shouldApplyMixin` from `UltimaConfig.isEnabled` once per mixin, not per call.

| Hook that can still apply | Frequency | Cost when it runs |
| --- | --- | --- |
| `settings_ui` `TitleScreenMixin.init` | title screen init | one button; returns immediately if Mod Menu is loaded. `LoadedModCache` probes Fabric once per mod id |
| `cursor_step` | not applied beside Lithium | without Lithium: one boolean check per `advance`, then carry arithmetic. No allocation, log, or config read |
| experimental mixins | not applied | no hook |

Fail-open is not on the cursor path. It is on recipe lookups only when that module is on. The healthy `isTripped` path reads one `volatile` and one set. It does not touch the consecutive map unless overflow has latched.

## 16. Fail-open residual analysis

Success still returns immediately when the consecutive map is empty, and otherwise removes only a present streak.

After both caps (256 consecutive keys and 256 tripped ids), a new id sets `overflow` and is then treated as tripped. An in-progress streak that later reaches the threshold while the tripped set is full is removed from the consecutive map and covered by the same latch. A further distinct id does not log on every later fault: the next call takes the vanilla branch before `failOpen`.

Case ids for recipes are `RecipeType` objects, not worlds.

## 17. Disabled-module zero-cost audit

| Module off | What still runs |
| --- | --- |
| Artifact cache | mixin not applied. `IrisFrontendArtifactCache` static init is property reads and an empty metrics object. Disk and Iris reflection run from `aroundTransform`, which the mixin calls. Diagnostics return before that class when the module was not enabled at launch |
| Broker | mixin not applied. No scheduler call |
| Warmup | mixin not applied. Adapter list stays empty |
| Recipe cache | mixin not applied |
| Client benchmark | mixin not applied |

No new thread, filesystem scan, or jar hash was added on those disabled paths.

## 18. Bytecode contract findings

`RecipeBytecodeContract` pins the `Code` attribute SHA-256 of the typed `matches` methods and `Ingredient.test` from the Loom runtime jar, and requires `aload_2 == 0` on those bodies. `MapExtendingRecipe` must still read `Level`. The same test requires `updateComponentsAndStaticRegistryTags` before `finalizeRecipeLoading` in `MinecraftServer.lambda$reloadResources$4`.

`MixinBytecodeChecks` still requires the new `finalizeRecipeLoading` inject to name a real method.

## 19. Test-quality findings

| Test | Proves | Does not prove |
| --- | --- | --- |
| `RecipeMatchCacheTest` model scan | cached holder equals an independent linear scan, including prefix and reload | a live datapack reload or an in-place tag bind |
| `testProductionPrefixStore` | real `RecipeMap` / `RecipeHolder` store rules, and `dropLookups` forgets the holder | that `finalizeRecipeLoading` is invoked by a running server |
| `RecipeBytecodeContract` | pinned method bytes and reload call order | that a third-party mixin rewrote `matches` at runtime |
| `testSmithingKeyIgnoresCount` | equal items with different counts share a smithing key; crafting keys do not | a live smithing menu |
| `testCursorOriginAndLargeBoxes` | carry sequence equals vanilla `%`/`/` including negative origins | a collision workload beside or without Lithium |
| `testDefaultSilProfile` | resolver output for S+I+L defaults | that Mixin actually skipped the class in a running game |
| `testOverflowIdsStopRetrying` | caps stay bounded and a new id takes the vanilla branch | log output text |

## 20. Build/static validation

Java 25 (`/home/ubuntu/opt/jdk-25.0.4.1+1`). `./gradlew --no-daemon check build` finished `BUILD SUCCESSFUL` in 27s. That run includes the registered JavaExec contracts: recipe differential and bytecode pin, cursor sequence and S+I+L profile, fail-open overflow, mixin bytecode, and the other checks. `scripts/summarize-client-bench.py --self-test` and `scripts/summarize-mesher-bench.py --self-test` passed. `git diff --check` reported no whitespace errors. Minecraft was not started. The summarizer self-test prints fixture FPS numbers; those are not measurements of this change.

## 21. Remaining P0/P1/P2

No open P0. No open P1 in the code that this pass could close statically.

| ID | Severity | Note |
| --- | --- | --- |
| RECIPE-INJECT | P2 residual | `@Inject` into `matches` without a new field or `MixinMerged` is invisible. Unknown classes still bypass |
| RECIPE-BIND | P2 residual | `bindTags` without `apply` or `finalizeRecipeLoading` is not a vanilla path |
| CURSOR-RESIDUAL | P2 residual | `SectionPos` iteration and any `getBlockCollisions` caller Lithium does not replace still use `Cursor3D` when the module is on. Beside Lithium the module is off |

## 22. Runtime-blocked questions

1. Does any Fabric reload listener call `getRecipeFor` after `finalizeRecipeLoading` and before the new tags are the ones `Ingredient` sees? Vanilla's own order was checked. Mod listeners were not run.
2. Does a mod rebind item tags on the live registry without rebuilding recipes?
3. Does a mod rewrite an allowlisted `matches` without `MixinMerged`?
4. What does `cursor_step` cost in a collision profile when Lithium is absent?
5. On a real client, is `settings_ui` the only Ultima mixin that loads beside Sodium, Iris, and Lithium?

## 23. What not to work on next

Do not build a recipe inverted index, a FastSuite clone, warmup adapters, or an active broker. Do not retune the artifact cache until a real Iris reload is measured. Do not turn `cursor_step` back on beside Lithium without a profile that shows a remaining `Cursor3D` hot path.

## Findings

| ID | SEVERITY | MODULE | FILE | LINES | FACT | FAILURE SCENARIO | IMPACT | FIX | TEST/CHECK | RUNTIME REQUIRED? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| V2-HOLD | — | artifact, broker, warmup | existing | — | Previous fail-closed and observer/profiler guarantees still hold | — | — | not extended | existing tests | no |
| RECIPE-TAG | P1 | recipe_match_cache | `RecipeManagerMixin.java` | 46-52 | `apply` can store a hit before `HolderSet.Named.bind` | listener calls `getRecipeFor` during reload, then tags bind | stale first holder | `dropLookups` at `finalizeRecipeLoading` HEAD | bytecode order + `dropLookups` test | mod listeners yes |
| RECIPE-SMITH | P2 | recipe_match_cache | `RecipeMatchKeys.java` | smithing `from` | count was in the smithing key; `SmithingRecipe.matches` ignores it | different stack sizes missed | extra misses, not a wrong holder | `ofIdentity` | `testSmithingKeyIgnoresCount` | no |
| RECIPE-LEVEL | — | recipe_match_cache | pinned `matches` | — | Allowlisted typed `matches` do not read `Level` | — | — | none removed from the allowlist | `RecipeBytecodeContract` | mixin rewrite yes |
| RECIPE-MEM | — | recipe_match_cache | `FirstMatchTable.java` | cap 4096 | Clear-on-overflow stays cheaper than LRU-on-hit | — | hit-rate cliff after 4096 distinct keys | kept | `testTableStaysBounded` | no |
| CURSOR-OVERLAP | P1 | cursor_step | `UltimaModules.java` | `cursor_step` | Lithium 0.25.3 replaces the hot `Cursor3D` callers and does not reference `Cursor3D` | module stayed applied and still intercepted `advance` | mixin on a path Lithium already left | auto-disable for lithium/canary/radium | `testDefaultSilProfile` | in-game cost yes |
| CURSOR-SEQ | — | cursor_step | `Cursor3DMixin.java` | `advance` | Carry matches vanilla while `canUseCarry` | negative origin or 1-cell box | skipped cell would desync collisions | sequence test; ineligible stays vanilla | `testCursorOriginAndLargeBoxes` | no |
| SIL-PROFILE | P1 | default profile | `UltimaMixinPlugin` | `shouldApplyMixin` | Experimental modules were already off; `cursor_step` was the leftover hot mixin | S+I+L still ran cursor carry | hidden server overhead | cursor auto-disable | `testDefaultSilProfile` | live mixin list yes |
| FAIL-OVERFLOW | P2 | fail-open | `FailOpenGuard.java` | `isTripped` / `recordFailure` | A new id after both caps logged every fault | many distinct failures | log storm; optimized path retried | overflow latch; success path still skips an empty map | `testOverflowIdsStopRetrying` | no |
| UI-FRAME | — | settings_ui | `TitleScreenMixin.java` | `init` | No per-frame hook | — | — | none | source read | no |
