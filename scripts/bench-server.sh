#!/usr/bin/env bash
# Measures dedicated-server tick cost under a fixed, entity-heavy load.
#
# Usage: bash scripts/bench-server.sh <run-label> <enabled|disabled>
#
# The second argument writes an Ultima config with every optimization module either enabled or
# disabled, so the same world and the same load can be measured against vanilla behaviour.
#
# The world is recreated from scratch on every run so that entity counts, forced chunks and the
# world seed are identical between the two sides of a comparison.
set -euo pipefail

LABEL="${1:?usage: bench-server.sh <run-label> <enabled|disabled>}"
MODE="${2:?usage: bench-server.sh <run-label> <enabled|disabled>}"
TICKS="${TICKS:-2000}"
WORLD="ultima-bench"
LOG="/tmp/ultima-bench-${LABEL}.log"
SESSION="ultima-bench-${LABEL}"

cd "$(dirname "$0")/.."
bash scripts/ensure-wrapper.sh

rm -rf "run/${WORLD}"
mkdir -p run/config
printf 'eula=true\n' > run/eula.txt
cat > run/server.properties <<PROPS
level-name=${WORLD}
level-type=minecraft\\:flat
level-seed=ultima
online-mode=false
sync-chunk-writes=false
view-distance=10
simulation-distance=10
max-tick-time=-1
PROPS

# Write every known module to the requested state. Missing keys default to enabled, so the
# disabled side must list them explicitly.
{
  for key in $(grep -oE '"[a-z_]+", (true|false)' src/main/java/dev/ultima/config/UltimaModules.java \
      | sed 's/"\([a-z_]*\)".*/\1/'); do
    printf '%s=%s\n' "$key" "$([[ "$MODE" == enabled ]] && echo true || echo false)"
  done
} > run/config/ultima.properties
echo "== module states (${MODE}) =="
cat run/config/ultima.properties

TMUX_CONF=(-f /exec-daemon/tmux.portal.conf)
if [[ ! -f /exec-daemon/tmux.portal.conf ]]; then TMUX_CONF=(); fi

tmux "${TMUX_CONF[@]}" kill-session -t "=${SESSION}" 2>/dev/null || true
tmux "${TMUX_CONF[@]}" new-session -d -s "${SESSION}" -c "$PWD" -- bash -l
tmux "${TMUX_CONF[@]}" send-keys -t "${SESSION}:0.0" \
  "cd $PWD && ./gradlew --no-daemon --console=plain runServer 2>&1 | tee ${LOG}" C-m

send() { tmux "${TMUX_CONF[@]}" send-keys -t "${SESSION}:0.0" "$1" C-m; sleep "${2:-2}"; }
wait_for() {
  local pattern="$1" limit="${2:-300}" waited=0
  while (( waited < limit )); do
    if grep -q "$pattern" "$LOG" 2>/dev/null; then return 0; fi
    sleep 3; waited=$((waited + 3))
  done
  echo "timed out waiting for: $pattern" >&2
  return 1
}

wait_for 'Done ([0-9.]*s)!' 600
mkdir -p "run/${WORLD}/datapacks"
cp -r bench/ultima_bench "run/${WORLD}/datapacks/"
send "reload" 10
wait_for 'Reloading!' 60
send "forceload add -80 -80 80 80" 6
send "function ultima:load_test" 30
send "tick sprint ${TICKS}" 5
wait_for 'Sprint completed' 900
send "tick query" 5
send "stop" 15

echo "===== ${LABEL} (${MODE}) ====="
grep -E 'Sprint completed|Average time per tick|Percentiles' "$LOG" || true
