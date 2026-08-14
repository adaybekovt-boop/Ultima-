#!/usr/bin/env bash
# Runs Ultima's differential audit harnesses.
#
# These are standalone Java programs. They do not depend on Minecraft, on Fabric, or on the Gradle
# build, so they can be run in an environment where the Minecraft toolchain cannot be provisioned.
# Each one places a transcription of the Ultima Mixin's logic next to a reference port of the
# vanilla algorithm it replaces and compares them position by position.
#
# Usage: bash tools/audit/run.sh
set -euo pipefail

cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/ultima-audit"
FASTUTIL_VERSION="8.5.15"
FASTUTIL="${OUT}/fastutil-${FASTUTIL_VERSION}.jar"
MAVEN_CENTRAL="https://repo.maven.apache.org/maven2"

mkdir -p "$OUT"

if [[ ! -f "$FASTUTIL" ]]; then
  echo "fetching fastutil ${FASTUTIL_VERSION} (SectionAudit runs against the real containers)"
  curl -fsSL -o "$FASTUTIL" \
    "${MAVEN_CENTRAL}/it/unimi/dsi/fastutil/${FASTUTIL_VERSION}/fastutil-${FASTUTIL_VERSION}.jar"
fi

javac -nowarn -cp "$FASTUTIL" -d "$OUT/classes" CursorAudit.java SectionAudit.java

echo
echo "################ cursor_step / collision_shell_skip traversal ################"
java -cp "$OUT/classes" CursorAudit

echo
echo "################ entity_section_lookup ################"
java -cp "$OUT/classes:$FASTUTIL" SectionAudit
