# Ultima

Optimization mod for Minecraft Java Edition 26.2 (Fabric). It trims server-side simulation work
for everyone on a server, and ships optional client render modules for players who use the
vanilla renderer. Guests join with an ordinary vanilla client: they install nothing.

Ultima makes **no performance claim it has not measured**. The results for each release, with
the commit, hardware and raw data, are in [`BENCHMARKS.md`](BENCHMARKS.md). Where a module has no
measurement there, treat it as unproven.

## Who it is for

| You run | What Ultima does |
|---|---|
| A dedicated server, without Lithium | Turns on six collision and entity-lookup optimizations by default. They run where the world is simulated, so every connected player benefits. |
| Singleplayer with Open to LAN | The same, for the world you host. Friends join with a vanilla client. |
| The vanilla renderer on your own client | Optional render modules, all off by default. |
| Sodium + Iris + Lithium | Nothing to gain. Every overlapping module switches itself off, and what is left is a settings screen. Ultima will not stack on top of those mods, and it does not claim to. |

## Requirements

- Minecraft 26.2 and Java 25
- Fabric Loader 0.19.3 or newer
- Fabric API 0.156.0+26.2 or newer
- Optional: Mod Menu, for a settings entry

## Install

1. Download `ultima-<version>.jar` from the [Releases](https://github.com/adaybekovt-boop/Ultima-/releases)
   page. Do not use the `-sources` jar.
2. Put it in the `mods` folder next to Fabric API.

**Dedicated server:** install it on the server only. Players do not need Fabric or Ultima.

**Open to LAN:** install it on the client that hosts the world. Guests need nothing.

**Client render modules:** install it on your own client. They run only on the machine that has
the mod and never affect other players.

Details of what a vanilla guest does and does not see are in
[`docs/SERVER_HOSTING.md`](docs/SERVER_HOSTING.md).

## Compatibility with Sodium, Iris and Lithium

Ultima checks which mods are loaded and switches off what would overlap. Nothing needs
configuring, and Ultima never adds `breaks` entries for other mods.

- **Lithium, Canary, Radium** replace the hot collision and entity code Ultima also touches. All
  collision, entity-query, hopper, tag, state-property and slot-mask modules turn off.
  `recipe_match_cache` stays available because Lithium has no equivalent.
- **Sodium, Iris, Canvas** replace terrain rendering. The terrain and mesher modules turn off.
  `fsr_upscaling` is decided separately: Canvas turns it off, Sodium alone is allowed, and Iris
  turns it off because Iris has no official hook after its final shader pass.
- The settings screen shows why a module is off, for example "Disabled: Sodium detected".

## Modules

Every module is listed in the in-game settings screen and in `config/ultima.properties`.
"Restart" applies to all of them: Mixins are chosen at launch, so a change needs a game restart.

### On by default

| Module | Side | Turns off with |
|---|---|---|
| `entity_section_lookup` | both | Lithium family |
| `block_collision_shape` | both | Lithium family |
| `collision_shell_skip` (needs `cursor_step`) | both | Lithium family |
| `supporting_block_shape_skip` | both | Lithium family |
| `full_cube_move` | both | Lithium family |
| `cursor_step` | both | Lithium family |
| `settings_ui` (title-screen button) | client | nothing |

### Opt-in simulation

`blockentity_sleeping`, `tag_bitsets`, `state_property_cache`, `container_slot_mask` and
`entity_query_early_out` turn off with the Lithium family. `recipe_match_cache` does not.

### Opt-in client rendering

`retained_terrain`, `java_mesher`, `mesher_fast_path`, `render_snapshot`, `section_task_queue`
and `rgss_endpoint` turn off with Sodium, Iris or Canvas. `fsr_upscaling` is the AMD FSR1
spatial upscaler with a quality preset; see [`docs/FSR_UPSCALING.md`](docs/FSR_UPSCALING.md).

### Experimental companions

`iris_shader_frontend_artifact_cache`, `cross_pipeline_admission_broker` and
`render_warmup_system` target Sodium + Iris setups. They are opt-in prototypes and are not part of
what 1.0 promises.

### Instrumentation

`server_metrics`, `client_benchmark` and `terrain_metrics` only record numbers. They do not
change gameplay or pixels. `server_metrics` powers `/ultima profile`
([`docs/SERVER_TELEMETRY.md`](docs/SERVER_TELEMETRY.md)).

## Settings

- **In game:** the Ultima button on the title screen, Mod Menu, or `/ultima config` on the client.
- **File:** `config/ultima.properties`, one `module_key=true|false` line per module. Keys of
  modules that no longer exist are ignored.
- **Commands:** `/ultima config` opens the screen on a client and prints the config path on a
  server. `/ultima debug compatibility` lists every module with its state and the reason, and
  writes the full JSON report to the log. `/ultima profile [seconds]` (operators, needs
  `server_metrics`) records a short server trace.

The settings screen is available in English and Russian.

## Building from source

```bash
./gradlew build
```

Needs JDK 25. The mod jar is `build/libs/ultima-<version>.jar`. Contributor notes, the test
suites and the CI layout are in [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## More

- [`CHANGELOG.md`](CHANGELOG.md): what changed in each release
- [`BENCHMARKS.md`](BENCHMARKS.md): measurements and the rule used to decide defaults
- [`docs/`](docs): design notes and the provenance history
- [Issues](https://github.com/adaybekovt-boop/Ultima-/issues)
