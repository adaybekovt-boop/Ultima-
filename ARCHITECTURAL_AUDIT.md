# Ultima architectural audit — integrated release-candidate pass

Date: 2026-08-14  
Target: Minecraft Java 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, Java 25

This document records the useful system-level findings from the independent Opus pass after manual
integration into `cursor/forensic-review-9efc`. It does not replace the adversarial correctness
record in `REVIEW_GPT56.md`; the two reports are complementary.

## Release-candidate portfolio

Ultima currently contains four optimization modules, all in entity lookup or block-collision paths.
That is deliberate scope, not evidence that these are Minecraft's only important bottlenecks.

| Module | Release default | Architectural verdict |
|---|---|---|
| `cursor_step` | enabled | Keep; bounded arithmetic substitution with committed differential coverage |
| `entity_section_lookup` | disabled | Keep opt-in; real algorithmic win but a whole-method replacement on a common optimization target |
| `collision_shell_skip` | disabled | Keep opt-in; large synthetic win but lazy snapshot semantics and eager pre-check trade-offs |
| `block_collision_shape` | disabled | Keep opt-in; allocation win, but perfect Mixin wrapper composition is impossible while deferring the call |

Among the original common modules, only `cursor_step` is enabled by default. Results from an
all-enabled benchmark are experimental and must not be presented as default-install performance.

The later client-priority pass adds three vanilla-renderer modules for same-frame chunk matrix/enum
array reuse and duplicate dirty-write elimination. They are client-only and automatically disabled
for Sodium/Iris. A real RTX 3090 disabled-vs-default A/B in a stationary Fancy scene was
inconclusive (−0.28% average FPS); details are in `CLIENT_PERFORMANCE_REPORT.md`. Do not present
those modules as a measured FPS win.

## Workload bias

The profile that selected these targets used a superflat world, no connected players, force-loaded
chunks, 1100 summoned entities, frozen time, and a collision-heavy sprint. That is a useful stress
test for mob farms and item-heavy areas, but it suppresses or minimizes:

- chunk generation, loading, saving, lighting, and serialization;
- player tracking, packet encoding, and view-distance churn;
- pathfinding toward meaningful goals and POI/villager work;
- redstone, block entities, scheduled ticks, and complex terrain palettes;
- render extraction, GPU submission, and shader work.

The statement that entity ticking dominated that recording describes the harness, not Minecraft in
general. Future optimization selection needs profiles from real player, chunk-loading, and
integrated-client workloads.

## Integrated findings

### Cursor eligibility must gate shell scanning

`Cursor3D` computes `end = width * height * depth` in `int`. Enormous or degenerate volumes can
overflow or preserve vanilla divide-by-zero behavior, so Ultima's carry/interior traversal correctly
refuses them.

The shell constructor hook previously scanned all covered chunk sections before discovering that the
cursor could not use interior-only mode. Since that scan scales with query dimensions, a query that
vanilla exhausts immediately after integer overflow could trigger effectively unbounded pre-work.

The release candidate now checks, in this order:

1. the cursor implements Ultima's optional interface;
2. its cached, constant-time eligibility flag permits interior traversal;
3. only then, section palettes are scanned.

Ineligible queries fail open to the untouched vanilla traversal before any chunk or palette query.

### Deferred shape creation and wrapper composition

The original expression-value injector never prevented `Shapes.create(AABB)` from running. The first
forensic pass corrected that with `@WrapOperation`, but deliberately discarded `original`; doing so
could silently suppress another mod's inner wrapper.

The release candidate retains the complete `Operation<VoxelShape>` supplied by MixinExtras and calls
it on the first non-cube collision branch. This preserves wrappers represented by that operation and
clears the retained factory immediately afterward.

Perfect composition remains impossible:

- a wrapper inside Ultima's operation is invoked lazily and is preserved;
- a wrapper outside Ultima still receives the constructor-time `null` sentinel and may expect an
  immediate non-null shape;
- another mod reading vanilla's private final `entityShape` before `computeNext` can observe null.

Compatibility and safety therefore take precedence over the allocation claim:
`block_collision_shape` is disabled by default and remains an explicit expert opt-in.

### Shell snapshot semantics

At the moment of the palette scan, shell rejection is conservative:

