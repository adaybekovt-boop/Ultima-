# Changelog

## main — PR #2 merged (KEEP)

Foundation validation (Prompt #2.1–2.4) completed with verdict **KEEP**.

Merged branch: `cursor/forensic-review-9efc` (PR #2).
PR #2 head: `ea94d594c6d45e7662675dc044792203971cc02d`.
Reported hardware test commit: `4d518325d974c2e6b504208fe3d9262c8bbbfcb5`.

**Not merged:** PR #3 (`cursor/ultima-code-completion-lab-4423`) remains a separate
draft. Lab gates stay default off.

### Real GPU A/B (hardware validated)

All three scenes valid and statistically proven:

| Scene | Result |
|---|---|
| Stationary | KEEP |
| Yaw sweep | KEEP |
| Chunk-flight (final / most demanding) | **+27.85% average FPS**, **+12.87% 1% low** |

Also reported on the KEEP pass:

- Bounded command compaction stopped hidden-command growth; stability held on a route three times the test length
- 0 crashes, 0 errors
- Visual parity PASS

### Dedicated-server (already on this branch)

Entity-farm 6-pair A/B: **8.333 → 6.518 ms/tick (−21.78%)**. Integrated-server /
hitch work, not a GPU FPS claim.

### Defaults

Shipped simulation modules stay default-on. `retained_terrain` stays opt-in
(default off). Experimental lab modules from PR #3 are not in this tree.
