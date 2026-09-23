#!/usr/bin/env python3
"""Fail a server A/B when world state differs between the off and on logs.

Compared markers are the datapack load, the post-load entity and player scores,
the level seed, and the trailing kill count. A missing marker fails closed.
"""
from __future__ import annotations

import glob
import re
import sys
from pathlib import Path


def last_score(text: str, name: str) -> str | None:
    matches = re.findall(re.escape(name) + r" has (\d+)", text)
    return matches[-1] if matches else None


def seed_of(text: str) -> str | None:
    matches = re.findall(r"Seed:\s*\[?([^\s\]]+)", text)
    return matches[-1] if matches else None


def main(prefix: str) -> int:
    logs = sorted(glob.glob(f"/tmp/ultima-bench-{prefix}_pair*_*.log"))
    pair_re = re.compile(r"ultima-bench-(?P<pre>.*_pair)(?P<n>\d+)_(?P<side>off|on)\.log$")
    grouped: dict[tuple[str, int], dict[str, str]] = {}
    for path in logs:
        match = pair_re.search(path)
        if match is None:
            continue
        grouped.setdefault((match.group("pre"), int(match.group("n"))), {})[match.group("side")] = Path(
            path
        ).read_text(encoding="utf-8", errors="replace")
    if not grouped:
        print("no server bench logs to compare", file=sys.stderr)
        return 2
    failed = False
    for key, sides in sorted(grouped.items()):
        if "off" not in sides or "on" not in sides:
            print(f"pair {key[1]} is missing off or on", file=sys.stderr)
            failed = True
            continue
        checks = (
            ("entities", last_score(sides["off"], "#ultima_entities"), last_score(sides["on"], "#ultima_entities")),
            ("players", last_score(sides["off"], "#ultima_players"), last_score(sides["on"], "#ultima_players")),
            ("seed", seed_of(sides["off"]), seed_of(sides["on"])),
        )
        for label, off, on in checks:
            if off is None or on is None or off != on:
                print(f"pair {key[1]} {label} mismatch off={off} on={on}", file=sys.stderr)
                failed = True
        off_kills = re.findall(r"Killed (\d+) entities", sides["off"])
        on_kills = re.findall(r"Killed (\d+) entities", sides["on"])
        if not off_kills or not on_kills or off_kills[-1] != on_kills[-1]:
            print(
                f"pair {key[1]} killed-entity mismatch off={off_kills[-1:] or ['missing']} on={on_kills[-1:] or ['missing']}",
                file=sys.stderr,
            )
            failed = True
    if failed:
        return 1
    print(f"server bench world state matches across {len(grouped)} pair(s)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: compare-server-bench-state.py <prefix>", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1]))
