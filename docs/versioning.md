# Release and versioning

Remanence uses one product SemVer identity across a coordinated release. The
Android release identity is maintained in `android/gradle.properties`:
`remanence.productVersion` is the product version and
`remanence.androidVersionCode` is the Android installer code. The app build
consumes both values; do not duplicate them in `android/app/build.gradle.kts`.

## Product progression

The SIFT/RootSIFT integrated-test sequence is:

```text
0.2.0-sift-it.1  →  0.2.0-sift-it.2  →  0.2.0-sift-it.3  →  0.2.0-sift-it.4  →  0.2.0-sift-it.5  →  0.2.0-sift-it.6  →  0.2.0-rc.1  →  0.2.0
```

`sift-it.N` identifies an installable integrated-test candidate. `rc.N` is a
release candidate. `0.2.0` is the accepted pre-public product release; the
first public release remains gated by the documented recovery and acceptance
requirements. Milestones such as M2, M3, and P3A belong in the changelog, not
in the product version.

Every distributed Android artifact receives a new monotonically increasing
`versionCode`. The current historical preview consumed code 11; SIFT candidates
consumed codes 12, 13, 14, 15, 16, and 17 in order. A consumed code is never reused, including
for a corrected or rollback build.

Product and wire versions are independent. The SIFT candidate records the
following separately:

```text
product:             0.2.0-sift-it.6
Android code:        17
REST API:            /v1
outer protobuf:      remanence.protocol.v1
recognition schema:  remanence.recognition.v2
fingerprint format:  3
profile:             postcard-sift-rootsift-v1
```

The inner recognition change does not silently change the outer API or crypto
protocol. A wire-affecting outer change requires its own protocol/schema
decision and compatibility evidence.

## Tags and artifacts

Release tags use the exact product version with a `v` prefix:
`v0.2.0-sift-it.1`, `v0.2.0-sift-it.2`, `v0.2.0-sift-it.3`,
`v0.2.0-sift-it.4`, `v0.2.0-sift-it.5`, `v0.2.0-sift-it.6`, `v0.2.0-rc.1`, and `v0.2.0`. Tags point to the exact clean
release commit. Existing tags are historical and must never be moved, deleted,
or rewritten.

Published Android artifacts use the version, Android code, and release commit:

```text
Remanence-android-v0.2.0-sift-it.6-code17-g<git-sha>-debug.apk
```

Record the matching SHA-256, backend image/digest, protocol and schema
versions, database migration head, configuration, and test evidence beside
the artifact. The ordinary `app-debug.apk` path is only a local Gradle output.

## Changelog and rollback mapping

Each changelog entry maps product version → Android code → Git tag/commit →
artifact hash → backend image/digest → protocol/schema versions → database
head. The current SIFT entry is in [`CHANGELOG.md`](../CHANGELOG.md).

Rollback selects that recorded artifact and server image; it does not decrement
an Android code. A rollback APK that must update an already-installed newer
APK receives a new higher code. P3A is a clean SIFT replacement with no ORB
compatibility reader or migration, so reverting to the code-11 ORB baseline
requires the documented clean recognition-state reset and must not mix ORB and
SIFT local/server recognition data.
