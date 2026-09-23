# RV-01 Google capability probe (throwaway)

This is a deliberately separate, throwaway Android app for automatable REC-01
probe preparation. It is not included by `android/settings.gradle.kts`, is not
part of a production or release artifact, has no backend or network permission,
and must never receive Remanence ARK material, user data, production OAuth
credentials, or provider secrets.

The app wires the real `com.google.android.gms:play-services-auth-blockstore:16.4.0`
bridge (`Blockstore.getClient`, `isEndToEndEncryptionAvailable`, one-key
exact-K_U store/retrieve/delete with tombstones) behind operator-confirmed P1
inputs: qualifying PIN/pattern/password lock kind (with `isDeviceSecure`
required), backup eligibility, and Backup Now completion. UNKNOWN stays
UNKNOWN unless the operator confirms it on the physical device. The screen
covers P1/P2/P3 plus the D1→D2 operator handoff (distinct P_D2 sealed under
an independent D2_TARGET context, SAF export, exact handoff copy line) and
the fresh-install D2 resume consumer (paste handoff, select P_D2, one
authenticated open of exactly 32 bytes, immediate zeroization). PRF remains
deferred: no Credential Manager/WebAuthn provider bridge exists here, and a
passkey or Google Password Manager record is never treated as PRF support.
Restore Credentials stays authentication/restore transport, not an ARK
unwrap capability:

* <https://developer.android.com/identity/block-store>
* <https://developer.android.com/identity/block-store/testing-restore-flows>
* <https://developer.android.com/identity/sign-in/restore-credentials>
* <https://developer.android.com/identity/credential-manager>
* <https://developers.google.com/identity/passkeys>
* <https://www.w3.org/TR/webauthn-3/#prf-extension>

Operator rehearsal order for the first A/G1 D1→D2 pass is documented in
[D1-D2-REHEARSAL-RUNBOOK.md](D1-D2-REHEARSAL-RUNBOOK.md). D1 cleanup deletes
the exact provider U slot and must wait until all D2 checks finish.

## Evidence status

Every debug APK and every local/emulator result from this harness remains
`LOCAL_DRY_RUN_NOT_EVIDENCE`; no physical device or provider capability
claim has been established. The Play-distributed, same-signed physical
device/account matrix (fresh install, restore-target install,
account-change, complete loss, Backup Now cloud restore) is still pending,
as are P4/P5/P6/P8 on hardware.

## Latest verified standalone gate (docs record, no new run)

Command (repository wrapper; this project stays out of production settings):

```text
env ANDROID_HOME=/usr/lib/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  android/gradlew -p tools/probes/rv01-android :app:testDebugUnitTest :app:assembleDebug \
  --no-daemon --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Result: BUILD SUCCESSFUL — 135 unit tests, 0 failures, 0 errors, 0 skips,
plus `:app:assembleDebug`. Debug APK built 2026-09-23 06:32 CEST:

```text
SHA256 6c1143e770367c6c1596ceb13f285e61d181ae5fa0ae7483319e649251b0fcb2
```
