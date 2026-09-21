#!/usr/bin/env bash
# Balanced, isolated A/B launcher for Ultima's three experimental killer modules.
#
# Usage: bash scripts/bench-killer-modules-ab.sh <profile> [pair-count]
# Profiles:
#   artifact-cold, artifact-warm, broker-trace, broker-control, broker-static,
#   warmup, all-trace, all-control
#
# Sodium/Iris/Lithium and the chosen shader/resource packs must already be installed in GAME_DIR.
# All ordinary driver caches are left untouched. For artifact-cold, only Ultima's exact cache
# directory is moved into run/ultima-cache-backups; nothing is deleted.
set -euo pipefail

PROFILE="${1:?usage: bench-killer-modules-ab.sh <profile> [pair-count]}"
PAIRS="${2:-${PAIRS:-6}}"
PREFIX="${PREFIX:-killer-${PROFILE}}"
SCENE="${SCENE:-killer_route}"
cd "$(dirname "$0")/.."
GAME_ROOT="${GAME_DIR:-$PWD/run}"
CACHE_ROOT="${GAME_ROOT%/}/cache/ultima/iris-frontend-v1"
BACKUP_ROOT="$PWD/run/ultima-cache-backups/${PREFIX}"
ALL_OFF="iris_shader_frontend_artifact_cache=false,cross_pipeline_admission_broker=false,render_warmup_system=false"
OFF_MODULES="$ALL_OFF"
OFF_BROKER_MODE="trace"

if ! [[ "$PAIRS" =~ ^[0-9]+$ ]] || (( PAIRS < 1 )); then
  echo "pair count must be a positive integer" >&2
  exit 2
fi
if (( PAIRS < 6 )); then
  echo "WARNING: fewer than 6 balanced pairs is diagnostic only." >&2
fi
case "$PROFILE" in
  artifact-cold|artifact-warm)
    ON_MODULES="iris_shader_frontend_artifact_cache=true,cross_pipeline_admission_broker=false,render_warmup_system=false"
    BROKER_MODE_VALUE="trace"
    ;;
  broker-trace)
    ON_MODULES="iris_shader_frontend_artifact_cache=false,cross_pipeline_admission_broker=true,render_warmup_system=false"
    BROKER_MODE_VALUE="trace"
    ;;
  broker-control)
    ON_MODULES="iris_shader_frontend_artifact_cache=false,cross_pipeline_admission_broker=true,render_warmup_system=false"
    OFF_MODULES="$ON_MODULES"
    BROKER_MODE_VALUE="control"
    ;;
  broker-static)
    ON_MODULES="iris_shader_frontend_artifact_cache=false,cross_pipeline_admission_broker=true,render_warmup_system=false"
    OFF_MODULES="$ON_MODULES"
    BROKER_MODE_VALUE="static"
    ;;
  warmup)
    ON_MODULES="iris_shader_frontend_artifact_cache=false,cross_pipeline_admission_broker=false,render_warmup_system=true"
    OFF_MODULES="$ON_MODULES"
    BROKER_MODE_VALUE="trace"
    ;;
  all-trace)
    ON_MODULES="iris_shader_frontend_artifact_cache=true,cross_pipeline_admission_broker=true,render_warmup_system=true"
    BROKER_MODE_VALUE="trace"
    ;;
  all-control)
    ON_MODULES="iris_shader_frontend_artifact_cache=true,cross_pipeline_admission_broker=true,render_warmup_system=true"
    BROKER_MODE_VALUE="control"
    ;;
  *)
    echo "unknown profile: $PROFILE" >&2
    exit 2
    ;;
esac

move_ultima_cache_for_cold_run() {
  local label="$1"
  if [[ "$PROFILE" != artifact-cold || ! -e "$CACHE_ROOT" ]]; then
    return
  fi
  if [[ -z "$CACHE_ROOT" || "$CACHE_ROOT" == "/" || "$CACHE_ROOT" != */cache/ultima/iris-frontend-v1 ]]; then
    echo "refusing unsafe cache path: $CACHE_ROOT" >&2
    exit 2
  fi
  mkdir -p "$BACKUP_ROOT"
  local destination="$BACKUP_ROOT/${label}"
  if [[ -e "$destination" ]]; then
    echo "refusing to overwrite cache backup: $destination" >&2
    exit 2
  fi
  mv -- "$CACHE_ROOT" "$destination"
  echo "Moved prior Ultima cache to $destination"
}

run_side() {
  local pair="$1" side="$2"
  local label="${PREFIX}_pair${pair}_${side}"
  local overrides="$OFF_MODULES"
  local broker_mode="$OFF_BROKER_MODE"
  local warmup_mode="profile"
  if [[ "$side" == on ]]; then
    overrides="$ON_MODULES"
    broker_mode="$BROKER_MODE_VALUE"
    warmup_mode="warm"
    move_ultima_cache_for_cold_run "$label"
  fi
  echo "===== ${label} (${PROFILE}) ====="
  FORCE_MODULES="$overrides" \
  BROKER_MODE="$broker_mode" \
  WARMUP_MODE="$warmup_mode" \
  AB_ROLE="$side" \
  PAIR_LABEL="${PREFIX}_pair${pair}" \
  SCENE="$SCENE" \
  REPLAY_MODE="tick" \
  bash scripts/bench-client.sh "$label" disabled
}

if [[ "$PROFILE" == artifact-warm ]]; then
  echo "===== priming persistent artifact cache ====="
  FORCE_MODULES="$ON_MODULES" \
  WARMUP_MODE="profile" \
  AB_ROLE="prime" \
  PAIR_LABEL="${PREFIX}_prime" \
  SCENE="$SCENE" \
  REPLAY_MODE="tick" \
  bash scripts/bench-client.sh "${PREFIX}_prime" disabled
fi

for (( pair = 1; pair <= PAIRS; pair++ )); do
  if (( pair % 2 == 1 )); then
    run_side "$pair" off
    run_side "$pair" on
  else
    run_side "$pair" on
    run_side "$pair" off
  fi
done

python3 scripts/summarize-client-bench.py "run/ultima-client-benchmark-${PREFIX}_pair"*_*.json
