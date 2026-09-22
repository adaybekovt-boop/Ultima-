#!/usr/bin/env bash
# Offline contract check for the exact Iris/Sodium jars supported by killer-modules-v1.
set -euo pipefail

IRIS_JAR="${1:?usage: check-killer-adapters.sh <iris-1.11.4+mc26.2.jar> <sodium-0.9.2+mc26.2.jar>}"
SODIUM_JAR="${2:?usage: check-killer-adapters.sh <iris-1.11.4+mc26.2.jar> <sodium-0.9.2+mc26.2.jar>}"
IRIS_JAR_SHA="f1f7ab57c974d193ba33aa285864a0ded949216f402116fceaf4dc7739b4dd7c"
IRIS_CLASS_SHA="101fb251c68034e4c8346de1c550616059d896dbfbb3fae56e5982a89877c25f"
SODIUM_JAR_SHA="16a5e91db49750f8c046ecca3b8f2af5a28ea6f9b6581b42f123ca8fda20864f"
SODIUM_CLASS_SHA="5a409c73d5e4a30853a5b4896db8610607d07b3a111b2cf80ffc82a81d11b443"
IRIS_ENTRY="net/irisshaders/iris/pipeline/transform/TransformPatcher.class"
SODIUM_ENTRY="net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.class"

for tool in sha256sum unzip javap rg; do
  command -v "$tool" >/dev/null || { echo "required tool not found: $tool" >&2; exit 2; }
done
for jar in "$IRIS_JAR" "$SODIUM_JAR"; do
  [[ -f "$jar" ]] || { echo "jar not found: $jar" >&2; exit 2; }
done

require_hash() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "$label fingerprint mismatch" >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $actual" >&2
    exit 1
  fi
}

class_hash() {
  unzip -p "$1" "$2" | sha256sum | awk '{print $1}'
}

require_line() {
  local file="$1" line="$2"
  rg -F -q -- "$line" "$file" || { echo "missing bytecode contract: $line" >&2; exit 1; }
}

require_hash "Iris jar" "$IRIS_JAR_SHA" "$(sha256sum "$IRIS_JAR" | awk '{print $1}')"
require_hash "Iris TransformPatcher" "$IRIS_CLASS_SHA" "$(class_hash "$IRIS_JAR" "$IRIS_ENTRY")"
require_hash "Sodium jar" "$SODIUM_JAR_SHA" "$(sha256sum "$SODIUM_JAR" | awk '{print $1}')"
require_hash "Sodium RenderSectionManager" "$SODIUM_CLASS_SHA" "$(class_hash "$SODIUM_JAR" "$SODIUM_ENTRY")"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TMP_DIR"' EXIT
javap -classpath "$IRIS_JAR" -p -s \
  net.irisshaders.iris.pipeline.transform.TransformPatcher > "$TMP_DIR/iris.txt"
javap -classpath "$SODIUM_JAR" -p -s \
  net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager > "$TMP_DIR/sodium-manager.txt"
javap -classpath "$SODIUM_JAR" -p -s \
  net.caffeinemc.mods.sodium.client.render.chunk.RenderSection > "$TMP_DIR/sodium-section.txt"
javap -classpath "$SODIUM_JAR" -p -c \
  net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager > "$TMP_DIR/sodium-manager-code.txt"

require_line "$TMP_DIR/iris.txt" \
  'descriptor: (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;'
require_line "$TMP_DIR/iris.txt" \
  'descriptor: (Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;'
require_line "$TMP_DIR/sodium-manager.txt" \
  'descriptor: (Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;)V'
require_line "$TMP_DIR/sodium-manager.txt" \
  'descriptor: (Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;)V'
require_line "$TMP_DIR/sodium-manager-code.txt" \
  'net/caffeinemc/mods/sodium/client/render/chunk/lists/DeferredTaskList.dequeueNextSectionPos:()J'
require_line "$TMP_DIR/sodium-section.txt" 'descriptor: (IJ)V'
require_line "$TMP_DIR/sodium-section.txt" \
  'descriptor: (Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)I'
require_line "$TMP_DIR/sodium-section.txt" \
  'descriptor: (Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/BuilderTaskOutput;)Z'

echo "Killer-module adapter fingerprints and bytecode contracts match."
