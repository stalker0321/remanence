# Development toolchain

This document records the required M0 toolchain, the original VPS inventory, and host provisioning for JDK 17, Python 3.13, and the Android command-line SDK.

Android builds are CLI-only. Android Studio is optional and is not required for M0. Do not install Gradle globally; the project uses the Gradle wrapper.

## Required baseline

These versions are already chosen. Do not substitute.

| Tool | Required |
| --- | --- |
| JDK | 17 |
| Gradle | 9.4.1 |
| Android Gradle Plugin | 9.2.0 |
| Kotlin | 2.4.10 |
| Android `compileSdk` | 36 |
| Android `targetSdk` | 36 |
| Android `minSdk` | 26 |
| Android Build Tools | 36.0.0 |
| Android SDK extras | platform-tools and command-line tools |
| Python (project runtime) | 3.13, managed and pinned through uv |
| uv | current host uv is acceptable |
| Docker | Docker Engine plus Docker Compose plugin |

Compatibility sources already selected:

- AGP 9.2.0 release notes: <https://developer.android.com/build/releases/agp-9-2-0-release-notes>
- Android 16 SDK setup (`compileSdk` 36): <https://developer.android.com/about/versions/16/setup-sdk>
- Kotlin releases: <https://kotlinlang.org/docs/releases.html>

System Python on the host is not the project runtime. Project Python is 3.13 via uv.

## Original VPS inventory

Recorded before JDK/Python provisioning with the read-only commands below. Re-run the same commands to re-check.

| Item | Observed |
| --- | --- |
| `java` | absent |
| `javac` | absent |
| `JAVA_HOME` | unset |
| `adb` | absent |
| `sdkmanager` | absent |
| `ANDROID_HOME` | unset |
| `ANDROID_SDK_ROOT` | unset |
| system Python | 3.14.4 (not project runtime) |
| uv | 0.11.28 |
| Docker Engine | 29.7.2 |
| Docker Compose | 5.5.0 |

### Re-check commands

JDK:

```sh
command -v java
java -version
command -v javac
javac -version
printf 'JAVA_HOME=%s\n' "${JAVA_HOME-}"
```

Android SDK:

```sh
command -v adb
adb version
command -v sdkmanager
sdkmanager --version
printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME-}"
printf 'ANDROID_SDK_ROOT=%s\n' "${ANDROID_SDK_ROOT-}"
```

Python and uv:

```sh
python3 --version
uv --version
```

Docker:

```sh
docker --version
docker compose version
```

## Host provisioning

Noninteractive commands used on this host. Do not install Gradle globally; the project uses its wrapper.

```sh
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk-headless unzip
uv python install 3.13
```

## Verified after provisioning

Actual results on this host after the commands above.

| Item | Detected |
| --- | --- |
| `java -version` | `openjdk version "17.0.19" 2026-04-21` (`OpenJDK Runtime Environment (build 17.0.19+10-1-26.04.2-Ubuntu)`) |
| `java` path | `/usr/bin/java` → `/usr/lib/jvm/java-17-openjdk-amd64/bin/java` |
| `javac -version` | `javac 17.0.19` |
| `javac` path | `/usr/bin/javac` → `/usr/lib/jvm/java-17-openjdk-amd64/bin/javac` |
| JDK home | `/usr/lib/jvm/java-17-openjdk-amd64` (`JAVA_HOME` still unset) |
| `uv python find 3.13` | `/home/vodkolyan/.local/share/uv/python/cpython-3.13-linux-x86_64-gnu/bin/python3.13` → `/home/vodkolyan/.local/share/uv/python/cpython-3.13.14-linux-x86_64-gnu/bin/python3.13` |
| `uv run --python 3.13 python --version` | `Python 3.13.14` |
| `adb` / `sdkmanager` | still absent (before Android SDK provisioning) |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | still unset (before Android SDK provisioning) |

Verification commands:

```sh
java -version
javac -version
uv python find 3.13
uv run --python 3.13 python --version
```

## Android CLI SDK provisioning

Noninteractive commands used on this host. Do not install Android Studio, emulator/system images, NDK, CMake, Gradle, or extra platforms/build-tools.

```sh
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y google-android-cmdline-tools-19.0-installer
yes | sudo env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 sdkmanager --sdk_root=/usr/lib/android-sdk --licenses
sudo env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 sdkmanager --sdk_root=/usr/lib/android-sdk \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Build shells MUST set:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/usr/lib/android-sdk
export ANDROID_SDK_ROOT=/usr/lib/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$PATH"
```

