#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-wrapper.sh
./gradlew --no-daemon build
python3 scripts/summarize-client-bench.py --self-test
python3 scripts/summarize-mesher-bench.py --self-test
DRY_RUN=1 bash scripts/bench-mesher-ab.sh
