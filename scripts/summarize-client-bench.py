#!/usr/bin/env python3
"""Summarize Ultima client A/B JSON runs.

Primary comparison is disabled versus default. This script never treats a mean
inside the noise, a CI that includes zero, or n<6 as a proven FPS gain.
Outliers, especially 0.1% low, are printed per pair and are not dropped from
the mean.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

METRICS = (
    ("averageFps", "Average FPS", True),
    ("medianFps", "Median FPS", True),
    ("onePercentLowFps", "1% low FPS", True),
    ("pointOnePercentLowFps", "0.1% low FPS", True),
    ("averageFrameTimeMs", "Average frame time ms", False),
    ("p95FrameTimeMs", "P95 frame time ms", False),
    ("p99FrameTimeMs", "P99 frame time ms", False),
)

T_CRIT_95 = {
    1: 12.706,
    2: 4.303,
    3: 3.182,
    4: 2.776,
    5: 2.571,
    6: 2.447,
    7: 2.365,
    8: 2.306,
    9: 2.262,
    10: 2.228,
    14: 2.145,
    19: 2.093,
    29: 2.045,
}


def t_crit(df: int) -> float:
    if df <= 0:
        return float("inf")
    if df in T_CRIT_95:
        return T_CRIT_95[df]
    for key in sorted(T_CRIT_95):
        if df <= key:
            return T_CRIT_95[key]
    return 1.96


def mean(values: list[float]) -> float:
    return sum(values) / len(values)


def sample_sd(values: list[float]) -> float:
    if len(values) < 2:
        return 0.0
    m = mean(values)
    return math.sqrt(sum((value - m) ** 2 for value in values) / (len(values) - 1))


def pct_delta(off: float, on: float, higher_is_better: bool) -> float:
    if off == 0:
        return float("nan")
    return 100.0 * (on - off) / off


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


PAIR_RE = re.compile(r"(?P<prefix>.*pair)(?P<pair>\d+)_(?P<side>off|on)\.json$", re.I)


def pair_key(path: Path) -> tuple[str, str] | None:
    match = PAIR_RE.search(path.name)
    if not match:
        return None
    return match.group("pair"), match.group("side").lower()


INSTRUMENTATION_KEYS = frozenset({"client_benchmark", "terrain_metrics"})
SHIPPED_DEFAULT_KEYS_MUST_INCLUDE = (
    "entity_section_lookup",
    "block_collision_shape",
    "collision_shell_skip",
)


def module_class(module: dict) -> str:
    explicit = module.get("moduleClass")
    if explicit:
        return explicit
    key = module.get("key")
    if key in INSTRUMENTATION_KEYS:
        return "instrumentation"
    if module.get("enabledByDefault"):
        return "shipped_default"
    return "opt_in_experiment"


def experimental_on(data: dict) -> bool:
    """True when the ON side enabled an opt-in experiment, not a shipped default."""
    for module in data.get("modules", []):
        if module.get("enabled") and module_class(module) == "opt_in_experiment":
            return True
    role = data.get("abProtocol", {}).get("requestedRole")
    return role == "enabled"


def summarize_pairs(pairs: list[tuple[int, dict, dict]]) -> dict:
    report: dict = {"pairs": [], "metrics": {}, "warnings": []}
    if any(experimental_on(on) for _, _, on in pairs):
        report["warnings"].append(
            "ON side enabled experimental opt-in modules; this is not the primary release A/B."
        )
    if len(pairs) < 6:
        report["warnings"].append(
            f"Only {len(pairs)} balanced pair(s); primary protocol requires at least 6."
        )

    for pair, off, on in pairs:
        entry = {
            "pair": pair,
            "off": {metric: off.get(metric) for metric, _, _ in METRICS},
            "on": {metric: on.get(metric) for metric, _, _ in METRICS},
            "counters": {
                "chunkMatrixCopiesAvoided": on.get("chunkMatrixCopiesAvoided"),
                "chunkLayerArraysAvoided": on.get("chunkLayerArraysAvoided"),
                "sectionDirtyWritesAvoided": on.get("sectionDirtyWritesAvoided"),
            },
        }
        deltas = {}
        for metric, _, higher in METRICS:
            if off.get(metric) is None or on.get(metric) is None:
                continue
            deltas[metric] = pct_delta(off[metric], on[metric], higher)
        entry["deltaPct"] = deltas
        if abs(deltas.get("pointOnePercentLowFps", 0.0)) >= 10.0:
            entry["outlier"] = "0.1% low |delta| >= 10 percentage points; do not hide by averaging"
        off_terrain = off.get("terrainMetrics") or {}
        on_terrain = on.get("terrainMetrics") or {}
        if off_terrain or on_terrain:
            entry["terrain"] = {
                "offTotalCpuNsAvg": off_terrain.get("terrainTotalCpuNsAvg", off_terrain.get("prepareNsAvg")),
                "onTotalCpuNsAvg": on_terrain.get("terrainTotalCpuNsAvg", on_terrain.get("prepareNsAvg")),
                "offOpaqueSubmitCpuNsAvg": off_terrain.get("terrainOpaqueSubmitCpuNsAvg"),
                "onOpaqueSubmitCpuNsAvg": on_terrain.get("terrainOpaqueSubmitCpuNsAvg"),
                "offTranslucentSubmitCpuNsAvg": off_terrain.get("terrainTranslucentSubmitCpuNsAvg"),
                "onTranslucentSubmitCpuNsAvg": on_terrain.get("terrainTranslucentSubmitCpuNsAvg"),
                "onWriteToBufferCallsAvg": on_terrain.get("writeToBufferCallsAvg"),
                "onWriteToBufferBytesAvg": on_terrain.get("writeToBufferBytesAvg"),
                "onGpuTerrainNsAvg": on_terrain.get("gpuTerrainNsAvg"),
                "onUltimaIssuedFenceWaitNsAvg": on_terrain.get("ultimaIssuedFenceWaitNsAvg", on_terrain.get("fenceWaitNsAvg")),
                "syncCountersScope": on_terrain.get("syncCountersScope", "ultima_issued_only"),
                "commandPopulationGrewWhileLiveBounded": on_terrain.get("commandPopulationGrewWhileLiveBounded"),
                "firstSampleTotalCommands": on_terrain.get("firstSampleTotalCommands"),
                "lastSampleTotalCommands": on_terrain.get("lastSampleTotalCommands"),
                "firstSampleLiveCommands": on_terrain.get("firstSampleLiveCommands"),
                "lastSampleLiveCommands": on_terrain.get("lastSampleLiveCommands"),
            }
        report["pairs"].append(entry)

    for metric, label, higher in METRICS:
        off_values = [off[metric] for _, off, _ in pairs if off.get(metric) is not None]
        on_values = [on[metric] for _, _, on in pairs if on.get(metric) is not None]
        deltas = [
            pct_delta(off[metric], on[metric], higher)
            for _, off, on in pairs
            if off.get(metric) is not None and on.get(metric) is not None
        ]
        if not deltas:
            continue
        n = len(deltas)
        m = mean(deltas)
        sd = sample_sd(deltas)
        se = sd / math.sqrt(n) if n else float("nan")
        crit = t_crit(n - 1)
        lo = m - crit * se
        hi = m + crit * se
        mean_off = mean(off_values)
        mean_on = mean(on_values)
        mean_of_means = pct_delta(mean_off, mean_on, higher)
        includes_zero = lo <= 0.0 <= hi
        verdict = "INCONCLUSIVE"
        if n >= 6 and not includes_zero:
            improved = m > 0 if higher else m < 0
            verdict = "DIRECTIONAL" if improved else "DIRECTIONAL_REGRESSION"
        report["metrics"][metric] = {
            "label": label,
            "higherIsBetter": higher,
            "n": n,
            "meanOff": mean_off,
            "meanOn": mean_on,
            "meanOfMeansPct": mean_of_means,
            "meanPairedDeltaPct": m,
            "sdPairedDeltaPct": sd,
            "ci95PairedDeltaPct": [lo, hi],
            "ciIncludesZero": includes_zero,
            "verdict": verdict,
        }
    return report


def format_report(report: dict) -> str:
    lines = ["Ultima client A/B summary", "Primary comparison: disabled vs default", ""]
    for warning in report["warnings"]:
        lines.append(f"WARNING: {warning}")
    if report["warnings"]:
        lines.append("")
    for pair in report["pairs"]:
        lines.append(f"PAIR {pair['pair']}")
        for metric, label, _ in METRICS:
            off = pair["off"].get(metric)
            on = pair["on"].get(metric)
            delta = pair["deltaPct"].get(metric)
            if off is None or on is None or delta is None:
                continue
            lines.append(f"  {label}: {off:.4f} -> {on:.4f} ({delta:+.2f}%)")
        if pair.get("outlier"):
            lines.append(f"  OUTLIER: {pair['outlier']}")
        terrain = pair.get("terrain")
        if terrain:
            lines.append(
                "  Terrain CPU total (A/B comparable): "
                f"{_fmt(terrain.get('offTotalCpuNsAvg'))} ns -> {_fmt(terrain.get('onTotalCpuNsAvg'))} ns"
            )
            lines.append(
                "  Opaque submit CPU: "
                f"{_fmt(terrain.get('offOpaqueSubmitCpuNsAvg'))} ns -> {_fmt(terrain.get('onOpaqueSubmitCpuNsAvg'))} ns"
            )
            lines.append(
                "  WriteToBuffer (Ultima-issued, not driver stall proof): "
                f"calls={_fmt(terrain.get('onWriteToBufferCallsAvg'))} "
                f"bytes={_fmt(terrain.get('onWriteToBufferBytesAvg'))} "
                f"gpuTerrainNs={_fmt(terrain.get('onGpuTerrainNsAvg'))} "
                f"ultimaIssuedFenceWaitNs={_fmt(terrain.get('onUltimaIssuedFenceWaitNsAvg'))}"
            )
            if terrain.get("onUltimaIssuedFenceWaitNsAvg") == 0:
                lines.append(
                    "  NOTE: ultimaIssuedFenceWaitNs=0 means Ultima issued no fence wait; "
                    "it does not prove the driver/GPU never synchronized."
                )
            if terrain.get("commandPopulationGrewWhileLiveBounded"):
                lines.append(
                    "  COMMAND GROWTH: total records grew while live draws stayed relatively bounded "
                    f"({terrain.get('firstSampleTotalCommands')} -> {terrain.get('lastSampleTotalCommands')} total, "
                    f"live {terrain.get('firstSampleLiveCommands')} -> {terrain.get('lastSampleLiveCommands')}). "
                    "Compaction is not applied; this is observability only."
                )
        lines.append("")
    for metric, _, _ in METRICS:
        stats = report["metrics"].get(metric)
        if not stats:
            continue
        lines.append(
            f"{stats['label']}: {stats['meanOff']:.4f} -> {stats['meanOn']:.4f} "
            f"({stats['meanOfMeansPct']:+.2f}% mean-of-means, "
            f"{stats['meanPairedDeltaPct']:+.2f}% mean paired delta, "
            f"SD {stats['sdPairedDeltaPct']:.2f} pp, "
            f"95% CI [{stats['ci95PairedDeltaPct'][0]:+.2f}, {stats['ci95PairedDeltaPct'][1]:+.2f}], "
            f"{stats['verdict']})"
        )
    fps = report["metrics"].get("averageFps")
    one = report["metrics"].get("onePercentLowFps")
    lines.append("")
    if fps:
        lines.append(
            f"MEASURED FPS GAIN: {fps['meanOfMeansPct']:+.2f}% ({fps['verdict']}; NO RELIABLE GAIN)"
            if fps["verdict"] == "INCONCLUSIVE"
            else f"MEASURED FPS GAIN: {fps['meanOfMeansPct']:+.2f}% ({fps['verdict']})"
        )
    if one:
        lines.append(
            f"MEASURED 1% LOW GAIN: {one['meanOfMeansPct']:+.2f}% ({one['verdict']})"
        )
    lines.append("Do not claim 2x FPS or a noticeable FPS gain from these data.")
    return "\n".join(lines) + "\n"


def _fmt(value) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


RTX3090_FIXTURES = [
    (1, {
        "averageFps": 566.15, "medianFps": 598.01, "onePercentLowFps": 256.48,
        "pointOnePercentLowFps": 137.50, "averageFrameTimeMs": 1.7663,
        "p95FrameTimeMs": 2.6121, "p99FrameTimeMs": 3.0647,
    }, {
        "averageFps": 552.20, "medianFps": 601.94, "onePercentLowFps": 254.05,
        "pointOnePercentLowFps": 113.26, "averageFrameTimeMs": 1.8109,
        "p95FrameTimeMs": 2.6956, "p99FrameTimeMs": 3.0699,
        "chunkMatrixCopiesAvoided": 7356000, "chunkLayerArraysAvoided": 7368000,
        "sectionDirtyWritesAvoided": 29844,
    }),
    (2, {
        "averageFps": 554.84, "medianFps": 593.86, "onePercentLowFps": 270.99,
        "pointOnePercentLowFps": 129.65, "averageFrameTimeMs": 1.8023,
        "p95FrameTimeMs": 2.6163, "p99FrameTimeMs": 2.9436,
    }, {
        "averageFps": 582.14, "medianFps": 631.67, "onePercentLowFps": 271.10,
        "pointOnePercentLowFps": 127.87, "averageFrameTimeMs": 1.7178,
        "p95FrameTimeMs": 2.5616, "p99FrameTimeMs": 2.8688,
        "chunkMatrixCopiesAvoided": 7368000, "chunkLayerArraysAvoided": 7380000,
        "sectionDirtyWritesAvoided": 29024,
    }),
    (3, {
        "averageFps": 592.26, "medianFps": 638.28, "onePercentLowFps": 275.46,
        "pointOnePercentLowFps": 143.97, "averageFrameTimeMs": 1.6884,
        "p95FrameTimeMs": 2.4729, "p99FrameTimeMs": 2.8201,
    }, {
        "averageFps": 574.09, "medianFps": 619.85, "onePercentLowFps": 283.29,
        "pointOnePercentLowFps": 143.93, "averageFrameTimeMs": 1.7419,
        "p95FrameTimeMs": 2.5751, "p99FrameTimeMs": 2.8948,
        "chunkMatrixCopiesAvoided": 7332000, "chunkLayerArraysAvoided": 7344000,
        "sectionDirtyWritesAvoided": 30005,
    }),
]


def self_test() -> None:
    report = summarize_pairs(RTX3090_FIXTURES)
    fps = report["metrics"]["averageFps"]
    one = report["metrics"]["onePercentLowFps"]
    tail = report["metrics"]["pointOnePercentLowFps"]
    if abs(fps["meanOfMeansPct"] - (-0.28)) > 0.05:
        raise SystemExit(f"self-test FPS mean-of-means expected ~-0.28, got {fps['meanOfMeansPct']}")
    if abs(fps["meanPairedDeltaPct"] - (-0.20)) > 0.05:
        raise SystemExit(f"self-test FPS paired delta expected ~-0.20, got {fps['meanPairedDeltaPct']}")
    if abs(fps["sdPairedDeltaPct"] - 4.45) > 0.05:
        raise SystemExit(f"self-test FPS SD expected ~4.45, got {fps['sdPairedDeltaPct']}")
    if abs(one["meanOfMeansPct"] - 0.68) > 0.05:
        raise SystemExit(f"self-test 1% low mean-of-means expected ~0.68, got {one['meanOfMeansPct']}")
    if abs(tail["meanOfMeansPct"] - (-6.34)) > 0.15:
        raise SystemExit(f"self-test 0.1% low mean-of-means expected ~-6.34, got {tail['meanOfMeansPct']}")
    if fps["verdict"] != "INCONCLUSIVE" or one["verdict"] != "INCONCLUSIVE":
        raise SystemExit("self-test must remain INCONCLUSIVE")
    if "outlier" not in report["pairs"][0]:
        raise SystemExit("pair 1 0.1% low must be flagged as an outlier")
    if not any("at least 6" in warning for warning in report["warnings"]):
        raise SystemExit("n=3 must warn that 6 pairs are required")
    test_module_classification()
    print(format_report(report), end="")
    print("self-test passed")


def parse_java_module_defaults() -> dict[str, bool]:
    text = Path("src/main/java/dev/ultima/config/UltimaModules.java").read_text(encoding="utf-8")
    defaults: dict[str, bool] = {}
    for match in re.finditer(
            r'(?:new Module|Module\.client)\("([a-z_]+)", (true|false),',
            text):
        defaults[match.group(1)] = match.group(2) == "true"
    if not defaults:
        raise SystemExit("failed to parse UltimaModules.java defaults")
    return defaults


def test_module_classification() -> None:
    defaults = parse_java_module_defaults()
    for key in SHIPPED_DEFAULT_KEYS_MUST_INCLUDE:
        if key not in defaults or defaults[key] is not True:
            raise SystemExit(f"{key} must remain enabledByDefault=true in UltimaModules.java")
    if defaults.get("retained_terrain") is not False:
        raise SystemExit("retained_terrain must remain opt-in")
    if defaults.get("client_benchmark") is not False:
        raise SystemExit("client_benchmark must remain opt-in instrumentation")
    if defaults.get("terrain_metrics") is not True:
        raise SystemExit("terrain_metrics must remain default-on instrumentation")

    default_on = {
        "modules": [
            {"key": key, "enabled": True, "enabledByDefault": True, "moduleClass": "shipped_default"}
            for key in SHIPPED_DEFAULT_KEYS_MUST_INCLUDE
        ] + [
            {"key": "client_benchmark", "enabled": True, "enabledByDefault": False, "moduleClass": "instrumentation"},
            {"key": "terrain_metrics", "enabled": True, "enabledByDefault": True, "moduleClass": "instrumentation"},
            {"key": "retained_terrain", "enabled": False, "enabledByDefault": False, "moduleClass": "opt_in_experiment"},
        ],
        "abProtocol": {"requestedRole": "default"},
    }
    if experimental_on(default_on):
        raise SystemExit("disabled-vs-default A/B must not warn because shipped defaults are enabled")

    retained_on = {
        "modules": [
            {"key": "retained_terrain", "enabled": True, "enabledByDefault": False, "moduleClass": "opt_in_experiment"},
            {"key": "client_benchmark", "enabled": True, "enabledByDefault": False, "moduleClass": "instrumentation"},
        ],
        "abProtocol": {"requestedRole": "default"},
    }
    if not experimental_on(retained_on):
        raise SystemExit("retained_terrain ON must be classified as an experimental opt-in")

    enabled_role = {"modules": [], "abProtocol": {"requestedRole": "enabled"}}
    if not experimental_on(enabled_role):
        raise SystemExit("requestedRole=enabled must remain an experimental warning")

    stale_hardcoded = {
        "modules": [
            {"key": "entity_section_lookup", "enabled": True, "enabledByDefault": True},
            {"key": "block_collision_shape", "enabled": True, "enabledByDefault": True},
            {"key": "collision_shell_skip", "enabled": True, "enabledByDefault": True},
        ]
    }
    if experimental_on(stale_hardcoded):
        raise SystemExit("shipped defaults without moduleClass must not be treated as experimental")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("json_files", nargs="*", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    if not args.json_files:
        parser.error("JSON files are required unless --self-test is set")

    grouped: dict[int, dict[str, dict]] = {}
    unmatched: list[Path] = []
    for path in args.json_files:
        parsed = pair_key(path)
        if parsed is None:
            unmatched.append(path)
            continue
        pair, side = parsed
        grouped.setdefault(int(pair), {})[side] = load_json(path)
    if unmatched:
        print("unrecognized file names (expected *pairN_off.json / *pairN_on.json):", file=sys.stderr)
        for path in unmatched:
            print(f"  {path}", file=sys.stderr)
        return 2
    pairs = []
    for pair in sorted(grouped):
        sides = grouped[pair]
        if "off" not in sides or "on" not in sides:
            print(f"pair {pair} is missing off or on JSON", file=sys.stderr)
            return 2
        pairs.append((pair, sides["off"], sides["on"]))
    print(format_report(summarize_pairs(pairs)), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
