#!/usr/bin/env bash
# Mesher hardware A/B harness. Compares default modules + mesher_fast_path=false
# versus default modules + mesher_fast_path=true. Does not enable PR #3 lab
# modules and does not change shipped defaults.
#
# Usage:
#   DRY_RUN=1 bash scripts/bench-mesher-ab.sh
#   bash scripts/bench-mesher-ab.sh [pair-count]
#
# Scenes (repeat the script per scene, or set SCENE=all):
#   mesher_cold_load       first compile storm after joining an unloaded world
#   mesher_rebuild_storm   mass remesh (F3+T / render-distance change after warmup)
#   mesher_chunk_flight    streaming compiles along the retained chunk-flight route
#
# Primary scores are mesherMetrics.* (CPU compile time, coverage, fail-open).
# This script never prints an FPS verdict.
set -euo pipefail

PAIRS="${1:-${PAIRS:-1}}"
SCENE="${SCENE:-all}"
PREFIX="${PREFIX:-mesher}"
DRY_RUN="${DRY_RUN:-0}"
OFF_MODE="${OFF_MODE:-default}"
ON_MODE="${ON_MODE:-default}"

if ! [[ "$PAIRS" =~ ^[0-9]+$ ]] || (( PAIRS < 1 )); then
  echo "pair count must be a positive integer" >&2
  exit 2
fi

cd "$(dirname "$0")/.."

scenes=()
if [[ "$SCENE" == all ]]; then
  scenes=(mesher_cold_load mesher_rebuild_storm mesher_chunk_flight)
else
  scenes=("$SCENE")
fi

for scene in "${scenes[@]}"; do
  case "$scene" in
    mesher_cold_load|mesher_rebuild_storm|mesher_chunk_flight) ;;
    *)
      echo "unknown mesher scene: ${scene}" >&2
      exit 2
      ;;
  esac
done

camera_for() {
  case "$1" in
    mesher_chunk_flight) echo chunk_flight ;;
    *) echo stationary ;;
  esac
}

warmup_for() {
  case "$1" in
    mesher_cold_load) echo 60 ;;
    mesher_rebuild_storm) echo 600 ;;
    *) echo 1200 ;;
  esac
}

sample_for() {
  case "$1" in
    mesher_cold_load) echo 2400 ;;
    *) echo 12000 ;;
  esac
}

echo "Ultima mesher A/B harness"
echo "CPU meshing time only, NOT a real FPS/GPU claim, requires hardware A/B before any performance statement"
echo "pairs=${PAIRS} scenes=${scenes[*]} dry_run=${DRY_RUN}"
echo "OFF: mode=${OFF_MODE} mesher_fast_path=false"
echo "ON:  mode=${ON_MODE} mesher_fast_path=true (FORCE_MODULES override; shipped default stays false)"
echo "retained_terrain stays false. Do not enable PR #3 lab modules."

if [[ "$DRY_RUN" == 1 ]]; then
  for scene in "${scenes[@]}"; do
    echo "----- ${scene} -----"
    echo "  camera=$(camera_for "$scene") warmup=$(warmup_for "$scene") sample=$(sample_for "$scene")"
    echo "  metrics: meshBuildNs meshBuildNsPerSection sectionsPerSecond fastPathCoverageOfNonAir weightedFastPathBlocks fallbackByReason meshFastPathFailures meshFastPathCircuitBreakerTrips"
    echo "  off: SCENE=${scene} FORCE_MODULES=mesher_fast_path=false,retained_terrain=false"
    echo "  on:  SCENE=${scene} FORCE_MODULES=mesher_fast_path=true,retained_terrain=false"
  done
  echo "mesher A/B harness dry-run passed"
  exit 0
fi

run_side() {
  local scene="$1" pair="$2" side="$3" mesher="$4" mode="$5"
  local label="${PREFIX}_${scene}_pair${pair}_${side}"
  echo "===== ${label} mesher_fast_path=${mesher} ====="
  SCENE="$scene" \
    CAMERA_MODE="$(camera_for "$scene")" \
    WARMUP_FRAMES="$(warmup_for "$scene")" \
    SAMPLE_FRAMES="$(sample_for "$scene")" \
    PREFIX="$PREFIX" \
    PAIR_LABEL="$label" \
    FORCE_MODULES="mesher_fast_path=${mesher},retained_terrain=false" \
    bash scripts/bench-client.sh "$label" "$mode"
}

for scene in "${scenes[@]}"; do
  for (( pair = 1; pair <= PAIRS; pair++ )); do
    if (( pair % 2 == 1 )); then
      run_side "$scene" "$pair" off false "$OFF_MODE"
      run_side "$scene" "$pair" on true "$ON_MODE"
    else
      run_side "$scene" "$pair" on true "$ON_MODE"
      run_side "$scene" "$pair" off false "$OFF_MODE"
    fi
  done
done

echo "===== summarize mesher pairs ====="
python3 scripts/summarize-mesher-bench.py --self-test
echo "Compare off/on JSON with:"
echo "  python3 scripts/summarize-mesher-bench.py run/${PREFIX}_<scene>_pair1_off.json run/${PREFIX}_<scene>_pair1_on.json"
echo "CPU meshing counters only. NOT a real FPS/GPU claim."
