#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${REMANENCE_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${REMANENCE_ANDROID_SDK_ROOT:-/usr/lib/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export TMPDIR="$repo_root/.hold-build/tmp"
mkdir -p "$TMPDIR"
# Robolectric's native extraction must not exhaust the host's quota-limited /tmp.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$TMPDIR"
cd "$repo_root/android"
if (( $# == 0 )); then
  set -- testDebugUnitTest assembleDebug
fi
exec ./gradlew "$@" --console=plain --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
