# Benchmarking Ultima

How a module earns "on by default", "opt-in" or removal. The decision rule is in
[`BENCHMARKS.md`](../BENCHMARKS.md); this page is how to produce the numbers. Nothing here runs in
CI, because a benchmark needs a quiet machine, and a client benchmark needs a GPU.

## Before you start

- A commit you are willing to name: results only count for the commit they were measured on.
  The scripts warn about a dirty working tree.
- JDK 25 on `PATH` (or `JAVA_HOME`), Python 3.9+, the Gradle wrapper. The server benchmark is
  pure Java and Python and runs on Windows, Linux and macOS.
- A quiet machine: close everything else, plug in a laptop, use a high-performance power plan, and
  do not touch it while a run is going. Thermal throttling and background updates are the usual
  reasons a benchmark lies.
- No other mods. Every module that overlaps Sodium, Iris or Lithium switches itself off, and the
  scripts refuse a run whose modules are not in the requested state.

## 1. Prove behaviour first

A speedup is worthless if the world state differs. Run the differential scenario test first:

```bash
bash scripts/scenario-diff.sh
```

It runs deterministic scenarios on a real dedicated server with the simulation modules off, off
again (a control that proves the scenarios are deterministic) and on, and requires identical world
state. It records the verdict in `build/scenarios/differential.status`; the benchmark reads it and
refuses to call a module "on by default" without a passing differential. CI runs the same test.

## 2. Dedicated-server tick time

```bash
python3 scripts/bench-modules.py --dry-run                 # plan and time estimate, runs nothing
python3 scripts/bench-modules.py --pairs 6                 # every optimization, one at a time
python3 scripts/bench-modules.py --modules cursor_step,tag_bitsets --pairs 6
python3 scripts/bench-modules.py --profile default --pairs 6   # the shipped defaults together
```

For each module the script builds the workload it targets, once with every simulation module off
and once with only that module (and what it depends on) on. It measures the wall time and the
allocated bytes of every server tick after a warmup, in alternating pairs, and reports the paired
gain with a 95% confidence interval.

| Workload | What it builds | Modules judged on it |
|---|---|---|
| `farm` | about 800 wandering mobs and loose items over 12 x 12 chunks | `entity_section_lookup`, `entity_query_early_out`, `tag_bitsets`, `state_property_cache` |
| `collision` | 200 mobs in a walled field of stairs, slabs, fences and walls | `block_collision_shape`, `collision_shell_skip`, `supporting_block_shape_skip`, `full_cube_move`, `cursor_step` |
| `hoppers` | twelve rings of hoppers circulating items plus 600 idle hoppers | `blockentity_sleeping`, `container_slot_mask` |
| `furnaces` | 160 furnaces smelting for the whole run | `recipe_match_cache` |

Cost: every server run takes about two minutes, so a full pass is 12 modules x 6 pairs x 2 sides,
roughly six hours; `--dry-run` prints the exact estimate. Run a subset, or run it overnight.
`--warmup` and `--ticks` change the length (defaults 600 and 1200).

Output goes to `bench-results/<sha>-<time>/`: `environment.json`, `results.json`, `results.md` (a
table ready to paste into `BENCHMARKS.md`), and the raw per-tick CSV of every run under `raw/`.

Two things this benchmark does not do. It has no player bots: a server with twenty connected
players is not simulated, only the world load. And it is a server-side tick benchmark, not an FPS
claim.

## 3. Client rendering on the vanilla renderer

Needs a GPU, a display and bash. Per module, per scene:

```bash
bash scripts/bench-client-module.sh mesher_fast_path 6
SCENE=chunk_flight bash scripts/bench-client-module.sh retained_terrain 6
SCENE=yaw_sweep    bash scripts/bench-client-module.sh java_mesher 6
```

Both sides start with every module off; the ON side turns on the module and what it needs. The
script prints the summary from `scripts/summarize-client-bench.py`: average FPS, 1% low and frame
times per pair, with a 95% confidence interval. The same world copy, camera, resolution and
distances must be used for every launch (`WORLD`, `GAME_DIR`, `WIDTH`, `HEIGHT`; see
`scripts/bench-client.sh`).

A render module also needs a visual check: `CAPTURE_SCREENSHOTS=1` grabs the first and last sample
frame of each run so the two sides can be compared. A module that changes pixels is not
"pixel-identical" whatever its speed.

`fsr_upscaling` renders below native resolution on purpose, so the 3% rule does not apply to it:
it stays an opt-in quality trade.

## 4. Sodium + Iris + Lithium

On that stack nothing is expected to get faster, and none of it should be claimed. The check is
that nothing gets slower or breaks: run the default profile against every module off, on a client
with the three mods installed and a shader pack, and require a result inside the noise.

## Recording a result

Paste the table from `results.md` (server) or the summarizer's verdict lines (client) into
`BENCHMARKS.md` together with the environment block: commit, CPU, memory, operating system, JVM and
flags, world seed and scene. A number without the commit it was measured on is not accepted.
Then apply the verdicts: a module that clears the rule becomes default-on in `UltimaModules`; one
that only passes the differential stays opt-in with an honest description; one that fails or
regresses is deleted.
