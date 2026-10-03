#!/usr/bin/env bash
# Tests of the app's core (it.paladia.minerva.core) on a plain JVM, against the fixtures in fixtures/.
# Usage:  ./test.sh      Needs javac/java 11+ (on Windows, Android Studio's jbr is found automatically).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_BIN=""
if ! command -v javac >/dev/null 2>&1; then
  for d in "${JAVA_HOME:-}" "/c/Program Files/Android/Android Studio/jbr"; do
    if [ -n "$d" ] && { [ -x "$d/bin/javac" ] || [ -x "$d/bin/javac.exe" ]; }; then
      JAVA_BIN="$d/bin/"
      break
    fi
  done
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"${JAVA_BIN}javac" -encoding UTF-8 --release 11 -d "$OUT" "$ROOT"/app/src/it/paladia/minerva/core/*.java "$ROOT"/app/test/it/paladia/minerva/core/*.java
"${JAVA_BIN}java" -cp "$OUT" it.paladia.minerva.core.TestMain "$ROOT/fixtures"