The Ubuntu cmdline-tools package also pulled `google-android-build-tools-19.1.0-installer` and switched the default `java` alternative to OpenJDK 25. Keep using JDK 17 via `JAVA_HOME` for Remanence builds. Do not use Build-Tools 19.1.0.

## Verified after Android CLI SDK provisioning

Actual results on this host after the commands above.

| Item | Detected |
| --- | --- |
| `sdkmanager --version` | `19.0` (`/usr/bin/sdkmanager` → `/usr/lib/android-sdk/cmdline-tools/19.0/bin/sdkmanager`) |
| `adb version` | `Android Debug Bridge version 1.0.41`, `Version 37.0.1-15733141`, installed as `/usr/lib/android-sdk/platform-tools/adb` |
| `ANDROID_HOME` | `/usr/lib/android-sdk` (for build shells; not set in a login profile) |
| `ANDROID_SDK_ROOT` | `/usr/lib/android-sdk` (for build shells; not set in a login profile) |
| `platform-tools` | `37.0.1` at `/usr/lib/android-sdk/platform-tools` |
| `platforms;android-36` | version `2` at `/usr/lib/android-sdk/platforms/android-36` |
| `build-tools;36.0.0` | `36.0.0` at `/usr/lib/android-sdk/build-tools/36.0.0` |

`sdkmanager --sdk_root=/usr/lib/android-sdk --list_installed` also shows `cmdline-tools;19.0` from the Ubuntu installer and `build-tools;19.1.0` from that package’s apt dependency. The three packages requested through `sdkmanager` are `platform-tools`, `platforms;android-36`, and `build-tools;36.0.0`.

Verification commands:

```sh
sdkmanager --version
"$ANDROID_HOME/platform-tools/adb" version
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 sdkmanager --sdk_root=/usr/lib/android-sdk --list_installed
```

## Canonical M0 verification

`scripts/verify-m0.sh` is the one-command M0 check. It resolves the repo from its own path, so it can be run from any cwd:

```sh
./scripts/verify-m0.sh
/home/vodkolyan/projects/Remanence/scripts/verify-m0.sh
```

If your clone is not `/home/vodkolyan/projects/Remanence`, substitute that path. The script does not change the caller cwd permanently.

Documented defaults (override with `REMANENCE_*` as needed):

| Variable | Default |
| --- | --- |
| `REMANENCE_JAVA_HOME` / `JAVA_HOME` | `/usr/lib/jvm/java-17-openjdk-amd64` |
| `REMANENCE_ANDROID_SDK_ROOT` / `ANDROID_SDK_ROOT` / `ANDROID_HOME` | `/usr/lib/android-sdk` |
| `REMANENCE_DEV_DB_PASSWORD` | `remanence-dev-only` (local development only; never a production secret) |
| `REMANENCE_DB_PORT` | `55432` |
| `REMANENCE_API_PORT` | `8000` |
| `REMANENCE_TEST_DATABASE_URL` | constructed as `postgresql+psycopg://remanence:<password>@127.0.0.1:<db-port>/remanence` when unset |

If the password contains URL-significant characters, set `REMANENCE_TEST_DATABASE_URL` explicitly instead of letting the script construct it. Do not put production credentials in these variables or in shell history.

The script leaves Compose services and named volumes running. It never invokes `adb` and must not be treated as device, camera, or M1/M2 evidence.

A current PASS surface is:

- `docker compose config --quiet`
- postgres healthy; migrate exited `0`; api healthy as uid/gid `10001`
- `GET /healthz` exactly `{"status":"ok"}`
- `alembic_version` exactly `0003_m2_capsule_routing`
- blob volume create/stat/remove probe at mode `0600`
- `uv lock --check`, `uv sync --locked`, `uv run --locked pytest -q -W error`
- `./gradlew clean testDebugUnitTest assembleDebug --console=plain` with `REMANENCE_TEST_API_BASE_URL=http://127.0.0.1:$REMANENCE_API_PORT/`
- non-empty `android/app/build/outputs/apk/debug/app-debug.apk` (size printed; this host last printed `12287755` bytes)
- `git diff --check` clean
- explicit note that device/camera/CV were not validated

On this host a PASS also ran 82 pytest cases (the two database tests run when `REMANENCE_TEST_DATABASE_URL` is set) and 146 Gradle actionable tasks.

## Manual clean-shell verification

Commands below are copy/paste-correct from `/home/vodkolyan/projects/Remanence`. Substitute your clone path if different. Use `docker compose` when `docker info` works; on this VPS the login user is not in the `docker` group, so prefix Compose with `sudo -n` as the verifier does. `sudo -n` does not prompt; if it fails, fix Docker access instead of waiting for a password.

