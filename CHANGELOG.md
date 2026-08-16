# Changelog

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
