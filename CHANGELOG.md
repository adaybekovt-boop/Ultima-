# Changelog

Earlier development history and provenance notes are in [`docs/history.md`](docs/history.md).

## 1.0.0 — unreleased

First stable release. Ultima is a server-first optimization mod: six default modules trim
collision and entity-lookup work where the world is simulated, and guests keep using a vanilla
client. Everything else is opt-in. **No FPS or TPS improvement is claimed for this release;**
[`BENCHMARKS.md`](BENCHMARKS.md) lists what has actually been measured.

### Added

- Russian translation of the settings screen. Module names, descriptions, restart notices and
  the reason a module is off now come from language files.
- Mod icon, author and contact links in the mod metadata.
- A release workflow: pushing a `v*` tag builds the jar, checks it and publishes a GitHub Release.

### Changed

- `cursor_step` now switches itself off when Lithium, Canary or Radium is loaded, because those
  mods replace the collision iterators it optimizes.
- `terrain_metrics` is off by default. It only feeds the benchmark. Existing config files that
  set it explicitly keep their value.
- The README, changelog history and design notes were reorganised for players. Provenance material
  moved to [`docs/`](docs).

### Removed

- The `temporal` module. It never had a backend and did not change a single pixel. A leftover
  `temporal=true` line in `ultima.properties` is ignored.
- Test-only classes (the synthetic mesher kernel, its oracles and fixtures) no longer ship inside
  the mod jar.

### Fixed

- The block-iteration volume check could overflow on very large boxes.

### Compatibility

- Minecraft 26.2, Java 25, Fabric Loader 0.19.3 or newer, Fabric API 0.156.0+26.2 or newer.
- Sodium, Iris, Canvas, Lithium, Canary and Radium: overlapping modules switch themselves off;
  see the README for the exact rules.

### Known limits

- The client render modules and FSR upscaling are opt-in and have not been benchmarked for this
  release. Treat them as experimental.
