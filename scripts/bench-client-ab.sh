#!/usr/bin/env bash
# Runs the primary client A/B protocol: disabled versus default, balanced pairs.
#
# Usage: bash scripts/bench-client-ab.sh [pair-count]
#
# Default pair count is 6. Order alternates OFF/ON, ON/OFF, ... so each side leads equally.
# Does not use mode=enabled. Set ON_MODE=enabled ALLOW_EXPERIMENTAL_ON=1 only for an
# experimental bound that must not be reported as the release result.
#
# Required for a credible run:
#   identical world copy per launch, same camera, resolution, distances, graphics,
#   resource/shader packs, and entity/particle population.
set -euo pipefail

PAIRS="${1:-${PAIRS:-6}}"
OFF_MODE="${OFF_MODE:-disabled}"
ON_MODE="${ON_MODE:-default}"
PREFIX="${PREFIX:-release}"
SCENE="${SCENE:-stationary_overworld}"

if ! [[ "$PAIRS" =~ ^[0-9]+$ ]] || (( PAIRS < 1 )); then
  echo "pair count must be a positive integer" >&2
  exit 2
fi
if (( PAIRS < 6 )); then
  echo "WARNING: primary protocol requires at least 6 balanced pairs; ${PAIRS} is not enough for a release claim." >&2
fi
if [[ "$ON_MODE" == enabled && "${ALLOW_EXPERIMENTAL_ON:-}" != 1 ]]; then
  echo "Refusing ON_MODE=enabled as the primary A/B. The release comparison is disabled vs default." >&2
  exit 2
fi

cd "$(dirname "$0")/.."

run_side() {
  local pair="$1" side="$2" mode="$3"
  local label="${PREFIX}_pair${pair}_${side}"
  echo "===== ${label} (${mode}) ====="
  PAIR_LABEL="$label" SCENE="$SCENE" bash scripts/bench-client.sh "$label" "$mode"
}

for (( pair = 1; pair <= PAIRS; pair++ )); do
  if (( pair % 2 == 1 )); then
    run_side "$pair" off "$OFF_MODE"
    run_side "$pair" on "$ON_MODE"
  else
    run_side "$pair" on "$ON_MODE"
    run_side "$pair" off "$OFF_MODE"
  fi
done

echo "===== summarize ${PAIRS} pairs ====="
python3 scripts/summarize-client-bench.py run/ultima-client-benchmark-${PREFIX}_pair*_*.json
