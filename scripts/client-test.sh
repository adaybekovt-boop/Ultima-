#!/usr/bin/env bash
# Runs the real client with Ultima and the client game test (src/clienttest), saving screenshots to
# build/clienttest/<label>/.
#
# usage: bash scripts/client-test.sh <label> [module,module,...]
#
#   label "defaults"  no ultima.properties: the shipped defaults
#   any other label   every module off except the comma-separated list
#
# On a machine without a display it needs xvfb and a software OpenGL (Mesa llvmpipe), which is what
# CI installs; on a desktop it opens a window. No GPU is required. Pixel results are only comparable
# between runs on the same machine and the same graphics stack.
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL="${1:?usage: client-test.sh <label> [module,module,...]}"
FORCE_ON="${2:-}"
RUN_DIR="run/clienttest"

bash scripts/ensure-wrapper.sh

rm -rf "$RUN_DIR/saves" "$RUN_DIR/screenshots" "$RUN_DIR/logs" "$RUN_DIR/config" "build/clienttest/$LABEL"
mkdir -p "$RUN_DIR/config" "build/clienttest/$LABEL"
# Clouds drift with the game time and would differ between two identical runs.
cat > "$RUN_DIR/options.txt" <<'OPTIONS'
renderClouds:"false"
tutorialStep:"none"
OPTIONS
python3 - "$LABEL" "$FORCE_ON" "$RUN_DIR/config/ultima.properties" <<'PY'
import re, sys
from pathlib import Path
label, force_on, target = sys.argv[1], sys.argv[2], Path(sys.argv[3])
if label == "defaults":
    sys.exit(0)
source = Path("src/main/java/dev/ultima/config/UltimaModules.java").read_text(encoding="utf-8")
keys = re.findall(r'(?:new Module|Module\.client)\(\s*"([a-z_]+)"', source)
on = {k for k in force_on.split(",") if k}
unknown = on - set(keys)
if unknown:
    sys.exit(f"unknown modules: {sorted(unknown)}")
target.write_text("".join(f"{key}={'true' if key in on else 'false'}\n" for key in keys), encoding="utf-8")
PY

export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
export MESA_GL_VERSION_OVERRIDE="${MESA_GL_VERSION_OVERRIDE:-4.6}"
export MESA_GLSL_VERSION_OVERRIDE="${MESA_GLSL_VERSION_OVERRIDE:-460}"

runner=()
if [[ -z "${DISPLAY:-}" ]]; then
  command -v xvfb-run >/dev/null || { echo "no DISPLAY and no xvfb-run: install xvfb" >&2; exit 2; }
  runner=(xvfb-run -a -s "-screen 0 1280x720x24")
fi

echo "=== client test: $LABEL (modules on: ${FORCE_ON:-none, or shipped defaults})"
status=0
timeout "${CLIENT_TEST_TIMEOUT:-1200}" "${runner[@]}" \
  ./gradlew runClientGametest -Pultima.clienttest.label="$LABEL" --console=plain || status=$?

mkdir -p "build/clienttest/logs"
cp "$RUN_DIR/logs/latest.log" "build/clienttest/logs/$LABEL.log" 2>/dev/null || true
if [[ $status -ne 0 ]]; then
  echo "client test $LABEL failed (exit $status); interesting log lines:" >&2
  grep -nE "Loading [0-9]+ mods|ultima-clienttest|Exception|Caused by|ERROR|FATAL|gametest|GameTest" "$RUN_DIR/logs/latest.log" | head -50 >&2 || true
  echo "--- tail:" >&2
  tail -n 30 "$RUN_DIR/logs/latest.log" >&2 || true
  exit 1
fi
count="$(find "build/clienttest/$LABEL" -name '*.png' | wc -l)"
if (( count < 8 )); then
  echo "client test $LABEL saved only $count screenshots (expected 8)" >&2
  exit 1
fi
echo "client test $LABEL passed with $count screenshots"
