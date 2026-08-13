#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-wrapper.sh
./gradlew genSources
TARGET=.agent/vanilla-src
rm -rf "$TARGET"
mkdir -p "$TARGET"

# Loom's split environment source sets produce more than one Minecraft sources JAR
# (common + clientOnly). Every JAR that carries net/minecraft sources is exported.
FOUND=0
: > .agent/vanilla-source-jar.txt
while IFS= read -r candidate; do
  # grep -c consumes the whole listing; grep -q would exit early and kill unzip
  # with SIGPIPE, which `set -o pipefail` would report as a failed candidate.
  if [[ "$(unzip -Z1 "$candidate" 2>/dev/null | grep -c '^net/minecraft/.*\.java$')" -gt 0 ]]; then
    unzip -qo "$candidate" -d "$TARGET"
    printf '%s\n' "$candidate" >> .agent/vanilla-source-jar.txt
    FOUND=1
  fi
done < <(find "$HOME/.gradle/caches" "$PWD/.gradle" -type f -iname '*-sources.jar' -size +1M -printf '%T@ %p\n' 2>/dev/null | sort -nr | cut -d' ' -f2-)

if [[ "$FOUND" -eq 0 ]]; then
  echo "Minecraft sources JAR was not found after genSources." >&2
  exit 1
fi
echo "Vanilla Minecraft sources exported to $TARGET"
