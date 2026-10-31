#!/usr/bin/env bash
# Compiles the engine and runs the end-to-end tests against local fixture servers.
# Needs: JDK 17+, kotlinc 2.x on PATH (or KOTLINC=/path/to/kotlinc), and jsoup.jar (JSOUP_JAR=/path/to/jsoup.jar).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
JSOUP_JAR="${JSOUP_JAR:?set JSOUP_JAR to a jsoup jar (1.15+)}"
OUT="$(mktemp -d)"
"$KOTLINC" -nowarn -cp "$JSOUP_JAR" "$ROOT/engine/src/main/kotlin" "$ROOT/engine/src/test/kotlin" -d "$OUT"
KOTLIN_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")"
java -cp "$OUT:$JSOUP_JAR:$KOTLIN_HOME/lib/kotlin-stdlib.jar" com.openmanga.engine.EngineTestsKt