- dynamic-shape states report a large shape;
- global palettes return true and force vanilla;
- debug worlds, non-`Level` collision getters, and non-`LevelChunk` chunks force vanilla;
- absent chunks and out-of-build-height sections match vanilla's behavior at that instant.

`BlockCollisions` is lazy, however. A fence, wall, dynamic block, moving piston, chunk, or modded
collision state can change after construction and before a shell position would have been visited.
Vanilla reads the new state; interior-only traversal does not. Vanilla callers normally drain the
iterator immediately on one thread, but public/modded retention and mutation are not equivalent.
The module remains disabled by default.

The pre-check can also regress short-circuit callers: `noCollision` may stop at its first collider,
while Ultima scans every covered palette first. Superflat benchmarks do not represent fence/wall
heavy villages or mob farms where palette eligibility is lower.

### Entity section lookup

The direct lookup preserves vanilla's observable key order for supported coordinates. The release
candidate retains:

- signed packed-coordinate bounds;
- saturating `2^96` candidate-volume arithmetic;
- a hard 1024-candidate ceiling;
- a conservative fallback when candidates exceed the total loaded section count;
- long loop counters that cannot wrap at `Integer.MAX_VALUE`.

The loaded-section comparison is not an exact estimate of vanilla's queried x strips, but it prevents
obvious sparse-world over-probing without scanning the same tree the optimization is intended to
avoid. The module still cancels the whole vanilla query method and overlaps Lithium-like mods, so it
remains opt-in.

### Module registry and startup safety

Module dependencies are declared in `UltimaModules.Module`, not hard-coded in config logic.
Dependency resolution detects inactive/cyclic chains. A Mixin package segment not present in the
registry now fails closed to vanilla behavior, preventing a rename or typo from bypassing the kill
switch in the dangerous direction.

Config boolean parsing remains strict. Invalid text retains the declared default with a warning.
Known missing keys use registry defaults. Existing explicit values are not silently overwritten.

## Independent coverage integrated

The committed regression executable now includes:

- cursor carry eligibility for zero, inverted, and overflowing dimensions;
- shell interior-mode refusal whenever carry is unsafe;
- exhaustive small-volume interior subsequence and vanilla-visible index checks;
- randomized cursor and entity-section order differential checks;
- packed-coordinate and saturated-volume boundaries;
- strict config parsing, dependency declaration, unknown-module failure, and release-default checks.

The tests model arithmetic and ordering. They do not transform Minecraft classes under a second
Mixin stack, emulate arbitrary world mutation, or prove third-party wrapper ordering.

## Rejected or deferred architectural changes

- A maintained per-section large-shape index remains rejected: vanilla and upgrade/network mutation
  paths can bypass a naïve counter, and an under-count would corrupt collision behavior.
- Shared per-tick block/shape caches remain rejected without a complete invalidation proof.
- Reusing one block-collision traversal across movement, step-up, and supporting-block queries is not
  a containment substitution. A union-volume design may be possible, but it would require a broad
  core-physics Mixin and new evidence.
- Renderer/extract changes remain rejected without a GPU, current Sodium/Iris stacks, and shader
  validation.
- Chunk lifecycle, scheduling, networking, and real-player workloads remain unprofiled.

## Final verification snapshot

- `bash scripts/check.sh`: successful, including the committed regression executable.
- `./gradlew --no-daemon clean build`: successful.
- Three alternating rounds of all-disabled/default/all-enabled server sprints completed cleanly.
- Default (`cursor_step` only): 8.64 ms/tick mean vs 8.86 all-disabled; overlapping ranges make this
  directional evidence, not a stable percentage claim.
- All-enabled experimental: 7.41 ms/tick mean; this does not describe shipped defaults.
- Production artifact: `build/libs/ultima-0.1.0.jar`, archive-verified as compiled mod content.

## Release posture

This release candidate is suitable for real-client testing, not for claims of universal Minecraft
performance. It makes no FPS or shader-performance claim. Promotion of any opt-in module requires:

1. current Sodium, Iris, Lithium, collision, entity, and chunk-mod compatibility tests;
2. real client shader-on/off visual and frame-time validation;
3. per-module reproducible benchmarks rather than all-enabled attribution;
4. long-running dedicated and integrated-server lifecycle testing.
