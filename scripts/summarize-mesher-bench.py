#!/usr/bin/env python3
"""Summarize Ultima mesher hardware A/B JSON.

Compares CPU meshing counters only (meshBuildNs, coverage, fail-open).
This script never prints an FPS verdict and must not be quoted as a GPU result.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


REQUIRED_FIELDS = (
    "meshBuildNs",
    "rebuildCount",
    "fastPathBlocks",
    "fallbackBlocks",
    "fastPathCoverageOfNonAir",
    "meshFastPathFailures",
    "meshFastPathCircuitBreakerTrips",
    "fallbackByReason",
    "disclaimer",
)

SCENES = ("mesher_cold_load", "mesher_rebuild_storm", "mesher_chunk_flight")


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def mesher_block(data: dict) -> dict:
    block = data.get("mesherMetrics")
    if not isinstance(block, dict):
        raise SystemExit("JSON is missing mesherMetrics")
    return block


def summarize_pair(off: dict, on: dict) -> str:
    off_m = mesher_block(off)
    on_m = mesher_block(on)
    lines = [
        "Ultima mesher A/B summary",
        "CPU meshing time only, NOT a real FPS/GPU claim, requires hardware A/B before any performance statement",
        "",
    ]
    for field in ("meshBuildNs", "rebuildCount", "fastPathCoverageOfNonAir", "weightedFastPathBlocks", "meshFastPathFailures"):
        lines.append(f"  {field}: {off_m.get(field)} -> {on_m.get(field)}")
    lines.append(f"  off disclaimer: {off_m.get('disclaimer')}")
    return "\n".join(lines) + "\n"


def self_test() -> None:
    sample = {
        "mesherMetrics": {
            "meshBuildNs": 1000,
            "rebuildCount": 10,
            "fastPathBlocks": 80,
            "fallbackBlocks": 20,
            "fastPathCoverageOfNonAir": 0.8,
            "weightedFastPathBlocks": 60,
            "meshFastPathFailures": 0,
            "meshFastPathCircuitBreakerTrips": 0,
            "fallbackByReason": {"TRANSLUCENT_LAYER": 4},
            "disclaimer": "CPU meshing counters only. NOT a real FPS/GPU claim.",
        }
    }
    for field in REQUIRED_FIELDS:
        if field not in sample["mesherMetrics"]:
            raise SystemExit(f"self-test fixture missing {field}")
    text = summarize_pair(sample, sample)
    if "NOT a real FPS/GPU claim" not in text:
        raise SystemExit("summarizer must keep the no-FPS disclaimer")
    if "FPS verdict" in text or "averageFps" in text:
        raise SystemExit("mesher summarizer must not emit an FPS verdict")
    print(text, end="")
    print("mesher summarizer self-test passed")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("json_files", nargs="*", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    if len(args.json_files) != 2:
        parser.error("expected off.json on.json, or --self-test")
    print(summarize_pair(load_json(args.json_files[0]), load_json(args.json_files[1])), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
