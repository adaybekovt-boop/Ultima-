#!/usr/bin/env python3
"""Per-module dedicated-server benchmark: paired A/B on the real server tick, portable across OSes.

For every simulation module this runs the workload the module targets with only that module
(plus its dependencies) on, against the same build with every simulation module off, in
alternating pairs, and applies the default-decision rule from BENCHMARKS.md.

    python3 scripts/bench-modules.py --pairs 6                       # every module
    python3 scripts/bench-modules.py --modules cursor_step,tag_bitsets --pairs 6
    python3 scripts/bench-modules.py --profile default               # shipped defaults vs all off
    python3 scripts/bench-modules.py --dry-run                       # print the plan and time estimate
    python3 scripts/bench-modules.py --self-test

Needs JDK 25 on PATH (or JAVA_HOME) and the Gradle wrapper. No tmux, no bash. Run it on a quiet
machine and do not touch it while it runs. Results go to bench-results/<sha>-<utc>/.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench_stats  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RUN_DIR = ROOT / "run" / "scenarios"
OUT_DIR = ROOT / "build" / "scenarios"
MODULES_SOURCE = ROOT / "src" / "main" / "java" / "dev" / "ultima" / "config" / "UltimaModules.java"

# Every module that runs on a dedicated server, in registry order.
SIM_MODULES = [
    "entity_section_lookup", "block_collision_shape", "collision_shell_skip",
    "supporting_block_shape_skip", "full_cube_move", "cursor_step", "server_metrics",
    "blockentity_sleeping", "recipe_match_cache", "tag_bitsets", "state_property_cache",
    "container_slot_mask", "entity_query_early_out",
]
DEFAULT_ON = [
    "entity_section_lookup", "block_collision_shape", "collision_shell_skip",
    "supporting_block_shape_skip", "full_cube_move", "cursor_step",
]
DEPENDENCIES = {"collision_shell_skip": ["cursor_step"]}
# The load each module is meant to help. server_metrics is instrumentation, not an optimization.
MODULE_WORKLOAD = {
    "entity_section_lookup": "farm",
    "entity_query_early_out": "farm",
    "tag_bitsets": "farm",
    "state_property_cache": "farm",
    "block_collision_shape": "collision",
    "collision_shell_skip": "collision",
    "supporting_block_shape_skip": "collision",
    "full_cube_move": "collision",
    "cursor_step": "collision",
    "blockentity_sleeping": "hoppers",
    "container_slot_mask": "hoppers",
    "recipe_match_cache": "furnaces",
}
ALL_WORKLOADS = ["farm", "collision", "hoppers", "furnaces"]
METRICS = ["mspt_mean", "mspt_p99", "alloc_mean_bytes"]

SERVER_PROPERTIES = """level-seed=1
level-type=minecraft:flat
generate-structures=false
spawn-monsters=false
spawn-animals=false
allow-nether=false
online-mode=false
view-distance=8
simulation-distance=8
max-tick-time=-1
spawn-protection=0
enable-status=false
sync-chunk-writes=false
motd=ultima-bench
"""


# --------------------------------------------------------------------------- source parsing

def parse_registry(source: str) -> dict[str, dict]:
    """Read key, default, side and dependencies of every module from UltimaModules.java."""
    body = source[source.index("List<Module> ALL = List.of("):]
    blocks = re.split(r"(?=new Module\(|Module\.client\()", body)[1:]
    modules: dict[str, dict] = {}
    for block in blocks:
        head = re.match(r'(new Module|Module\.client)\(\s*"([a-z_]+)",\s*(true|false)', block)
        if not head:
            continue
        deps = re.search(r'",\s*List\.of\(([^)]*)\)', block)
        modules[head.group(2)] = {
            "default": head.group(3) == "true",
            "client": head.group(1) == "Module.client",
            "dependencies": re.findall(r'"([a-z_]+)"', deps.group(1)) if deps else [],
        }
    return modules


def self_test() -> None:
    bench_stats.self_test()
    registry = parse_registry(MODULES_SOURCE.read_text(encoding="utf-8"))
    common = [key for key, info in registry.items() if not info["client"]]
    assert common == SIM_MODULES, f"SIM_MODULES drifted from UltimaModules.java: {common}"
    defaults = [key for key in common if registry[key]["default"]]
    assert defaults == DEFAULT_ON, f"DEFAULT_ON drifted from UltimaModules.java: {defaults}"
    parsed_deps = {key: registry[key]["dependencies"] for key in common if registry[key]["dependencies"]}
    assert parsed_deps == DEPENDENCIES, f"DEPENDENCIES drifted from UltimaModules.java: {parsed_deps}"
    optimizations = [key for key in SIM_MODULES if key != "server_metrics"]
    assert sorted(MODULE_WORKLOAD) == sorted(optimizations), "every optimization needs exactly one workload"
    assert set(MODULE_WORKLOAD.values()) <= set(ALL_WORKLOADS)
    print("bench-modules self-test passed")


# --------------------------------------------------------------------------- environment

def run_text(command: list[str]) -> str:
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=20, check=False)
        return (completed.stdout + completed.stderr).strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def cpu_name() -> str:
    if sys.platform.startswith("linux"):
        for line in Path("/proc/cpuinfo").read_text(errors="replace").splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    if sys.platform == "darwin":
        return run_text(["sysctl", "-n", "machdep.cpu.brand_string"])
    if sys.platform == "win32":
        lines = [ln.strip() for ln in run_text(["wmic", "cpu", "get", "name"]).splitlines() if ln.strip()]
        return lines[1] if len(lines) > 1 else platform.processor()
    return platform.processor()


def memory_gib() -> str:
    try:
        if sys.platform.startswith("linux"):
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemTotal"):
                    return f"{int(line.split()[1]) / 1048576:.1f}"
        if sys.platform == "darwin":
            return f"{int(run_text(['sysctl', '-n', 'hw.memsize'])) / 2**30:.1f}"
        if sys.platform == "win32":
            lines = [ln.strip() for ln in run_text(["wmic", "computersystem", "get", "totalphysicalmemory"]).splitlines() if ln.strip()]
            return f"{int(lines[1]) / 2**30:.1f}"
    except (ValueError, IndexError, OSError):
        pass
    return "unknown"


def environment() -> dict:
    sha = run_text(["git", "-C", str(ROOT), "rev-parse", "HEAD"])
    dirty = bool(run_text(["git", "-C", str(ROOT), "status", "--porcelain"]))
    java = run_text(["java", "-version"]).splitlines()
    return {
        "git_sha": sha,
        "git_dirty": dirty,
        "utc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "os": platform.platform(),
        "cpu": cpu_name(),
        "cpu_threads": os.cpu_count(),
        "memory_gib": memory_gib(),
        "java": java[0] if java else "unknown",
        "jvm_flags": "-Xmx6G (Loom run configuration)",
        "world": "flat, seed 1, fixed workload positions, mob spawning off",
    }


# --------------------------------------------------------------------------- running

def gradlew() -> list[str]:
    return [str(ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))]


def prepare_run_dir(enabled: set[str]) -> None:
    for name in ("world", "logs", "config"):
        target = RUN_DIR / name
        if target.exists():
            shutil.rmtree(target)
    (RUN_DIR / "config").mkdir(parents=True, exist_ok=True)
    (RUN_DIR / "eula.txt").write_text("eula=true\n")
    (RUN_DIR / "server.properties").write_text(SERVER_PROPERTIES)
    lines = [f"{key}={'true' if key in enabled else 'false'}" for key in SIM_MODULES]
    (RUN_DIR / "config" / "ultima.properties").write_text("\n".join(lines) + "\n")


def parse_summary(path: Path) -> dict:
    result: dict = {"modules": {}}
    for line in path.read_text().splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "module":
            result["modules"][parts[1]] = parts[2].endswith("=true")
        elif parts[0] == "status":
            result["status"] = " ".join(parts[1:])
        elif len(parts) == 2:
            try:
                result[parts[0]] = float(parts[1])
            except ValueError:
                result[parts[0]] = parts[1]
    return result


def run_side(label: str, workload: str, enabled: set[str], args: argparse.Namespace) -> dict:
    prepare_run_dir(enabled)
    command = gradlew() + [
        "runScenarioServer", "--console=plain",
        f"-Pultima.scenario.label={label}",
        f"-Pultima.scenario.bench={workload}",
        f"-Pultima.scenario.warmup={args.warmup}",
        f"-Pultima.scenario.ticks={args.ticks}",
    ]
    print(f"    {label}: {workload}, modules on = {sorted(enabled) or 'none'}", flush=True)
    completed = subprocess.run(command, cwd=ROOT, timeout=args.run_timeout, check=False,
                               stdout=subprocess.DEVNULL if not args.verbose else None,
                               stderr=subprocess.STDOUT if not args.verbose else None)
    summary_path = OUT_DIR / f"{label}.bench.summary.txt"
    if completed.returncode != 0 or not summary_path.exists():
        raise SystemExit(f"run {label} failed (gradle exit {completed.returncode}); see {RUN_DIR / 'logs' / 'latest.log'}")
    summary = parse_summary(summary_path)
    if not str(summary.get("status", "")).startswith("ok"):
        raise SystemExit(f"run {label} did not complete: {summary.get('status')}")
    wrong = [key for key in SIM_MODULES if summary["modules"].get(key, False) != (key in enabled)]
    if wrong:
        raise SystemExit(
            f"run {label}: modules {wrong} are not in the requested state (another mod loaded, or the config was ignored)")
    return summary


def with_dependencies(module: str) -> set[str]:
    enabled = {module}
    for dependency in DEPENDENCIES.get(module, []):
        enabled |= with_dependencies(dependency)
    return enabled


def differential_status() -> str:
    status = OUT_DIR / "differential.status"
    if not status.exists():
        return "unknown"
    return status.read_text().split()[0]


def measure(name: str, workload: str, enabled: set[str], args: argparse.Namespace, out: Path) -> dict:
    print(f"== {name} on workload '{workload}', {args.pairs} pairs", flush=True)
    off: list[dict] = []
    on: list[dict] = []
    for pair in range(1, args.pairs + 1):
        order = ("off", "on") if pair % 2 == 1 else ("on", "off")
        for side in order:
            label = f"{name}_{workload}_pair{pair}_{side}"
            summary = run_side(label, workload, enabled if side == "on" else set(), args)
            (off if side == "off" else on).append(summary)
            for suffix in ("bench.csv", "bench.summary.txt"):
                source = OUT_DIR / f"{label}.{suffix}"
                (out / "raw").mkdir(parents=True, exist_ok=True)
                shutil.copy(source, out / "raw" / source.name)
    differential = differential_status()
    rows = {}
    for metric in METRICS:
        gains = [bench_stats.gain_percent(o[metric], n[metric]) for o, n in zip(off, on)
                 if o.get(metric, 0) > 0]
        if not gains:
            continue
        ci = bench_stats.interval(gains)
        outcome, reason = bench_stats.verdict(gains, differential)
        rows[metric] = {
            "off_mean": sum(o[metric] for o in off) / len(off),
            "on_mean": sum(n[metric] for n in on) / len(on),
            "gain_mean": ci.mean, "gain_low": ci.low, "gain_high": ci.high, "pairs": ci.n,
            "verdict": outcome, "reason": reason,
        }
    return {"module": name, "workload": workload, "enabled": sorted(enabled),
            "differential": differential, "metrics": rows}


def markdown(results: list[dict], env: dict) -> str:
    lines = [
        f"Commit `{env['git_sha'][:12]}`{' (dirty tree)' if env['git_dirty'] else ''}, {env['utc']}",
        f"Machine: {env['cpu']} ({env['cpu_threads']} threads), {env['memory_gib']} GiB, {env['os']}",
        f"JVM: {env['java']}; {env['jvm_flags']}",
        "",
        "| Module | Workload | Metric | Off | On | Gain (95% CI) | Pairs | Differential | Verdict |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for result in results:
        for metric, row in result["metrics"].items():
            lines.append(
                f"| `{result['module']}` | {result['workload']} | {metric} | {row['off_mean']:.4g} | {row['on_mean']:.4g} "
                f"| {row['gain_mean']:+.2f}% ({row['gain_low']:+.2f}..{row['gain_high']:+.2f}) | {row['pairs']} "
                f"| {result['differential']} | {row['verdict']} |")
    return "\n".join(lines) + "\n"


def estimate_minutes(runs: int, args: argparse.Namespace) -> float:
    # About 60 s for Gradle, server start and world load, plus warmup and measured ticks at 20 TPS.
    return runs * (60 + (args.warmup + args.ticks) / 20) / 60


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pairs", type=int, default=6)
    parser.add_argument("--modules", help="comma-separated module keys (default: every optimization)")
    parser.add_argument("--profile", choices=["default"], help="measure the shipped defaults instead of single modules")
    parser.add_argument("--warmup", type=int, default=600, help="warmup ticks (default 600)")
    parser.add_argument("--ticks", type=int, default=1200, help="measured ticks (default 1200)")
    parser.add_argument("--run-timeout", type=int, default=900, help="seconds before one server run is abandoned")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--verbose", action="store_true", help="show Gradle output")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        self_test()
        return 0

    plans: list[tuple[str, str, set[str]]] = []
    if args.profile == "default":
        for workload in ALL_WORKLOADS:
            plans.append(("default_profile", workload, set(DEFAULT_ON)))
    else:
        chosen = args.modules.split(",") if args.modules else [m for m in SIM_MODULES if m != "server_metrics"]
        for module in chosen:
            if module not in MODULE_WORKLOAD:
                parser.error(f"unknown or non-optimization module: {module}")
            plans.append((module, MODULE_WORKLOAD[module], with_dependencies(module)))

    total_runs = len(plans) * args.pairs * 2
    print(f"{len(plans)} measurement(s) x {args.pairs} pairs x 2 sides = {total_runs} server runs, "
          f"about {estimate_minutes(total_runs, args) / 60:.1f} hours. Do not use the machine meanwhile.")
    if args.pairs < bench_stats.MIN_PAIRS:
        print(f"WARNING: fewer than {bench_stats.MIN_PAIRS} pairs cannot produce a verdict.", file=sys.stderr)
    if args.dry_run:
        for name, workload, enabled in plans:
            print(f"  {name}: workload {workload}, on = {sorted(enabled)}")
        return 0

    env = environment()
    if env["git_dirty"]:
        print("WARNING: the working tree has uncommitted changes; the results will not match a commit.", file=sys.stderr)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = ROOT / "bench-results" / f"{env['git_sha'][:8]}-{stamp}"
    out.mkdir(parents=True)
    (out / "environment.json").write_text(json.dumps(env, indent=2) + "\n")

    if OUT_DIR.exists():
        # Keep the differential verdict from scripts/scenario-diff.sh, drop stale benchmark files.
        for stale in OUT_DIR.glob("*.bench.*"):
            stale.unlink()
    results = [measure(name, workload, enabled, args, out) for name, workload, enabled in plans]
    (out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    (out / "results.md").write_text(markdown(results, env))
    print(f"\nResults: {out / 'results.md'}\nPaste the table and the environment lines into BENCHMARKS.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
