# Development toolchain

This document records the required M0 toolchain, the original VPS inventory, and host provisioning for JDK 17 and Python 3.13.

Android builds are CLI-only. Android Studio is optional and is not required for M0.

Android SDK tooling is still missing on this host. That remains a known M0 provisioning step, not an architecture blocker. Do not install Gradle globally; the project uses the Gradle wrapper.

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
| `adb` / `sdkmanager` | still absent |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | still unset |

Verification commands:

```sh
java -version
javac -version
uv python find 3.13
uv run --python 3.13 python --version
```

Android SDK remains missing for the next provisioning task.
