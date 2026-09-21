#!/usr/bin/env bash
set -euo pipefail

bash scripts/ensure-wrapper.sh
mkdir -p run/logs
rm -f run/logs/latest.log
./gradlew --no-daemon runServer -Pultima.mixinSmoke --args nogui --console=plain

LOG=run/logs/latest.log
if [[ ! -f "$LOG" ]]; then
  echo "Mixin smoke did not produce $LOG" >&2
  exit 1
fi
if ! grep -q 'ULTIMA_MIXIN_SMOKE_OK' "$LOG"; then
  echo "Mixin smoke success marker is missing" >&2
  exit 1
fi
if grep -Eq 'Mixin apply for mod ultima failed|Mixin transformation of .* failed|Failed to start the minecraft server' "$LOG"; then
  echo "Mixin smoke log contains a transformation/bootstrap failure" >&2
  exit 1
fi
echo "Ultima common Mixin apply smoke passed."
