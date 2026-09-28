#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-wrapper.sh
./gradlew build
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys; assert sys.version_info.major == 3' >/dev/null 2>&1; then
  PYTHON_BIN=python3
elif command -v python >/dev/null 2>&1 && python -c 'import sys; assert sys.version_info.major == 3' >/dev/null 2>&1; then
  PYTHON_BIN=python
else
  echo "Python 3 is required for benchmark summarizer self-tests." >&2
  exit 1
fi
"$PYTHON_BIN" scripts/summarize-client-bench.py --self-test
"$PYTHON_BIN" scripts/summarize-mesher-bench.py --self-test
DRY_RUN=1 bash scripts/bench-mesher-ab.sh
