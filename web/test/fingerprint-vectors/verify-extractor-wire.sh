#!/usr/bin/env bash
# TEST-ONLY verifier runner: takes a produced browser extraction report and proves
# its format-3 wire blob is accepted by the UNCHANGED actual Kotlin codec, using
# ONLY the pinned artifacts already placed in TOOLS_DIR (see TOOLS.md). It does
# not download/install anything, runs no Gradle, and does not touch Android.
# Usage: verify-extractor-wire.sh <TOOLS_DIR> <PRODUCT_REPO_ROOT> <BROWSER_REPORT_JSON>
set -euo pipefail
TOOLS="$1"
REPO="$2"
REPORT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/java" "$WORK/classes" "$WORK/kclasses"

"$TOOLS/protoc" --java_out=lite:"$WORK/java" \
  -I "$REPO/protocol/proto" \
  "$REPO/protocol/proto/remanence/recognition/v2/recognition_v2.proto"

javac -cp "$TOOLS/protobuf-javalite.jar" -d "$WORK/classes" \
  $(find "$WORK/java" -name '*.java')

COMPILER_CP="$TOOLS/kotlin-compiler-embeddable.jar:$TOOLS/kotlin-stdlib.jar:$TOOLS/kotlin-reflect.jar:$TOOLS/kotlin-script-runtime.jar:$TOOLS/kotlin-daemon-embeddable.jar:$TOOLS/kotlinx-coroutines-core-jvm.jar:$TOOLS/annotations.jar"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -classpath "$TOOLS/kotlin-stdlib.jar:$TOOLS/protobuf-javalite.jar:$WORK/classes" \
  -d "$WORK/kclasses" \
  "$REPO/android/core/model/src/main/kotlin/dev/hryshyn/remanence/core/model/SiftRootSiftFingerprint.kt" \
  "$REPO/android/core/model/src/main/kotlin/dev/hryshyn/remanence/core/model/SiftRootSiftFingerprintCodec.kt" \
  "$HERE/VerifyExtractorWire.kt"

java -cp "$WORK/kclasses:$WORK/classes:$TOOLS/protobuf-javalite.jar:$TOOLS/kotlin-stdlib.jar" \
  VerifyExtractorWireKt "$REPORT"
