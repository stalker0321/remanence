# RV-01 Google capability probe (throwaway)

This is a deliberately separate, throwaway Android app for automatable REC-01
probe preparation. It is not included by `android/settings.gradle.kts`, is not
part of a production or release artifact, has no backend or network permission,
and must never receive Remanence ARK material, user data, production OAuth
credentials, or provider secrets.

The local screen and fake backends exercise only P1-P3 and the exact retrieved
object local-authenticated-package tamper shape for P7. Their output is marked
`LOCAL_DRY_RUN_NOT_EVIDENCE`; it is not RV-01 evidence and does not establish a
Google capability or a recovery GO decision. The fake backend is the only
available backend in this skeleton. The named Block Store and PRF adapters
return unavailable until a real, separately reviewed provider bridge reports an
actual capability. They never turn a fake result into provider success.

Each run creates a random 32-byte canary and 32-byte unwrap material in memory.
The authenticated test package binds version and fixed probe context as AAD,
and P7 flips a byte in the exact opaque package returned by retrieval before
asking the adapter to unwrap it. No raw package, canary, unwrap material,
provider subject, email, token, or exception is emitted in evidence JSON.

## Deferred provider assumptions

No Google SDK is wired here intentionally. A future Block Store bridge would be
reviewed against the official `com.google.android.gms:play-services-auth-blockstore`
API, including `Blockstore.getClient`, `isEndToEndEncryptionAvailable`, cloud
backup eligibility, and the documented Backup Now/restore test flow:

* <https://developer.android.com/identity/block-store>
* <https://developer.android.com/identity/block-store/testing-restore-flows>

A future PRF bridge must use an actual Credential Manager/WebAuthn provider
capability and a newly returned stable PRF output. Google Password Manager or a
passkey being present is not evidence that PRF is available. Restore Credentials
is an authentication/restore transport and is not an ARK unwrap capability:

* <https://developer.android.com/identity/sign-in/restore-credentials>
* <https://developer.android.com/identity/credential-manager>
* <https://developers.google.com/identity/passkeys>
* <https://www.w3.org/TR/webauthn-3/#prf-extension>

Physical P4/P5/P6/P8 work remains outside this app's local test harness. It
requires a Play-distributed throwaway app, fresh devices/accounts as specified
by RV-01, and explicit screen-lock/E2EE, sync/backup-eligibility, identity
isolation, and rollback/tamper evidence. D2D is optional extra coverage only
when the candidate explicitly claims D2D support; P5-D2D is then recorded in
addition to the applicable cloud/provider-sync path. D2D alone never satisfies
the mandatory baseline, which still requires the applicable cloud/provider-sync
path.

## Future local checks

The project has no checked-in Gradle wrapper. Once an Android build is
authorized, use the repository wrapper without adding this project to the
production settings:

```text
android/gradlew -p tools/probes/rv01-android :app:testDebugUnitTest :app:assembleDebug \
  --no-daemon --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

The local standalone gate has completed successfully: 5/5 unit tests passed
and `:app:assembleDebug` produced the debug APK. This artifact and every
result from the local/emulator harness remain
`LOCAL_DRY_RUN_NOT_EVIDENCE`; no physical device or provider capability claim
has been established.