```sh
cd /home/vodkolyan/projects/Remanence

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/usr/lib/android-sdk
export ANDROID_SDK_ROOT=/usr/lib/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$PATH"

sudo -n docker compose config --quiet
sudo -n docker compose up --build -d
sudo -n docker compose ps -a
```

Wait until `postgres` and `api` are `healthy` and `migrate` has `Exited (0)`. Then:

```sh
curl -fsS -H 'Accept: application/json' http://127.0.0.1:8000/healthz
```

The body must be exactly `{"status":"ok"}`.

`remanence-dev-only` is a local-development-only Compose password. Do not replace it with a production secret.

```sh
cd /home/vodkolyan/projects/Remanence/server
uv lock --check
uv sync --locked
REMANENCE_TEST_DATABASE_URL=postgresql+psycopg://remanence:remanence-dev-only@127.0.0.1:55432/remanence \
  uv run --locked pytest -q -W error
```

```sh
cd /home/vodkolyan/projects/Remanence/android
REMANENCE_TEST_API_BASE_URL=http://127.0.0.1:8000/ \
  ./gradlew clean testDebugUnitTest assembleDebug --console=plain
```

```sh
stat -c '%s %n' /home/vodkolyan/projects/Remanence/android/app/build/outputs/apk/debug/app-debug.apk
```

The APK path must exist and be non-empty. Debug `applicationId` is `dev.hryshyn.remanence`. Debug `API_BASE_URL` defaults to `http://127.0.0.1:8000/` unless you pass `-Premanence.apiBaseUrl=...` (must end with `/`).

## Physical-device install (optional)

No physical Android device is currently attached to this VPS. The following path is for when one is available. It proves only the current M0 shell and loopback connectivity. It does not validate CameraX, CV, or M1/M2.

1. On the phone, enable Developer options and USB debugging.
2. Connect USB, unlock the device, and approve this host's RSA fingerprint.
3. Confirm `adb` sees state `device`, not `unauthorized` or `offline`:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/usr/lib/android-sdk
export ANDROID_SDK_ROOT=/usr/lib/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$PATH"
adb devices -l
```

4. Reverse the API port onto the device loopback. The debug client intentionally allows only exact loopback HTTP (`http://127.0.0.1:8000/` by default; production/main manifest is fail-closed for cleartext). Without reverse, the phone cannot reach the host API:

```sh
adb reverse tcp:8000 tcp:8000
adb install -r /home/vodkolyan/projects/Remanence/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.hryshyn.remanence/.MainActivity
```

Substitute the APK path if your clone is not `/home/vodkolyan/projects/Remanence`. Confirm Home shows `Architecture approved · API available` (backend health Available). Create and Scan stay disabled.

## Stopping Compose

Preserve named volumes (`remanence-postgres-data`, `remanence-blob-data`):

```sh
cd /home/vodkolyan/projects/Remanence
sudo -n docker compose stop
```

`docker compose down -v` is destructive: it removes the containers and the named volumes. Do not use it as a routine stop.

## Troubleshooting

- System `java` may be OpenJDK 25 after the Ubuntu cmdline-tools package. Always `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` (or `REMANENCE_JAVA_HOME`) before Gradle or the verifier.
- Builds need `ANDROID_HOME` / `ANDROID_SDK_ROOT` at `/usr/lib/android-sdk` with `platforms/android-36` and `build-tools/36.0.0`. Do not use Build-Tools 19.1.0.
- Docker: `docker info` first. If it fails, the verifier tries `sudo -n docker` and will not prompt. Configure passwordless `sudo -n docker` or a docker-group login; do not hang on a sudo password.
- Port collisions: host PostgreSQL is `127.0.0.1:55432`, API is `127.0.0.1:8000`. Override with `REMANENCE_DB_PORT` / `REMANENCE_API_PORT`. Keep the test URL, `-Premanence.apiBaseUrl=http://127.0.0.1:<api-port>/`, `REMANENCE_TEST_API_BASE_URL`, curl, and `adb reverse tcp:<api-port> tcp:<api-port>` on that same port. Base URLs must end with `/`.
- `adb devices -l` must show `device`. `unauthorized` means the phone has not approved this host; `offline` means reconnect/unlock USB debugging.
- Home `Architecture approved · API unavailable`: check `curl` `/healthz`, `docker compose ps -a`, and that `adb reverse` is still active for the debug API port.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a differently signed/versioned `dev.hryshyn.remanence` is already installed. `adb uninstall dev.hryshyn.remanence` deletes local app data on the device; only then is it an acceptable last resort.
- Never put production database passwords or other production secrets in Compose, `REMANENCE_TEST_DATABASE_URL`, or these examples. `remanence-dev-only` is local-development-only.
