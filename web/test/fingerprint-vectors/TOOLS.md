# T308 — genuine format-3 fingerprint vector provenance (TEST-ONLY)

`fingerprint-vectors.json` is emitted by executing the **UNCHANGED actual Kotlin**
codec/domain with the committed schema — not by a TypeScript mirror. Reproduce:

```
web/test/fingerprint-vectors/generate.sh <TOOLS_DIR> <PRODUCT_REPO_ROOT> <OUT_JSON>
```

It compiles `recognition_v2.proto` with `protoc --java_out=lite`, `javac`s the
generated lite classes, compiles the two Kotlin files + `GenerateVectors.kt` with
the Kotlin compiler, and runs the generator (which serializes each domain value,
`parse`s its own bytes, re-serializes, and asserts canonical equality). No Gradle,
no AVD, no downloads/installs.

## Input source hashes (from the protected product repo, byte-unchanged)

| File | SHA-256 |
| --- | --- |
| `protocol/proto/remanence/recognition/v2/recognition_v2.proto` | `f4ecfafcb9301a32af57631dc89a769bb1c529952f0b103e69a9e45d67984b85` |
| `.../core/model/SiftRootSiftFingerprint.kt` | `19c1ed4b4ecb914404c484d92dda00e8532d0edfe964e6664b83437c34b22d1d` |
| `.../core/model/SiftRootSiftFingerprintCodec.kt` | `58440cc5e4f5af97853d4000785445d19fd95d81baf8afb62372a6f86fa0e078` |
| `web/test/fingerprint-vectors/GenerateVectors.kt` (this repo) | `0f39b95de481e92655789f93c4b062143a9b1ff4496185de9c37accd9539ccbf` |
| `web/test/fingerprint-vectors/fingerprint-vectors.json` | `bb7abcea3dd4814c662d718ac52367172cd5d5fc88950c8a74bc38edbe4c5635` |

## Pinned tool artifacts (from the existing local Gradle cache; `tools.MANIFEST.sha256`)

Versions: Kotlin compiler `2.2.10`; protobuf-javalite `4.35.0`; protoc `4.35.0`;
kotlinx-coroutines-core-jvm `1.8.0`; org.jetbrains annotations `23.0.0`; Java 17+.

The `TOOLS_DIR` must contain the named jars (`kotlin-compiler-embeddable.jar`,
`kotlin-stdlib.jar`, `kotlin-reflect.jar`, `kotlin-script-runtime.jar`,
`kotlin-daemon-embeddable.jar`, `kotlinx-coroutines-core-jvm.jar`,
`annotations.jar`, `protobuf-javalite.jar`) and the executable `protoc`; their
SHA-256 manifest is committed as `tools.MANIFEST.sha256`.
