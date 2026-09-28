#!/usr/bin/env python3
"""Compare the digests written by scripts/scenario-diff.sh.

usage: compare-scenarios.py OUT_DIR BASELINE CONTROL CANDIDATE MODULE...

BASELINE and CONTROL run with the modules off, CANDIDATE with them on. The check fails when
  * a run did not finish ("status" is not ok),
  * the candidate did not actually enable every listed module (nothing engaged),
  * a scenario differs between BASELINE and CONTROL (the scenario is not deterministic), or
  * a scenario differs between BASELINE and CANDIDATE (a module changed observable behaviour).
"""
from __future__ import annotations

import difflib
import sys
from pathlib import Path


def parse_summary(path: Path) -> dict:
    result = {"status": "missing", "modules": {}, "scenarios": {}, "missing_palette": []}
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "status":
            result["status"] = " ".join(parts[1:])
        elif parts[0] == "module":
            result["modules"][parts[1]] = parts[2].split("=", 1)[1] == "true"
        elif parts[0] == "scenario":
            fields = dict(item.split("=", 1) for item in parts[2:])
            result["scenarios"][parts[1]] = fields
        elif parts[0] == "palette-missing":
            result["missing_palette"].append(" ".join(parts[1:]))
    return result


def first_difference(directory: Path, scenario: str, left: str, right: str, limit: int = 12) -> list[str]:
    a = (directory / f"{left}.{scenario}.txt").read_text(encoding="utf-8").splitlines()
    b = (directory / f"{right}.{scenario}.txt").read_text(encoding="utf-8").splitlines()
    diff = difflib.unified_diff(a, b, f"{left}", f"{right}", n=0, lineterm="")
    lines: list[str] = []
    for text in diff:
        lines.append(text if len(text) < 260 else text[:257] + "...")
        if len(lines) >= limit:
            break
    return lines


def main(argv: list[str]) -> int:
    if len(argv) < 5:
        print(__doc__, file=sys.stderr)
        return 2
    directory = Path(argv[0])
    baseline_label, control_label, candidate_label = argv[1:4]
    modules = argv[4:]
    runs = {label: parse_summary(directory / f"{label}.summary.txt") for label in argv[1:4]}

    failures: list[str] = []
    for label, run in runs.items():
        if run["status"] != "ok":
            failures.append(f"run {label}: status is '{run['status']}'")

    baseline = runs[baseline_label]
    control = runs[control_label]
    candidate = runs[candidate_label]

    enabled_in_baseline = [m for m in modules if baseline["modules"].get(m)]
    if enabled_in_baseline:
        failures.append(f"baseline unexpectedly had modules enabled: {enabled_in_baseline}")
    disengaged = [m for m in modules if not candidate["modules"].get(m)]
    if disengaged:
        failures.append(
            "candidate did not enable: " + ", ".join(disengaged)
            + " (another mod loaded, or the config was ignored); a differential over inactive modules proves nothing"
        )

    expected = set(baseline["scenarios"]) | set(candidate["scenarios"]) | set(control["scenarios"])
    print(f"{'scenario':<24} {'lines':>7}  control  candidate")
    for scenario in sorted(expected):
        base = baseline["scenarios"].get(scenario)
        ctrl = control["scenarios"].get(scenario)
        cand = candidate["scenarios"].get(scenario)
        if not (base and ctrl and cand):
            failures.append(f"scenario {scenario} is missing from at least one run")
            print(f"{scenario:<24} {'?':>7}  MISSING")
            continue
        control_ok = base["sha256"] == ctrl["sha256"]
        candidate_ok = base["sha256"] == cand["sha256"]
        print(f"{scenario:<24} {base['lines']:>7}  {'same' if control_ok else 'DIFFERS':<7}  {'same' if candidate_ok else 'DIFFERS'}")
        if not control_ok:
            failures.append(f"scenario {scenario} is not deterministic (baseline != control)")
            failures.extend("    " + line for line in first_difference(directory, scenario, baseline_label, control_label))
        elif not candidate_ok:
            failures.append(f"scenario {scenario}: modules on changed the world state")
            failures.extend("    " + line for line in first_difference(directory, scenario, baseline_label, candidate_label))

    for run_label, run in runs.items():
        if run["missing_palette"]:
            print(f"note: {run_label} skipped names missing in this Minecraft version: {run['missing_palette']}")

    if failures:
        print("\nSCENARIO DIFFERENTIAL FAILED", file=sys.stderr)
        for failure in failures:
            print(failure, file=sys.stderr)
        return 1
    print("\nScenario differential passed: modules off, off again and on produced identical world state.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
