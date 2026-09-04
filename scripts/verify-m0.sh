#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  if ((${#COMPOSE[@]})); then
    "${COMPOSE[@]}" ps -a || true
    "${COMPOSE[@]}" logs --tail=80 postgres migrate api || true
  fi
  exit 1
}

log() {
  printf '%s\n' "$*"
}

need_cmd() {
  local name="$1"
  local hint="$2"
  command -v "$name" >/dev/null 2>&1 || die "${name} is not on PATH. ${hint}"
}

if [[ -o xtrace ]]; then
  die "refusing to run with xtrace enabled because it would leak secrets"
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)"
readonly SCRIPT_DIR REPO_ROOT

[[ -f "${REPO_ROOT}/compose.yaml" ]] || die "compose.yaml not found under ${REPO_ROOT}; cannot resolve repo root from script location"
[[ -x "${REPO_ROOT}/android/gradlew" ]] || die "android/gradlew is missing or not executable under ${REPO_ROOT}"
[[ -f "${REPO_ROOT}/server/pyproject.toml" ]] || die "server/pyproject.toml not found under ${REPO_ROOT}"

DOCKER=()
COMPOSE=()
PROBE_PATH="/var/lib/remanence/blobs/.remanence-m0-verify-probe"
readonly PROBE_PATH

