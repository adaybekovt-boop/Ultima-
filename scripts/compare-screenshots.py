#!/usr/bin/env python3
"""Compare the screenshots of two client-test runs pixel by pixel.

usage: compare-screenshots.py DIR_A DIR_B [--max-changed-fraction F] [--channel-tolerance N]

Every PNG that exists in both directories is compared. A pixel counts as changed when any colour
channel differs by more than --channel-tolerance (default 0). The run fails when the fraction of
changed pixels in any image exceeds --max-changed-fraction (default 0: pixel-identical), or when
an image is missing on either side. Needs Pillow (pip install pillow).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path


def compare(path_a: Path, path_b: Path, tolerance: int) -> tuple[float, int, tuple[int, int]]:
    from PIL import Image, ImageChops  # noqa: PLC0415 - optional dependency, imported when used

    with Image.open(path_a) as a, Image.open(path_b) as b:
        if a.size != b.size:
            return 1.0, 255, a.size
        diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
        width, height = diff.size
        pixels = diff.load()
        changed = 0
        worst = 0
        for y in range(height):
            for x in range(width):
                delta = max(pixels[x, y])
                if delta > tolerance:
                    changed += 1
                if delta > worst:
                    worst = delta
        return changed / (width * height), worst, (width, height)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dir_a", type=Path)
    parser.add_argument("dir_b", type=Path)
    parser.add_argument("--max-changed-fraction", type=float, default=0.0)
    parser.add_argument("--channel-tolerance", type=int, default=0)
    args = parser.parse_args(argv)

    names_a = {p.name for p in args.dir_a.glob("*.png")}
    names_b = {p.name for p in args.dir_b.glob("*.png")}
    failures: list[str] = []
    for missing in sorted(names_a ^ names_b):
        failures.append(f"{missing} exists in only one of the two runs")
    print(f"{'image':<32} {'changed':>10} {'worst channel':>14}")
    for name in sorted(names_a & names_b):
        fraction, worst, size = compare(args.dir_a / name, args.dir_b / name, args.channel_tolerance)
        print(f"{name:<32} {fraction * 100:>9.4f}% {worst:>14}")
        if fraction > args.max_changed_fraction:
            failures.append(f"{name}: {fraction * 100:.4f}% of the pixels changed (limit {args.max_changed_fraction * 100:.4f}%)")
    if not (names_a & names_b):
        failures.append("no screenshots to compare")
    if failures:
        print("\nSCREENSHOT COMPARISON FAILED", file=sys.stderr)
        for failure in failures:
            print("  " + failure, file=sys.stderr)
        return 1
    print("\nScreenshots match.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
