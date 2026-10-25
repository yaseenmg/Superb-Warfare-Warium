#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" \
  "$ROOT/src/main/java/dev/manus/wariumsbwcompat/Heuristics.java" \
  "$ROOT/src/test/java/dev/manus/wariumsbwcompat/HeuristicsSelfTest.java"
java -cp "$OUT" dev.manus.wariumsbwcompat.HeuristicsSelfTest
