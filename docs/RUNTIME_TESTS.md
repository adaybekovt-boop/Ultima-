# Runtime tests

`./gradlew test` runs plain Java checks against real Minecraft classes. It does **not** apply
Mixins, so it cannot say what Ultima does to a running game. These tests do: they start the real
dedicated server (and, for the client test, the real client) with Ultima loaded and look at what
happens.

## Differential scenarios (real dedicated server)

```bash
bash scripts/scenario-diff.sh
```

Runs the world scenarios in `src/scenario` three times, each on a fresh flat world:

| Run | Simulation modules | Purpose |
|---|---|---|
| `off_a` | all off | baseline |
| `off_b` | all off | control: proves the scenarios are deterministic |
| `on` | all on | candidate |

The digests must be identical. The comparer (`scripts/compare-scenarios.py`) also fails when the
`on` run did not really enable the modules (another mod loaded, config ignored), because a
differential over inactive modules proves nothing, and when the control run differs from the
baseline, because then a difference could be noise.

| Scenario | What it does | Modules it exercises |
|---|---|---|
| `collision_positive`, `collision_negative`, `collision_chunk_edge` | drives boxes of seven widths and six heights through a field of random collision shapes with `Entity.move`, three times over positive, negative and chunk-border coordinates, and records every position bit for bit | `block_collision_shape`, `collision_shell_skip`, `supporting_block_shape_skip`, `full_cube_move`, `cursor_step` |
| `entity_query` | scatters motionless items through a mostly empty column and records what box queries return, including boxes across empty sections and unloaded chunks | `entity_section_lookup`, `entity_query_early_out` |
| `hoppers` | hopper chains, item pickup, a furnace fed and drained by hoppers, partially filled chests; records every container slot after 280 ticks | `blockentity_sleeping`, `container_slot_mask`, `recipe_match_cache` |
| `recipes` | 2,500 seeded crafting lookups and a smelting and blasting lookup for every item, before and after a `/reload` | `recipe_match_cache` |
| `tags_and_states` | tag membership and cached block-state properties of every block state, before and after a `/reload` | `tag_bitsets`, `state_property_cache` |

The harness lives in a separate source set (`src/scenario`) declared as its own Loom mod
(`ultima-scenarios`). It is not part of the mod jar, and it does nothing unless the run sets
`-Dultima.scenario.label`. To add a scenario, implement `Scenario`, add it to `Scenarios.all()`,
and keep it deterministic: no unseeded randomness, no mob AI, no random ticks in the arena.

Outputs are in `build/scenarios/`: one file of digest lines per scenario and run, a summary per
run, and the server logs. When a scenario differs, the failure prints the first differing lines.

## Client test (real client)

```bash
bash scripts/client-test.sh baseline                      # every module off
bash scripts/client-test.sh mesher java_mesher,mesher_fast_path,render_snapshot,section_task_queue
bash scripts/client-test.sh defaults                      # the shipped defaults
python3 scripts/compare-screenshots.py build/clienttest/baseline build/clienttest/mesher \
  --control build/clienttest/control --channel-tolerance 1 --max-changed-fraction 0.005 \
  --ignore '01_title.png' '02_settings_*'
```

Starts the real client with Ultima and the Fabric client game test in `src/clienttest`. The
test opens Ultima's settings screen and clicks through its categories (that walks every
translation key the screen uses), creates a flat world, builds a small arrangement of blocks
that crosses chunk and section borders (full cubes, stairs, slabs, glass, leaves, fences, a wall,
a cactus, glowstone) and saves a screenshot from three fixed camera positions. The player is a
spectator, the weather is clear, the time is noon, random ticks are off, clouds are off, and the
test waits until every chunk is downloaded and rendered before each screenshot.

Without a display it runs under `xvfb` with Mesa software rendering (CI installs both), so it
needs no GPU; on a desktop it opens a window. Pixels are only comparable between runs on the
same machine and graphics stack.

How the screenshots are compared (`scripts/compare-screenshots.py`):

- Two runs with the same modules render the arrangement pixel for pixel alike: the spectator
  camera removes the arm, falling and hand sway, and clouds, water, torches and random ticks are
  out of the scene. A **control** run, every module off again, guards this: the pixels where
  baseline and control differ are treated as noise (dilated by two pixels), excluded from the
  comparison, and the run fails if more than 1 % of an image is noise. CI measures 0 %.
- The shipped defaults and retained terrain must match the baseline with one colour level of
  slack per channel. The mesher modules get a budget of 0.5 % of the pixels.
- The title screen (animated panorama) and the settings screens (they show the toggle states that
  the runs deliberately change) are listed but not compared.
- Every difference that is not excluded is printed with a coarse map and its hot spots (tile
  position, pixel count, colour before and after), because a bare percentage is hard to act on.

CI result on the mesher modules: 0.31 % of the pixels of the first camera position differ by more
than one level (the other two positions: 0 % and 0.003 %). Almost all of it is a band of tinted
leaf pixels that are one or two levels of 255 off, e.g. (45, 63, 21) against (46, 65, 22); a few
pixels differ by up to 15. With the control at 0 % this is a deterministic difference between the
mesher modules' output and vanilla's, not noise, most likely a rounding difference in the vertex
colour of tinted faces. It is inside the budget and has not been traced to a cause; read the hot
spots in the `client` job log before making `java_mesher` or `mesher_fast_path` default.

What it proves: with the shipped defaults or retained terrain on, the arrangement renders like
vanilla; with the mesher modules on it renders like vanilla except for the residual above; and the
game gets through the settings screen and a world without a Mixin failure. What it does not prove: anything about a
real GPU, shaders (Iris), other resource packs, or motion. Those stay manual checks.

## Compatibility with other mods

```bash
bash scripts/compat-server.sh lithium
bash scripts/compat-client.sh sodium_iris sodium iris
```

Downloads the pinned mod from Modrinth (`scripts/fetch-mods.py`, versions and SHA-512 pinned in
`scripts/mods.lock.json`), starts the headless server with it and Ultima, force-loads every
common Mixin target, and checks that the module states Ultima logged are the ones the loaded mods
imply (with Lithium, the default simulation modules are off with `reason=incompatible_mod`) and
that the log holds no Mixin failure.

`compat-client.sh` does the same on the real client: it starts it with the mods next to Ultima,
asks for every render module and the default-on simulation modules, and checks that Sodium and
Iris switch the render modules off with `reason=incompatible_mod`, Lithium the simulation ones,
Iris also FSR, and that Mod Menu changes nothing. CI runs Mod Menu, Lithium, Sodium and Sodium +
Iris.

The mod versions are pinned in `scripts/mods.lock.json` (Modrinth version number and SHA-512),
and the download fails when the bytes differ. To bump one, run
`python3 scripts/fetch-mods.py <dir> --print-lock <slug>` and copy the printed entry.

## Where they run

CI runs everything on this page on Linux for every pull request (jobs `runtime`, `client` and
`client compat`); the release workflow repeats the server checks before it publishes. Windows CI
runs the normal build and tests only. None of this measures speed; see
[`BENCHMARKING.md`](BENCHMARKING.md) for that.

## Not covered

Real GPU rendering, Iris shader packs, Canvas, LAN and dedicated-server play with a vanilla
guest, and long sessions. Check these by hand before a release.
