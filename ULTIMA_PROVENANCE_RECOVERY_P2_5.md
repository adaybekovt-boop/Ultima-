# ULTIMA — Prompt #2.5 Provenance Recovery

Date: 2026-08-16

## Executive status

**P0 provenance is not closed yet.**

The previous hardware report named tested commit
`4d518325d974c2e6b504208fe3d9262c8bbbfcb5`, but GitHub cannot resolve that object and
it is absent from every remote branch currently visible to the repository. A fresh remote
checkout cannot recover a commit that was never pushed; the reflog/stash of the original
Claude/Cursor machine would be required to prove whether the object still exists locally there.

Merged `main` at investigation start was:

`ecfd7c74941fa5eb6f2e54952322b76ac6e796cc`

That tree did **not** contain bounded retained-command compaction. Its `SubmitGroup.java`
explicitly stated that hidden `instanceCount=0` records were kept and not compacted.

Therefore the previous `+27.85% average FPS / +12.87% 1% low` chunk-flight result is
quarantined until the recovered released code is tested again on the exact final SHA.

## Lost commit result

Status: **NOT FOUND IN REMOTE REPOSITORY**.

The environment performing this recovery has no access to the reflog or stash of the original
machine that produced the unpushed hardware-test commit. That part cannot be reconstructed from
GitHub metadata.

## PR #3 is not the missing implementation

Draft PR #3 contains an experimental command compactor, but it is not equivalent to the documented
Prompt #2.4 behavior. The lab version compacts CPU command arrays during `prepare()` and does not
provide the documented transactional retained GPU-command-buffer replacement between opaque render
passes. PR #3 remains untouched and unmerged.

## Recovered Prompt #2.4 contract

Recovery branch:

`fix/provenance-compaction-p0`

The recovered implementation follows the documented contract:

1. Compaction only matters when hidden commands exist.
2. Trigger when either:
   - `hidden / total > 50%` (strictly greater than 50%), or
   - `total > 4096`.
3. Preserve relative order of live commands.
4. Rewrite every surviving owner's command index.
5. Detach dropped hidden owners so a section can be reattached when it becomes visible again.
6. Never compact an indirect GPU command buffer in place while it is part of an active opaque pass.
7. A submit schedules compaction; the next frame applies it before uploads/draws, strictly between
   opaque passes.
8. Prepare a replacement GPU command buffer first, compact CPU state, atomically swap the group to
   the replacement, retire the old buffer, then upload the compacted list before the next draw.

Pure regression coverage verifies:

- exact 50% hidden does not fire the strict `>50%` trigger;
- >50% hidden compacts;
- `total > 4096` with hidden records compacts;
- live command order is preserved;
- owner indices are rewritten;
- dropped owners are detached;
- compacted records are marked dirty for full upload to the replacement buffer;
- a dropped owner can be reattached later.

## CI provenance fix

The previous GitHub Actions workflow checked out two hard-coded old SHAs, so a green workflow did
not prove that the triggering `main` HEAD built.

The recovery workflow now:

- checks out the actual triggering `github.sha`;
- asserts `git rev-parse HEAD == $GITHUB_SHA`;
- runs on the recovery branch, PRs to `main`, and pushes to `main`;
- uses Java 25 / Gradle 9.5.1;
- runs `clean build test`;
- runs forensic checks, including the recovered command-compaction test;
- runs the benchmark summarizer self-test;
- repeats on Ubuntu and Windows.

## Performance-claim status

Previous hardware result: historical / quarantined.

`Tested commit == Released commit: NO` until the exact final `main` SHA is re-run with the same
Prompt #2.4 chunk-flight A/B protocol (6 counterbalanced OFF/ON pairs) and the result is recorded.

No new FPS number is claimed by this recovery branch.

## Required final gate

Before Prompt #2.5 can close:

1. CI must pass on the recovery code.
2. Recovery code must be merged to `main`.
3. CI must pass again on the resulting exact `main` SHA.
4. The same real-hardware chunk-flight A/B must run on that exact `main` SHA.
5. `CHANGELOG.md` and `REAL_PERFORMANCE_REPORT.md` must be updated with the real tested/released SHA.
6. Only then may the final line become:

`Tested commit == Released commit: YES`
