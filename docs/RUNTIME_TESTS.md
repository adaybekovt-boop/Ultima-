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
  --control build/clienttest/control --channel-tolerance 4 --max-changed-fraction 0.0025 \
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

The screenshot comparison is not bit-exact, and this is why:

- Two runs with the same modules still differ in a few percent of the frame (the spectator
  hint, sparse rasterization differences). The comparer takes a **control** run (every module off
  again), treats the pixels where baseline and control differ as noise (dilated by two pixels),
  fails if more than 10 % of an image is noise, and compares the candidate with the baseline
  everywhere else.
- Colour differences of up to four levels per channel are ignored, and up to 0.25 % of the
  pixels may differ. A missing or wrong face, a wrong texture or a lighting bug changes far more
  than that.
- The title screen (animated panorama) and the settings screens (they show the toggle states
  that the runs deliberately change) are listed but not compared.

CI measures 0 to 0.12 % of the pixels outside the noise for the shipped defaults, the mesher modules
and retained terrain alike. The defaults only enable simulation modules and cannot touch rendering,
so that residual is the environment's floor, not the modules.

What it proves: with the shipped defaults and with the mesher modules or retained terrain on, the
arrangement renders the same as vanilla to within that tolerance, and the game reaches the
settings screen and a world without a Mixin failure. What it does not prove: anything about a
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
