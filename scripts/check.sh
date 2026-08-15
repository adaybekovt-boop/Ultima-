#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-wrapper.sh
./gradlew --no-daemon build
python3 scripts/summarize-client-bench.py --self-test