cleanup_probe() {
  if ((${#COMPOSE[@]})); then
    "${COMPOSE[@]}" exec -T api rm -f "${PROBE_PATH}" >/dev/null 2>&1 || true
  fi
}

on_err() {
  printf 'error: verify-m0 failed at line %s\n' "${1:-?}" >&2
  if ((${#COMPOSE[@]})); then
    "${COMPOSE[@]}" ps -a || true
    "${COMPOSE[@]}" logs --tail=80 postgres migrate api || true
  fi
}

trap 'on_err $LINENO' ERR
trap cleanup_probe EXIT

need_cmd bash "Install bash."
need_cmd curl "Install curl."
need_cmd uv "Install uv as documented in docs/development.md."

if [[ -n "${REMANENCE_JAVA_HOME:-}" ]]; then
  JAVA_HOME="${REMANENCE_JAVA_HOME}"
elif [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
fi
if [[ ! -x "${JAVA_HOME}/bin/java" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
  die "JDK 17 not found at ${JAVA_HOME} (expected bin/java and bin/javac). Set REMANENCE_JAVA_HOME or JAVA_HOME."
fi
java_version="$("${JAVA_HOME}/bin/java" -version 2>&1)"
case "${java_version}" in
  *version\ \"17\"* | *version\ \"17.*) ;;
  *) die "JAVA_HOME must point at JDK 17 (checked ${JAVA_HOME}). Set REMANENCE_JAVA_HOME or JAVA_HOME." ;;
esac
export JAVA_HOME

if [[ -n "${REMANENCE_ANDROID_SDK_ROOT:-}" ]]; then
  android_sdk="${REMANENCE_ANDROID_SDK_ROOT}"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  android_sdk="${ANDROID_SDK_ROOT}"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  android_sdk="${ANDROID_HOME}"
else
  android_sdk="/usr/lib/android-sdk"
fi
if [[ ! -d "${android_sdk}" ]]; then
  die "Android SDK not found at ${android_sdk}. Set REMANENCE_ANDROID_SDK_ROOT, ANDROID_SDK_ROOT, or ANDROID_HOME."
fi
if [[ ! -d "${android_sdk}/platforms/android-36" || ! -f "${android_sdk}/platforms/android-36/android.jar" ]]; then
  die "Android SDK platform 36 is missing under ${android_sdk}/platforms/android-36. Install platforms;android-36 as documented in docs/development.md."
fi
if [[ ! -d "${android_sdk}/build-tools/36.0.0" ]]; then
  die "Android build-tools 36.0.0 are missing under ${android_sdk}/build-tools/36.0.0. Install build-tools;36.0.0 as documented in docs/development.md."
fi
ANDROID_HOME="${android_sdk}"
ANDROID_SDK_ROOT="${android_sdk}"
export ANDROID_HOME ANDROID_SDK_ROOT

if docker info >/dev/null 2>&1; then
  DOCKER=(docker)
elif sudo -n docker info >/dev/null 2>&1; then
  DOCKER=(sudo -n docker)
else
  die "Docker is not accessible. Enable docker for this user, or configure passwordless sudo -n docker; this script will not prompt for a password."
fi
COMPOSE=("${DOCKER[@]}" compose --project-directory "${REPO_ROOT}")
if ! "${COMPOSE[@]}" version >/dev/null 2>&1; then
  die "Docker Compose is not available via ${DOCKER[*]}. Install the Docker Compose plugin."
fi

REMANENCE_DEV_DB_PASSWORD="${REMANENCE_DEV_DB_PASSWORD:-remanence-dev-only}"
REMANENCE_DB_PORT="${REMANENCE_DB_PORT:-55432}"
REMANENCE_API_PORT="${REMANENCE_API_PORT:-8000}"
export REMANENCE_DEV_DB_PASSWORD REMANENCE_DB_PORT REMANENCE_API_PORT

# Serialized VPS-safe Gradle heap cap: Kotlin compiles in-process, so the
# single-use build JVM is the only Kotlin/Gradle JVM this gate permits
# (plus Gradle's default-capped test workers). The value looks like 1024m
# or 2g; anything else fails closed before Gradle starts.
REMANENCE_GRADLE_JVM_HEAP="${REMANENCE_GRADLE_JVM_HEAP:-1024m}"
if [[ ! "${REMANENCE_GRADLE_JVM_HEAP}" =~ ^[0-9]+[mMgG]$ ]]; then
  die "REMANENCE_GRADLE_JVM_HEAP must look like 1024m or 2g, got '${REMANENCE_GRADLE_JVM_HEAP}'."
fi
export REMANENCE_GRADLE_JVM_HEAP

if [[ -z "${REMANENCE_TEST_DATABASE_URL:-}" ]]; then
  if [[ ! "${REMANENCE_DEV_DB_PASSWORD}" =~ ^[A-Za-z0-9._~-]+$ ]]; then
    die "REMANENCE_DEV_DB_PASSWORD contains URL-significant characters; set REMANENCE_TEST_DATABASE_URL explicitly instead of constructing a URL."
  fi
  REMANENCE_TEST_DATABASE_URL="postgresql+psycopg://remanence:${REMANENCE_DEV_DB_PASSWORD}@127.0.0.1:${REMANENCE_DB_PORT}/remanence"
fi
export REMANENCE_TEST_DATABASE_URL
REMANENCE_TEST_API_BASE_URL="http://127.0.0.1:${REMANENCE_API_PORT}/"
export REMANENCE_TEST_API_BASE_URL

wait_healthy() {
  local service="$1"
  local tries="${2:-90}"
  local i health
  for ((i = 1; i <= tries; i++)); do
    health="$("${COMPOSE[@]}" ps --format '{{.Health}}' "${service}" 2>/dev/null || true)"
    health="${health//$'\r'/}"
    health="${health%%$'\n'*}"
    if [[ "${health}" == "healthy" ]]; then
      log "${service} is healthy"
      return 0
    fi
    sleep 1
  done
  die "${service} did not become healthy within ${tries}s"
}

log "validating compose config"
"${COMPOSE[@]}" config --quiet

log "starting postgres"
"${COMPOSE[@]}" up -d --build postgres
wait_healthy postgres

log "building api image for migrate"
"${COMPOSE[@]}" build api

log "running migrate one-shot"
"${COMPOSE[@]}" up --no-deps --force-recreate --abort-on-container-exit --exit-code-from migrate migrate
migrate_exit="$("${COMPOSE[@]}" ps -a --format '{{.ExitCode}}' migrate)"
migrate_exit="${migrate_exit//$'\r'/}"
migrate_exit="${migrate_exit%%$'\n'*}"
[[ "${migrate_exit}" == "0" ]] || die "migrate service did not exit 0"

log "starting api"
"${COMPOSE[@]}" up -d --build api
wait_healthy api

log "checking /healthz"
health_body="$(curl -fsS -H 'Accept: application/json' "http://127.0.0.1:${REMANENCE_API_PORT}/healthz")"
[[ "${health_body}" == '{"status":"ok"}' ]] || die "/healthz did not return exactly {\"status\":\"ok\"}"

log "checking alembic_version"
alembic_version="$("${COMPOSE[@]}" exec -T postgres psql -U remanence -d remanence -tAc "SELECT version_num FROM alembic_version;")"
alembic_version="${alembic_version//$'\r'/}"
alembic_version="${alembic_version%%$'\n'*}"
[[ "${alembic_version}" == "0003_m2_capsule_routing" ]] || die "alembic_version is not 0003_m2_capsule_routing"

log "checking api uid/gid"
api_uid="$("${COMPOSE[@]}" exec -T api id -u)"
api_gid="$("${COMPOSE[@]}" exec -T api id -g)"
api_uid="${api_uid//$'\r'/}"
api_gid="${api_gid//$'\r'/}"
api_uid="${api_uid%%$'\n'*}"
api_gid="${api_gid%%$'\n'*}"
[[ "${api_uid}" == "10001" && "${api_gid}" == "10001" ]] || die "api is not running as uid/gid 10001"

log "probing blob volume mode 0600"
"${COMPOSE[@]}" exec -T api sh -c "
set -eu
rm -f '${PROBE_PATH}'
umask 077
touch '${PROBE_PATH}'
chmod 0600 '${PROBE_PATH}'
mode=\$(stat -c %a '${PROBE_PATH}')
rm -f '${PROBE_PATH}'
[ \"\$mode\" = 600 ]
"

log "server lock, sync, and pytest"
(
  cd "${REPO_ROOT}/server"
  uv lock --check
  uv sync --locked
  uv run --locked pytest -q -W error
)

log "android unit tests and assembleDebug (gradle heap ${REMANENCE_GRADLE_JVM_HEAP}, kotlin in-process)"
(
  cd "${REPO_ROOT}/android"
  ./gradlew clean testDebugUnitTest assembleDebug --console=plain \
    --no-daemon --max-workers=1 \
    "-Dorg.gradle.jvmargs=-Xmx${REMANENCE_GRADLE_JVM_HEAP} -Dfile.encoding=UTF-8" \
    "-Dkotlin.compiler.execution.strategy=in-process"
)

apk_path="${REPO_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "${apk_path}" ]] || die "debug APK is missing or empty at android/app/build/outputs/apk/debug/app-debug.apk"
apk_bytes="$(stat -c %s "${apk_path}")"

log "git diff --check"
git -C "${REPO_ROOT}" diff --check

log "M0 verify PASS"
log "postgres healthy; migrate exited 0; api healthy uid/gid 10001"
log "healthz {\"status\":\"ok\"}; alembic 0003_m2_capsule_routing; blob probe mode 0600"
log "apk ${apk_bytes} bytes; git diff --check clean"
log "device/camera/CV not validated (adb not run)"
