#!/usr/bin/env bash
# Temporary CI reconnaissance: print public API signatures needed to write the runtime harness.
# Reads recon/queries.txt: one directive per line, "jar <glob>" or "javap <jar-glob> <fqcn>...".
set -uo pipefail

GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
find_jar() {
  find "$GRADLE_HOME_DIR" "$PWD/.gradle" "$PWD/build" -type f -name "$1" 2>/dev/null | head -n "${2:-3}"
}

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  read -r cmd rest <<<"$line"
  echo "::group::$line"
  case "$cmd" in
    jar)
      find_jar "$rest" 20
      ;;
    ls)
      # ls <jar-glob> <grep-pattern>
      set -- $rest
      jar=$(find_jar "$1" 1)
      echo "jar=$jar"
      [[ -n "$jar" ]] && unzip -Z1 "$jar" | grep -E "${2:-.}" | head -100
      ;;
    javap)
      set -- $rest
      glob="$1"; shift
      jar=$(find_jar "$glob" 1)
      echo "jar=$jar"
      [[ -n "$jar" ]] && javap -public -cp "$jar" "$@" 2>&1 | head -200
      ;;
    javapg)
      # javapg <jar-glob> <fqcn> <grep-regex>
      set -- $rest
      glob="$1"; cls="$2"; shift 2
      jar=$(find_jar "$glob" 1)
      [[ -n "$jar" ]] && javap -public -cp "$jar" "$cls" 2>&1 | grep -E "${*:-.}" | head -60
      ;;
    sh)
      bash -c "$rest" 2>&1 | head -200
      ;;
    *)
      echo "unknown directive: $cmd"
      ;;
  esac
  echo "::endgroup::"
done < recon/queries.txt
