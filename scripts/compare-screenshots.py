#!/usr/bin/env python3
"""Compare the screenshots of two client-test runs pixel by pixel.

usage: compare-screenshots.py DIR_A DIR_B [--control DIR_C] [--ignore PATTERN ...]
           [--max-changed-fraction F] [--channel-tolerance N] [--max-unstable F] [--dilate N]

Every PNG that exists in both directories is compared. A pixel counts as changed when any colour
channel differs by more than --channel-tolerance (default 0). The run fails when the fraction of
changed pixels in any image exceeds --max-changed-fraction (default 0: pixel-identical), or when
an image is missing on either side.

--control DIR_C is a second run configured exactly like DIR_A. The pixels that differ between A and
C are the environment's own noise (animation, entity movement, chunk-load timing under software
rendering); they are dilated by --dilate pixels and excluded from the A-vs-B comparison. The run
fails when more than --max-unstable of an image is noise, because an image that is mostly noise
proves nothing. --ignore drops the images matching a glob pattern from the check (they are still listed).

Needs Pillow and numpy (pip install pillow numpy).
"""
from __future__ import annotations

import argparse
import fnmatch
import sys
from pathlib import Path

MAP_COLUMNS = 64
MAP_ROWS = 18
MAP_GLYPHS = " .:*#"


def load(path: Path):
    import numpy as np  # noqa: PLC0415 - optional dependency, imported when used
    from PIL import Image  # noqa: PLC0415

    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.int16)


def changed(a, b, tolerance: int):
    import numpy as np  # noqa: PLC0415

    return np.abs(a - b).max(axis=2) > tolerance


def dilate(mask, radius: int):
    import numpy as np  # noqa: PLC0415

    if radius <= 0:
        return mask
    height, width = mask.shape
    padded = np.pad(mask, radius)
    result = np.zeros_like(mask)
    for dy in range(2 * radius + 1):
        for dx in range(2 * radius + 1):
            result |= padded[dy:dy + height, dx:dx + width]
    return result


def hotspots(mask, a, b, tile: int = 32, top: int = 8) -> list[str]:
    """The tiles with the most changed pixels, each with the colours of one changed pixel in both images."""
    import numpy as np  # noqa: PLC0415

    height, width = mask.shape
    found = []
    for tile_y in range(0, height, tile):
        for tile_x in range(0, width, tile):
            block = mask[tile_y:tile_y + tile, tile_x:tile_x + tile]
            count = int(block.sum())
            if count:
                ys, xs = np.nonzero(block)
                y, x = tile_y + int(ys[0]), tile_x + int(xs[0])
                found.append((count, tile_x, tile_y, tuple(int(v) for v in a[y, x]), tuple(int(v) for v in b[y, x])))
    found.sort(reverse=True)
    return [f"      tile x={x:<4} y={y:<4} {count:>4} px   {before} -> {after}" for count, x, y, before, after in found[:top]]


def ascii_map(mask) -> str:
    """A coarse picture of where the changed pixels are, one glyph per block."""
    height, width = mask.shape
    lines = []
    for row in range(MAP_ROWS):
        top, bottom = row * height // MAP_ROWS, (row + 1) * height // MAP_ROWS
        line = []
        for column in range(MAP_COLUMNS):
            left, right = column * width // MAP_COLUMNS, (column + 1) * width // MAP_COLUMNS
            block = mask[top:bottom, left:right]
            fraction = float(block.mean()) if block.size else 0.0
            line.append(MAP_GLYPHS[0] if fraction == 0 else MAP_GLYPHS[min(len(MAP_GLYPHS) - 1, 1 + int(fraction * (len(MAP_GLYPHS) - 1)))])
        lines.append("    |" + "".join(line) + "|")
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dir_a", type=Path)
    parser.add_argument("dir_b", type=Path)
    parser.add_argument("--control", type=Path)
    parser.add_argument("--ignore", nargs="*", default=[])
    parser.add_argument("--max-changed-fraction", type=float, default=0.0)
    parser.add_argument("--channel-tolerance", type=int, default=0)
    parser.add_argument("--max-unstable", type=float, default=0.05)
    parser.add_argument("--dilate", type=int, default=2)
    args = parser.parse_args(argv)

    names_a = {p.name for p in args.dir_a.glob("*.png")}
    names_b = {p.name for p in args.dir_b.glob("*.png")}
    names_c = {p.name for p in args.control.glob("*.png")} if args.control else names_a
    failures: list[str] = []
    for missing in sorted((names_a ^ names_b) | (names_a ^ names_c)):
        failures.append(f"{missing} is not present in every run")
    rows: list[str] = []
    for name in sorted(names_a & names_b & names_c):
        a, b = load(args.dir_a / name), load(args.dir_b / name)
        if a.shape != b.shape:
            failures.append(f"{name}: image size differs ({a.shape[1]}x{a.shape[0]} vs {b.shape[1]}x{b.shape[0]})")
            continue
        raw = changed(a, b, args.channel_tolerance)
        worst = int(abs(a - b).max())
        noise_fraction = 0.0
        effective = raw
        noise = None
        if args.control:
            c = load(args.control / name)
            if c.shape != a.shape:
                failures.append(f"{name}: control image size differs")
                continue
            noise = dilate(changed(a, c, args.channel_tolerance), args.dilate)
            noise_fraction = float(noise.mean())
            effective = raw & ~noise
        fraction = float(effective.mean())
        ignored = any(fnmatch.fnmatch(name, pattern) for pattern in args.ignore)
        problems = []
        if not ignored and fraction > args.max_changed_fraction:
            problems.append(f"{fraction * 100:.4f}% of the pixels changed outside the noise (limit {args.max_changed_fraction * 100:.4f}%)")
        if not ignored and args.control and noise_fraction > args.max_unstable:
            problems.append(f"{noise_fraction * 100:.2f}% of the image is noise between two identical runs (limit {args.max_unstable * 100:.2f}%)")
        verdict = "ignored" if ignored else ("FAIL" if problems else "ok")
        rows.append(f"{name:<32} {raw.mean() * 100:>8.4f}% {noise_fraction * 100:>8.4f}% {fraction * 100:>15.4f}% {worst:>6}  {verdict}")
        failures.extend(f"{name}: {problem}" for problem in problems)
        if fraction > 0 and not ignored:
            print(f"{name}: changed outside the noise:" if args.control else f"{name}: changed:")
            print(ascii_map(effective))
            print("\n".join(hotspots(effective, a, b)))
    if not (names_a & names_b):
        failures.append("no screenshots to compare")
    print(f"\n{'image':<32} {'A vs B':>9} {'noise':>9} {'B outside noise':>16} {'worst':>6}")
    for row in rows:
        print(row)
    if failures:
        print("\nSCREENSHOT COMPARISON FAILED")
        for failure in failures:
            print("  " + failure)
        return 1
    print("\nScreenshots match.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
