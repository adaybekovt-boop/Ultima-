#!/usr/bin/env bash
# Client A/B for one render module on the vanilla renderer: same build, same world, only the
# module differs. Both sides start from "disabled" (every module off), then the ON side turns the
# module (and what it needs) on. client_benchmark and terrain_metrics stay on for both sides, as
# in every client benchmark here.
#
# Usage:
#   bash scripts/bench-client-module.sh <module> [pair-count]
#   SCENE=chunk_flight bash scripts/bench-client-module.sh retained_terrain 6
#
# Scenes (CAMERA_MODE): stationary (default), yaw_sweep, chunk_flight. Run each scene you care about.
# Do not run this with Sodium, Iris or Canvas installed: those modules switch themselves off there,
# and the summarizer refuses a pair whose module states are not the requested ones.
#
# Needs a real GPU and a display, bash, and the same world copy for every launch (WORLD/GAME_DIR,
# see scripts/bench-client.sh). Prints the paired statistics; apply the rule in BENCHMARKS.md.
set -euo pipefail

MODULE="${1:?usage: bench-client-module.sh <module> [pair-count]}"
PAIRS="${2:-${PAIRS:-6}}"
SCENE="${SCENE:-stationary}"
PREFIX="${PREFIX:-client_${MODULE}_${SCENE}}"

if ! [[ "$MODULE" =~ ^[a-z_]+$ ]]; then
  echo "module must be a lower-case module key" >&2
  exit 2
fi
if ! [[ "$PAIRS" =~ ^[0-9]+$ ]] || (( PAIRS < 1 )); then
  echo "pair count must be a positive integer" >&2
  exit 2
fi
if (( PAIRS < 6 )); then
  echo "WARNING: at least 6 balanced pairs are required for a verdict; ${PAIRS} is a smoke run." >&2
fi

cd "$(dirname "$0")/.."

case "$MODULE" in
  retained_terrain|java_mesher|mesher_fast_path|render_snapshot|section_task_queue|rgss_endpoint|terrain_metrics) ;;
  fsr_upscaling)
    echo "fsr_upscaling renders below native resolution by design, so it is a quality trade and not judged by" >&2
    echo "the 3% rule. Measure it if you like, but it stays opt-in with its own description." >&2
    ;;
  *)
    echo "not a client render module: ${MODULE}" >&2
    exit 2
    ;;
esac

# The module plus whatever it depends on, read from the registry so this cannot drift.
ON_FORCE="$(MODULE="$MODULE" python3 - <<'PY'
import os, re
from pathlib import Path
text = Path("src/main/java/dev/ultima/config/UltimaModules.java").read_text(encoding="utf-8")
blocks = re.split(r"(?=new Module\(|Module\.client\()", text[text.index("List<Module> ALL = List.of("):])[1:]
deps = {}
for block in blocks:
    head = re.match(r'(?:new Module|Module\.client)\(\s*"([a-z_]+)"', block)
    if head:
        found = re.search(r'",\s*List\.of\(([^)]*)\)', block)
        deps[head.group(1)] = re.findall(r'"([a-z_]+)"', found.group(1)) if found else []
module = os.environ["MODULE"]
if module not in deps:
    raise SystemExit(f"unknown module {module}")
seen, todo = [], [module]
while todo:
    key = todo.pop()
    if key not in seen:
        seen.append(key)
        todo.extend(deps.get(key, []))
print(",".join(f"{key}=true" for key in seen))
PY
)"
OFF_FORCE="$(echo "$ON_FORCE" | sed 's/=true/=false/g')"
VARIED="$(echo "$ON_FORCE" | sed 's/=true//g')"

echo "client A/B: ${MODULE} scene=${SCENE} pairs=${PAIRS}"
echo "  OFF: mode=disabled ${OFF_FORCE}"
echo "  ON:  mode=disabled ${ON_FORCE}"

run_side() {
  local pair="$1" side="$2" force="$3"
  local label="${PREFIX}_pair${pair}_${side}"
  echo "===== ${label} ====="
  SCENE="$SCENE" CAMERA_MODE="$SCENE" PAIR_LABEL="$label" VARIED_KEYS="$VARIED" FORCE_MODULES="$force" \
    bash scripts/bench-client.sh "$label" disabled
}

for (( pair = 1; pair <= PAIRS; pair++ )); do
  if (( pair % 2 == 1 )); then
    run_side "$pair" off "$OFF_FORCE"
    run_side "$pair" on "$ON_FORCE"
  else
    run_side "$pair" on "$ON_FORCE"
    run_side "$pair" off "$OFF_FORCE"
  fi
done

echo "===== summarize ${PAIRS} pairs ====="
python3 scripts/summarize-client-bench.py run/ultima-client-benchmark-${PREFIX}_pair*_*.json
