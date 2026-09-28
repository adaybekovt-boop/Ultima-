#!/usr/bin/env bash
# Differential runtime check on a real dedicated server.
#
# Runs the deterministic world scenarios (src/scenario) three times with different module sets:
#   off_a  every simulation module off (baseline)
#   off_b  the same again (control: proves the scenarios are deterministic)
#   on     every simulation module on (candidate)
# and requires the world-state digests of off_a, off_b and on to be identical. It also requires
# that "on" really enabled the modules, so a run where nothing engaged cannot pass.
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/ensure-wrapper.sh

RUN_DIR="run/scenarios"
OUT_DIR="build/scenarios"
SIM_MODULES=(
  entity_section_lookup block_collision_shape collision_shell_skip supporting_block_shape_skip
  full_cube_move cursor_step server_metrics blockentity_sleeping recipe_match_cache tag_bitsets
  state_property_cache container_slot_mask entity_query_early_out
)

prepare_run_dir() {
  rm -rf "$RUN_DIR/world" "$RUN_DIR/logs" "$RUN_DIR/config"
  mkdir -p "$RUN_DIR/config"
  echo "eula=true" > "$RUN_DIR/eula.txt"
  cat > "$RUN_DIR/server.properties" <<'PROPS'
level-seed=1
level-type=minecraft:flat
generate-structures=false
spawn-monsters=false
spawn-animals=false
allow-nether=false
online-mode=false
view-distance=4
simulation-distance=4
max-tick-time=-1
spawn-protection=0
enable-status=false
sync-chunk-writes=false
motd=ultima-scenarios
PROPS
}

write_module_config() {
  local value="$1"
  : > "$RUN_DIR/config/ultima.properties"
  local module
  for module in "${SIM_MODULES[@]}"; do
    echo "$module=$value" >> "$RUN_DIR/config/ultima.properties"
  done
}

run_label() {
  local label="$1" value="$2"
  echo "=== scenario run: $label (simulation modules = $value)"
  prepare_run_dir
  write_module_config "$value"
  local status=0
  # A hung server must fail the run, not the whole CI job: cap each run and keep its log.
  timeout "${SCENARIO_RUN_TIMEOUT:-420}" \
    ./gradlew runScenarioServer -Pultima.scenario.label="$label" --console=plain || status=$?
  mkdir -p "$OUT_DIR/logs"
  cp "$RUN_DIR/logs/latest.log" "$OUT_DIR/logs/$label.log" 2>/dev/null || true
  if [[ $status -ne 0 || ! -f "$OUT_DIR/$label.summary.txt" ]]; then
    echo "scenario run $label failed (exit $status) or produced no summary; tail of the server log:" >&2
    tail -n 60 "$RUN_DIR/logs/latest.log" >&2 || true
    exit 1
  fi
}

rm -rf "$OUT_DIR"
run_label off_a false
run_label off_b false
run_label on true

python3 scripts/compare-scenarios.py "$OUT_DIR" off_a off_b on "${SIM_MODULES[@]}"
