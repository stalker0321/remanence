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

Noninteractive commands used on this host. Do not install Gradle globally. Do not install the Android SDK here.

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

The Ubuntu cmdline-tools package also pulled `google-android-build-tools-19.1.0-installer` and switched the default `java` alternative to OpenJDK 25. Keep using JDK 17 via `JAVA_HOME` for Postmark builds. Do not use Build-Tools 19.1.0.

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
