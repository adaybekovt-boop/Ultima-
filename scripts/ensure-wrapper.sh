#!/usr/bin/env bash
# The committed Gradle 9.5.1 wrapper is the canonical build path.
# This script only recreates it when a checkout is missing the wrapper files.
set -euo pipefail

if [[ -x ./gradlew && -f gradle/wrapper/gradle-wrapper.jar && -f gradle/wrapper/gradle-wrapper.properties ]]; then
  exit 0
fi

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is not installed. Open this repository in its Codespace/devcontainer or install Gradle 9.5.1." >&2
  exit 1
fi

gradle wrapper --gradle-version 9.5.1
chmod +x ./gradlew
