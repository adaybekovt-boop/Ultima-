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


def experimental_on(data: dict) -> bool:
    for module in data.get("modules", []):
        if module.get("key") in {
            "entity_section_lookup",
            "block_collision_shape",
            "collision_shell_skip",
        } and module.get("enabled"):
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
    print(format_report(report), end="")
    print("self-test passed")


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
