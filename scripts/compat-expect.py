#!/usr/bin/env python3
"""Check the module states Ultima logged at startup against what the loaded mods imply.

usage: compat-expect.py LOG MOD...

MOD is a Modrinth slug of a mod that was present in the run (lithium, sodium, iris, modmenu).
Ultima logs one line per module: "Ultima module <key> requested=<bool> enabled=<bool> reason=<code>".
The check fails when a module that a present mod should switch off is still enabled (or is off for
another reason), when a module is switched off although nothing should have done it, or when the
log holds a Mixin failure.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

LINE = re.compile(r"Ultima module (\w+) requested=(true|false) enabled=(true|false) reason=(\w+)")
FAILURE = re.compile(r"Mixin apply for mod ultima failed|Mixin transformation of .* failed|Failed to start the minecraft server")

LITHIUM_FAMILY_DEFAULT_ON = [
    "entity_section_lookup", "block_collision_shape", "collision_shell_skip",
    "supporting_block_shape_skip", "full_cube_move", "cursor_step",
]
# Modules a mod switches off when it is loaded on the given side, by the reason Ultima reports.
SERVER_EXPECT = {"lithium": {key: "incompatible_mod" for key in LITHIUM_FAMILY_DEFAULT_ON}}
# The default-on modules that must stay enabled when nothing overlaps them.
SERVER_DEFAULT_ON = LITHIUM_FAMILY_DEFAULT_ON


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    text = Path(argv[0]).read_text(encoding="utf-8", errors="replace")
    mods = argv[1:]
    states = {m.group(1): (m.group(2) == "true", m.group(3) == "true", m.group(4)) for m in LINE.finditer(text)}
    problems: list[str] = []
    if not states:
        problems.append("the log has no 'Ultima module ...' lines")
    for match in FAILURE.finditer(text):
        problems.append(f"log holds a failure: {match.group(0)}")

    expected: dict[str, str] = {}
    for mod in mods:
        expected.update(SERVER_EXPECT.get(mod, {}))
    for key in SERVER_DEFAULT_ON:
        requested, enabled, reason = states.get(key, (False, False, "missing"))
        if key in expected:
            if enabled or reason != expected[key]:
                problems.append(f"{key}: expected enabled=false reason={expected[key]}, got enabled={enabled} reason={reason}")
        elif not enabled:
            problems.append(f"{key}: expected enabled=true with mods {mods}, got reason={reason}")

    if problems:
        print("COMPATIBILITY CHECK FAILED", file=sys.stderr)
        for problem in problems:
            print("  " + problem, file=sys.stderr)
        return 1
    print(f"Compatibility check passed for {mods}: {len(states)} module states match the expectation.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
