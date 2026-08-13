#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-wrapper.sh
./gradlew genSources
./gradlew build
