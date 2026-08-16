#!/usr/bin/env bash
set -euo pipefail

if [[ -x ./gradlew && -f gradle/wrapper/gradle-wrapper.jar ]]; then
  exit 0
fi

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is not installed. Open this repository in its Codespace/devcontainer or install Gradle 9.5.1." >&2
  exit 1
fi

gradle wrapper --gradle-version 9.5.1
chmod +x ./gradlew
