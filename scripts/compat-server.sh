#!/usr/bin/env bash
# Starts the dedicated server headless with the given Modrinth mods next to Ultima, force-loads
# every common Mixin target (the same smoke as mixin-smoke.sh) and checks the module states Ultima
# logged. Only mods that load on a dedicated server are meaningful here (lithium, modmenu is
# client-only and is ignored). Client-side mods run through the client test.
#
# usage: bash scripts/compat-server.sh lithium
set -euo pipefail
cd "$(dirname "$0")/.."

(( $# >= 1 )) || { echo "usage: compat-server.sh <modrinth-slug>..." >&2; exit 2; }

rm -rf run/mods
mkdir -p run/mods
python3 scripts/fetch-mods.py run/mods "$@"
trap 'rm -rf run/mods' EXIT

bash scripts/mixin-smoke.sh
python3 scripts/compat-expect.py run/logs/latest.log "$@"
