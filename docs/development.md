# Development toolchain

This document records the required M0 toolchain and the observed VPS inventory. It does not install or configure anything.

Android builds are CLI-only. Android Studio is optional and is not required for M0.

Missing JDK and Android SDK tooling on this host is a known M0 provisioning step, not an architecture blocker.

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

## Observed VPS inventory

Recorded on this host with the read-only commands below. Re-run the same commands to re-check.

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
