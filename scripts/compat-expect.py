#!/usr/bin/env python3
"""Check the module states Ultima logged at startup against what the loaded mods imply.

usage: compat-expect.py [--client] LOG MOD...

MOD is a Modrinth slug of a mod that was present in the run (lithium, sodium, iris, modmenu).
With --client the run was a client run in which every client render module was requested on, and
the expectations for the render modules are checked as well.
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

# Client render modules that the renderer family (Sodium, Iris, Canvas) switches off.
RENDER_MODULES = [
    "retained_terrain", "java_mesher", "mesher_fast_path", "render_snapshot", "section_task_queue", "rgss_endpoint",
]
RENDERER_FAMILY = ("sodium", "iris", "canvas")
IRIS_FSR_REASON = "no_safe_post_iris_integration_point"


def main(argv: list[str]) -> int:
    client = "--client" in argv
    argv = [a for a in argv if a != "--client"]
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
        elif not enabled and not client:
            problems.append(f"{key}: expected enabled=true with mods {mods}, got reason={reason}")

    if client:
        renderer_present = any(mod in RENDERER_FAMILY for mod in mods)
        for key in RENDER_MODULES:
            requested, enabled, reason = states.get(key, (False, False, "missing"))
            if not requested:
                problems.append(f"{key}: the client test was meant to request it, but requested=false")
            elif renderer_present and (enabled or reason != "incompatible_mod"):
                problems.append(f"{key}: expected enabled=false reason=incompatible_mod with {mods}, got enabled={enabled} reason={reason}")
            elif not renderer_present and reason in ("incompatible_mod", "missing"):
                problems.append(f"{key}: nothing overlaps it with {mods}, yet reason={reason}")
        if "iris" in mods:
            requested, enabled, reason = states.get("fsr_upscaling", (False, False, "missing"))
            if enabled or reason != IRIS_FSR_REASON:
                problems.append(f"fsr_upscaling: expected enabled=false reason={IRIS_FSR_REASON} with Iris, got enabled={enabled} reason={reason}")

    if problems:
        print("COMPATIBILITY CHECK FAILED", file=sys.stderr)
        for problem in problems:
            print("  " + problem, file=sys.stderr)
        return 1
    print(f"Compatibility check passed for {mods}: {len(states)} module states match the expectation.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
