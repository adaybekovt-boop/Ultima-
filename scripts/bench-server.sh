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

# The entity load is generated rather than committed, from a fixed seed and an explicit linear
# congruential generator, so every run places exactly the same entities in the same places.
# They are spread across the whole force-loaded region so that the number of populated entity
# sections resembles a server whose players are spread out rather than standing in one place.
FUNCTIONS="bench/ultima_bench/data/ultima/function"
mkdir -p "$FUNCTIONS"
awk -v span=260 -v mobs=700 -v items=400 'BEGIN {
  print "gamerule advance_time false";
  print "gamerule random_tick_speed 3";
  print "time set midnight";
  print "difficulty normal";
  split("zombie skeleton cow sheep pig chicken creeper spider", kinds, " ");
  seed = 20260616;
  width = span * 2 + 1;
  for (i = 0; i < mobs + items; i++) {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    x = (seed % width) - span;
    seed = (seed * 1103515245 + 12345) % 2147483648;
    z = (seed % width) - span;
    if (i < mobs) {
      printf "summon minecraft:%s %d.5 -59.0 %d.5\n", kinds[(i % 8) + 1], x, z;
    } else {
      printf "summon minecraft:item %d.5 -59.0 %d.5 {Item:{id:\"minecraft:cobblestone\",count:1}}\n", x, z;
    }
  }
}' > "${FUNCTIONS}/load_test.mcfunction"
echo "generated $(wc -l < "${FUNCTIONS}/load_test.mcfunction") load commands"

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

# forceload is capped per command, so the region is covered in tiles.
for cx in -272 -16 240; do
  for cz in -272 -16 240; do
    send "forceload add ${cx} ${cz} $((cx + 255)) $((cz + 255))" 4
  done
done
send "function ultima:load_test" 45
send "tick sprint ${TICKS}" 5
wait_for 'Sprint completed' 1800
send "tick query" 5
# Confirms both sides of a comparison really ticked the same load.
send "kill @e[type=cow]" 4
send "stop" 15

echo "===== ${LABEL} (${MODE}) ====="
grep -E 'Sprint completed|Average time per tick|Percentiles|Killed' "$LOG" || true
