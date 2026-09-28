#!/usr/bin/env bash
# Starts the real client (under xvfb in CI) with the given Modrinth mods next to Ultima, with every
# client render module and the default-on simulation modules requested on, runs the client game
# test, and checks that the module states Ultima logged match what the loaded mods imply (Sodium,
# Iris and Canvas switch the render modules off with reason=incompatible_mod, Lithium the
# simulation ones, Iris also FSR) and that the log holds no Mixin failure.
#
# usage: bash scripts/compat-client.sh <label> <modrinth-slug>...
#   e.g. bash scripts/compat-client.sh sodium sodium
#        bash scripts/compat-client.sh sodium_iris sodium iris
set -euo pipefail
cd "$(dirname "$0")/.."

(( $# >= 2 )) || { echo "usage: compat-client.sh <label> <modrinth-slug>..." >&2; exit 2; }
LABEL="compat_$1"
shift

rm -rf run/clienttest/mods
mkdir -p run/clienttest/mods
python3 scripts/fetch-mods.py run/clienttest/mods "$@"
trap 'rm -rf run/clienttest/mods' EXIT

REQUESTED_ON="retained_terrain,java_mesher,mesher_fast_path,render_snapshot,section_task_queue,rgss_endpoint,fsr_upscaling"
REQUESTED_ON+=",cursor_step,entity_section_lookup,block_collision_shape,collision_shell_skip,supporting_block_shape_skip,full_cube_move"
bash scripts/client-test.sh "$LABEL" "$REQUESTED_ON"
python3 scripts/compat-expect.py --client "build/clienttest/logs/$LABEL.log" "$@"
