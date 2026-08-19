# Changelog

## Vanilla-client-compatible server hosting

Audit and declaration pass so a host can run Ultima on a dedicated or
integrated server while guests join with vanilla Minecraft 26.2 (no Ultima).

- `fabric.mod.json`: `"environment": "*"` is unchanged (may load on host
  client or dedicated server; it is not a both-sides-required handshake).
  Mixin configs are now explicit objects: simulation `ultima.mixins.json` is
  `"environment": "*"`, render `ultima.client.mixins.json` stays
  `"environment": "client"`. Description states vanilla-client hosting.
- Entrypoints were already correct: `main` → `dev.ultima.Ultima` (dedicated +
  integrated server), `client` → `dev.ultima.client.UltimaClient` (Fabric
  client-only slot). No dedicated-server-only entrypoint, so LAN Open-to-LAN
  still loads simulation.
- Network: no C2S/S2C channels, no Fabric optional/required payloads, no
  custom registries, no Mod Protocol. Vanilla join is the default Fabric
  case (protocol version only).
- `VanillaClientHostingChecks` enforces the metadata/source contract from
  the merged regression checkpoint.
- User docs: `SERVER_HOSTING.md` plus a README pointer, including the
  dedicated-server and Open-to-LAN hardware scenarios (not run in this
  session).

---

## mesher_fast_path Phase 3.2 (weighted unit-cube coverage)

Expand the unit-cube fast path to vanilla 26.2 `WeightedVariants` whose
every alternative is a proven 6-quad opaque cube (stone, dirt, deepslate,
sand, …). Pick the alternative with `BlockState.getSeed(pos)` + vanilla
`WeightedList.getRandomOrThrow` so UVs stay bit-identical. Multipart,
grass overlay, fluids, and true non-cubes stay fallback. Cache was
already keyed by `BlockState` identity (furnace/log variants were already
distinct SingleVariant entries). Default remains **OFF**. **No FPS/GPU
claim.** See `MESHER_FAST_PATH.md`. Retained-terrain GPU-time hypothesis
is in `RETAINED_GPU_TIME_HYPOTHESIS.md` (no retained code change).

## mesher_fast_path Phase 3.1 (hardware-ready prep)

Section-level fail-open + BlockState circuit breaker, unified
`FastPathCriteria` (glass/translucent always vanilla), lean production
snapshot flags, expanded equivalence + realistic CPU datasets, coverage
JSON, and a three-scene hardware runbook. Default remains **OFF**.
**No FPS/GPU claim.** See `MESHER_FAST_PATH.md` and `MESHER_HARDWARE_AB.md`.

## mesher_fast_path (draft, isolated from main / PR #3)

Hybrid unit-cube mesher behind `mesher_fast_path=false`. Packed 18³ snapshot,
cached vanilla cube quads, vanilla occlusion/lighting, vanilla fallback.
Equivalence tests PASS. CPU meshing-time microbench only. **No FPS/GPU claim.**
See `MESHER_FAST_PATH.md`. `gradlew test` / `gradlew build` PASS on this branch.

## Prompt #2.6.1 — retained foundation closed out: KEEP

PR #7 closed the three remaining rework items opened by Prompt #2.5's provenance
recovery: visual differences are explained by same-mode route variation (functional
parity PASS), A2 visibility re-entry is same-frame (2 same-frame, 0 one-frame-late
over 3,000 sampled frames), and bounded compaction is active and stable (37 triggers
over an 18,000-frame smoke run, no unbounded growth, no errors).

The six-pair counterbalanced chunk-flight A/B from SHA `6572f2e` remains the valid
performance dataset (diagnostic-only commits since then do not alter the release path):

- average FPS: 301.36 → 394.14, **+30.8%**, paired 95% CI +70.44…+115.13 FPS
- 1% low: 77.79 → 84.26, **+8.3%**, CI +1.03…+11.92 FPS
- terrain CPU total: 1,254,679 → 716,923 ns, **−42.9%**
- P99 frame time: 9.151 → 7.748 ms
- all 12 logs: 0 query-object errors, 0 `GL_INVALID_OPERATION`, 0 Mixin apply failures, 0 crashes

**FOUNDATION VERDICT: KEEP.**

Released as tag `ultima-foundation-final-2.6.1` at commit
`55e7605cd0e8d9fb0a5e3d39a16daa8b5b2f9c79` (main HEAD, PR #7 merge commit):
https://github.com/adaybekovt-boop/Ultima-/releases/tag/ultima-foundation-final-2.6.1

`Tested SHA = Released SHA`: **YES** for the diagnostic/compaction code tree (merge SHA
rebuilt, tree equals `858359f`). The six-pair FPS dataset SHA equals the released SHA:
**NO** — that dataset was collected on ancestor `6572f2e`; the commits after it
(`cf83913`, `858359f`) are opt-in diagnostics and do not change the default release path.

PR #3 (lab, base `cursor/forensic-review-9efc`) and PR #4 (mesher fast path, base `main`)
remain separate open drafts, isolated from `retained_terrain`, not merged, default off.

---

## Prompt #2.5 — provenance recovery (P0)

The previous KEEP documentation referenced hardware-tested commit
`4d518325d974c2e6b504208fe3d9262c8bbbfcb5`, but that object is not present in the
remote repository. The released `main` tree also lacked the bounded hidden-command
compaction described by Prompt #2.4.

Until the recovered code is rebuilt and the exact released SHA is re-tested on real
hardware, the previous chunk-flight numbers (**+27.85% average FPS**, **+12.87% 1% low**)
are historical test results only and are **not a release-valid performance claim**.

Recovery requirements:

- restore the documented bounded compaction trigger: hidden/total > 50% or total > 4096
  (only when hidden commands exist);
- preserve live-command order and rewrite owner command indices;
- replace the retained GPU command buffer transactionally between opaque render passes;
- build and test the actual current HEAD, not hard-coded historical SHAs;
- repeat the same real-hardware chunk-flight A/B on the exact final `main` SHA;
- only then record `Tested commit == Released commit: YES`.

PR #3 (`cursor/ultima-code-completion-lab-4423`) remains separate and must not be merged
as part of this provenance recovery.

---

## main — PR #2 merged (historical KEEP report; provenance not yet closed)

Foundation validation (Prompt #2.1–2.4) previously reported verdict **KEEP**.

Merged branch: `cursor/forensic-review-9efc` (PR #2).
PR #2 head: `ea94d594c6d45e7662675dc044792203971cc02d`.
Reported hardware test commit: `4d518325d974c2e6b504208fe3d9262c8bbbfcb5` (**missing from remote repository**).

**Not merged:** PR #3 (`cursor/ultima-code-completion-lab-4423`) remains a separate
draft. Lab gates stay default off.

### Historical real GPU A/B report — quarantined pending Prompt #2.5 retest

| Scene | Previously reported result |
|---|---|
| Stationary | KEEP |
| Yaw sweep | KEEP |
| Chunk-flight (final / most demanding) | **+27.85% average FPS**, **+12.87% 1% low** |

The same report stated that bounded command compaction stopped hidden-command growth,
with 0 crashes, 0 errors and visual parity PASS. Because the tested commit cannot be
traced to the released tree and that compaction was absent from merged `main`, these
figures must not be advertised as released-code results until the exact final SHA is
re-tested.

### Dedicated-server (independent proven result)

Entity-farm 6-pair A/B: **8.333 → 6.518 ms/tick (−21.78%)**. Integrated-server /
hitch work, not a GPU FPS claim.

### Defaults

Shipped simulation modules stay default-on. `retained_terrain` stays opt-in
(default off). Experimental lab modules from PR #3 are not in this tree.
