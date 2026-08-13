#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-wrapper.sh
./gradlew genSources
TARGET=.agent/vanilla-src
rm -rf "$TARGET"
mkdir -p "$TARGET"
SOURCE_JAR=""
while IFS= read -r candidate; do
  if unzip -Z1 "$candidate" 2>/dev/null | grep -q '^net/minecraft/.*\.java$'; then
    SOURCE_JAR="$candidate"
    break
  fi
done < <(find "$HOME/.gradle/caches" "$PWD/.gradle" -type f -iname '*sources.jar' -size +5M -printf '%T@ %p\n' 2>/dev/null | sort -nr | cut -d' ' -f2-)
if [[ -z "$SOURCE_JAR" ]]; then
  echo "Minecraft sources JAR was not found after genSources." >&2
  exit 1
fi
unzip -q "$SOURCE_JAR" -d "$TARGET"
printf '%s\n' "$SOURCE_JAR" > .agent/vanilla-source-jar.txt
echo "Vanilla Minecraft sources exported to $TARGET"
