#!/usr/bin/env bash
# Print hot methods and allocation pressure from a JFR recording.
set -euo pipefail
JFR="${1:?usage: summarize-jfr.sh <recording.jfr>}"
source "$HOME/.ultima-tools.sh" 2>/dev/null || true
JFR_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}jfr"
if ! command -v "$JFR_BIN" >/dev/null 2>&1 && [[ -x "$HOME/tools/jdk-25/bin/jfr" ]]; then
  JFR_BIN="$HOME/tools/jdk-25/bin/jfr"
fi

echo "===== JFR summary: ${JFR} ====="
"$JFR_BIN" summary "$JFR" || true
echo
echo "===== hot methods ====="
if "$JFR_BIN" view --help 2>/dev/null | grep -q hot-methods; then
  "$JFR_BIN" view hot-methods "$JFR" || true
else
  "$JFR_BIN" print --events jdk.ExecutionSample "$JFR" \
    | awk '
      /jdk.ExecutionSample/ { in_event=1; next }
      in_event && /stackTrace/ { grabbing=1; next }
      grabbing && /java\/lang\/Thread.run/ { grabbing=0; in_event=0; next }
      grabbing && /truncated/ { next }
      grabbing {
        gsub(/^[[:space:]]+/, "", $0)
        if ($0 != "" && !seen) { print; seen=1 }
      }
      in_event && /^$/ { seen=0; grabbing=0; in_event=0 }
    ' | sort | uniq -c | sort -nr | head -40
fi
echo
echo "===== allocation events (top types) ====="
"$JFR_BIN" print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB "$JFR" 2>/dev/null \
  | awk -F'"' '/objectClass/ { c[$2]++ } END { for (k in c) printf "%8d  %s\n", c[k], k }' \
  | sort -nr | head -30 || true
