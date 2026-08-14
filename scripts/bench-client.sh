#!/usr/bin/env bash
# Launches the client with Ultima's opt-in frame-time recorder.
#
# Usage: bash scripts/bench-client.sh <run-label> <disabled|default|enabled>
# Run each side with the same copied world, camera route, resolution, render/simulation distance,
# graphics options, frame cap, shader pack, resource packs, and entity/particle population.
set -euo pipefail

LABEL="${1:?usage: bench-client.sh <run-label> <disabled|default|enabled>}"
MODE="${2:?usage: bench-client.sh <run-label> <disabled|default|enabled>}"
WARMUP_FRAMES="${WARMUP_FRAMES:-1200}"
SAMPLE_FRAMES="${SAMPLE_FRAMES:-12000}"

if [[ ! "$MODE" =~ ^(disabled|default|enabled)$ ]]; then
  echo "mode must be exactly 'disabled', 'default', or 'enabled'" >&2
  exit 2
fi
if [[ ! "$LABEL" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "label may contain only letters, digits, dot, underscore, and hyphen" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
bash scripts/ensure-wrapper.sh
mkdir -p run/config

mapfile -t MODULE_ENTRIES < <(
  awk '/(new Module|Module\.client)\("[a-z_]+", (true|false),/ {
    line = $0
    state = line
    sub(/^.*(new Module|Module\.client)\("/, "", line)
    sub(/".*$/, "", line)
    sub(/^.*", /, "", state)
    sub(/,.*/, "", state)
    print line "=" state
  }' src/main/java/dev/ultima/config/UltimaModules.java
)
if (( ${#MODULE_ENTRIES[@]} == 0 )); then
  echo "failed to discover optimization module keys" >&2
  exit 1
fi

{
  for entry in "${MODULE_ENTRIES[@]}"; do
    key="${entry%%=*}"
    default_state="${entry#*=}"
    if [[ "$key" == client_benchmark ]]; then
      state=true
    else
      case "$MODE" in
        enabled) state=true ;;
        disabled) state=false ;;
        default) state="$default_state" ;;
      esac
    fi
    printf '%s=%s\n' "$key" "$state"
  done
} > run/config/ultima.properties

OUTPUT="$PWD/run/ultima-client-benchmark-${LABEL}.json"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dultima.clientBenchmark=true -Dultima.clientBenchmark.warmupFrames=${WARMUP_FRAMES} -Dultima.clientBenchmark.sampleFrames=${SAMPLE_FRAMES} -Dultima.clientBenchmark.output=${OUTPUT}"

echo "Client benchmark mode: ${MODE}"
echo "Output: ${OUTPUT}"
echo "Keep world, camera path, resolution, distances, graphics, shaders, packs, and population identical."
echo "The recorder starts after a world is loaded, discards ${WARMUP_FRAMES} frames, then records ${SAMPLE_FRAMES} frames."

./gradlew --no-daemon runClient
