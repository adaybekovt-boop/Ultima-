#!/usr/bin/env bash
# Balanced dedicated-server A/B: disabled versus default (or ON_MODE).
# Usage: bash scripts/bench-server-ab.sh [pair-count]
set -euo pipefail
PAIRS="${1:-${PAIRS:-3}}"
OFF_MODE="${OFF_MODE:-disabled}"
ON_MODE="${ON_MODE:-default}"
PREFIX="${PREFIX:-server}"
TICKS="${TICKS:-800}"
WARMUP_TICKS="${WARMUP_TICKS:-250}"

cd "$(dirname "$0")/.."
source "$HOME/.ultima-tools.sh" 2>/dev/null || true
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-25}"
export PATH="$JAVA_HOME/bin:$PATH"

for (( pair = 1; pair <= PAIRS; pair++ )); do
  if (( pair % 2 == 1 )); then
    TICKS="$TICKS" WARMUP_TICKS="$WARMUP_TICKS" bash scripts/bench-server.sh "${PREFIX}_pair${pair}_off" "$OFF_MODE"
    TICKS="$TICKS" WARMUP_TICKS="$WARMUP_TICKS" bash scripts/bench-server.sh "${PREFIX}_pair${pair}_on" "$ON_MODE"
  else
    TICKS="$TICKS" WARMUP_TICKS="$WARMUP_TICKS" bash scripts/bench-server.sh "${PREFIX}_pair${pair}_on" "$ON_MODE"
    TICKS="$TICKS" WARMUP_TICKS="$WARMUP_TICKS" bash scripts/bench-server.sh "${PREFIX}_pair${pair}_off" "$OFF_MODE"
  fi
done

echo "===== ${PREFIX} ${PAIRS} pairs ====="
python3 - <<'PY'
import glob, re, statistics
logs = sorted(glob.glob("/tmp/ultima-bench-*_pair*_*.log"))
pair_re = re.compile(r"ultima-bench-(?P<pre>.*_pair)(?P<n>\d+)_(?P<side>off|on)\.log$")
sprints = {}
kills = {}
for path in logs:
    m = pair_re.search(path)
    if not m:
        continue
    key = (int(m.group("n")), m.group("side"))
    times = []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = re.search(r"Sprint completed with [0-9]+ ticks per second, or ([0-9.]+) ms per tick", line)
            if match:
                times.append(float(match.group(1)))
            match = re.search(r"Killed (\d+) entities", line)
            if match:
                kills[key] = int(match.group(1))
    if len(times) >= 2:
        sprints[key] = times[-1]
    elif times:
        sprints[key] = times[-1]
pairs = sorted({n for n, _ in sprints})
offs, ons, deltas = [], [], []
print("pair  off_ms  on_ms   delta%")
for n in pairs:
    off = sprints.get((n, "off"))
    on = sprints.get((n, "on"))
    if off is None or on is None:
        continue
    delta = 100.0 * (on - off) / off
    offs.append(off)
    ons.append(on)
    deltas.append(delta)
    print(f"{n:4d}  {off:6.2f}  {on:6.2f}  {delta:+6.2f}%  cows off={kills.get((n,'off'))} on={kills.get((n,'on'))}")
if deltas:
    print(f"mean off {statistics.mean(offs):.3f} ms  on {statistics.mean(ons):.3f} ms  paired {statistics.mean(deltas):+.2f}%")
PY
