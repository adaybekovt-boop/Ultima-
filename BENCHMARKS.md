# Benchmarks

**Status: there is no measurement for a 1.0 release yet, so Ultima claims no performance gain.**

A number appears in this file only together with the commit it was measured on (equal to the
release commit), the hardware, the JVM and its flags, the world seeds, and the raw CSV.
The `+30.8%` figure from an old `retained_terrain` run belongs to code about 150 commits older
and to a module that is off by default; it is kept in [`docs/history.md`](docs/history.md) and is
not a claim about this release.

## How to measure

The scripts, the scenarios each module is judged in, and what a run costs are described in
[`docs/BENCHMARKING.md`](docs/BENCHMARKING.md). Results are pasted into the table below together
with the run's environment block.

## How defaults are decided

Each module is judged in its own scenario, against the same build with the module off, in
alternating pairs (at least six).

- **Improvement of at least 3%** with a 95% confidence interval that does not cross zero, no
  divergence in the differential scenario tests, and no visual difference for render modules:
  the module is **on by default**.
- **Passes the differential tests but not the threshold:** the module is **opt-in**, with an
  honest description.
- **Fails a test or regresses:** the module is **removed from the code**.

A module never stays "default off forever": it earns a row below or it is deleted.

Compatibility with Sodium, Iris and Lithium means "does not break and does not duplicate". On
those stacks no gain is expected and none is claimed.

## Results

| Module | Scenario | Metric | Off | On | Change (95% CI) | Verdict |
|---|---|---|---|---|---|---|
| _no measured release yet_ | | | | | | |
