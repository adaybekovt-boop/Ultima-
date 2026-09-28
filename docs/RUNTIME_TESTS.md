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

## Compatibility with other mods

```bash
bash scripts/compat-server.sh lithium
```

Downloads the pinned mod from Modrinth (`scripts/fetch-mods.py`, versions and SHA-512 pinned in
`scripts/mods.lock.json`), starts the headless server with it and Ultima, force-loads every
common Mixin target, and checks that the module states Ultima logged are the ones the loaded mods
imply (with Lithium, the default simulation modules are off with `reason=incompatible_mod`) and
that the log holds no Mixin failure.

## Where they run

CI runs the scenario differential and the Lithium check on Linux for every pull request.
Windows CI runs the normal build and tests only. None of this measures speed; see
[`BENCHMARKING.md`](BENCHMARKING.md) for that.
