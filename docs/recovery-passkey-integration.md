# Passkey/PRF recovery — first product slice

2026-10-10. Opt-in DEV/TEST implementation; not a production rollout.
Cryptographic contract and release limits: [ADR-011](decisions/ADR-011-passkey-prf-first-integration.md).

The newer Android onboarding preparation is defined in
[ADR-019](decisions/ADR-019-google-onboarding-mvp.md) and the
[morning runbook](android-mvp-morning-runbook.md). In that enabled test profile,
Google authenticates the account independently of its passkey provider; a new
account cannot enter Create/Scan until actual PRF/readback recovery succeeds.
Pending/stored enrollment resumes without replacing the identity or record.
The recovery code is optional and hidden until requested, not a required
registration step. A signed-in Google account can restore with its existing
passkey or previously saved code; Google login alone cannot decrypt anything.
The original password-only acceptance below remains a legacy test scenario,
not the new onboarding specification. These changes are not yet deployed.

## What works in this slice

Android owners with an existing account/private identity can select **Protect
memories with a passkey**. Credential Manager creates a discoverable credential,
then evaluates PRF in a verified assertion. The client encrypts the existing
HPKE + Ed25519 identity, signs the opaque recovery record, uploads it and unwraps
the actual server readback. Only then does it display the manual recovery code.

A clean test installation offers **Sign in and restore with a passkey**. It
authenticates, validates and decrypts the existing identity, installs it wrapped
under the new device KEK, then publishes the product session. Missing PRF fails
before a login session is requested. Existing local identity material is never
silently replaced, including incomplete/corrupt material.

Alternatively, sign in with the existing email/password and use **Restore with
recovery code** on the recovery-required screen. A password alone cannot unwrap
the keys. This fallback works without a passkey provider; passkey actions require
Android 9/API 28 or newer. The app's existing minimum remains API 26.

`/recovery-test/` is a read-only browser identity-unlock test, not the full web
product. It verifies the signed record and actual private/public key correspondence
using WebCrypto, then discards secrets and revokes its temporary product session.
Both passkey and password + recovery-code paths are available. There is no web
enrollment UI, gallery or persistent browser key storage in this slice.

## Isolated development configuration

Do not migrate the existing hosted database or use an existing real account for
initial acceptance. Provision a separate DEV database/blob root, then run from
`server/` with dependencies installed using `uv sync --locked`:

```sh
# Set REMANENCE_DATABASE_URL and REMANENCE_BLOB_ROOT to isolated test resources.
export REMANENCE_MODE=dev
export REMANENCE_RECOVERY_ENABLED=true
export REMANENCE_RECOVERY_RP_ID=localhost
export REMANENCE_RECOVERY_ORIGINS='["http://localhost:8000"]'
uv run alembic upgrade head
uv run uvicorn remanence.main:create_app --factory --host 127.0.0.1 --port 8000
```

The localhost configuration is for browser/virtual-authenticator development,
not a native Credential Manager deployment. Serve all `/v1/` endpoints and
`/recovery-test/` from the same origin. A proxy path prefix is supported if the
prefix is stripped before forwarding to this service. Never reuse the old
synthetic `/keys-test/` credential: this enrollment stores a real product identity.

Build Android from `android/` using the shared build lock:

```sh
flock -w 60 /tmp/remanence-gradle.lock ./gradlew :app:assembleDebug \
  -Premanence.recovery.enabled=true \
  -Premanence.apiBaseUrl=https://remanence.hryshyn.dev/TEST_PREFIX/ \
  --no-daemon --no-parallel --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process
```

`TEST_PREFIX` is a placeholder, not a deployed URL. Enabled debug builds use
`dev.hryshyn.remanence.recoverytest`, so they cannot replace the normal installed
Remanence package. Default debug and every release build keep recovery disabled.
The locally preserved APK uses the default loopback API URL; it is a build
artifact, **not a hosted/phone-ready release**.

## Native staging requirements — not deployed by this change

Use the existing `remanence.hryshyn.dev` RP with a separately routed test service
and isolated database. Browser origin is `https://remanence.hryshyn.dev` (no path).
Native origin must exactly match the actual APK signer. For the currently checked
canonical debug certificate:

```text
SHA-256: 2C:B4:05:56:C1:8C:BA:21:81:E1:D7:78:F5:05:D2:21:86:97:F8:72:E2:CB:63:B9:2E:BF:6D:8A:89:42:D8:72
origin: android:apk-key-hash:LLQFVsGMuiGB4dd49QXSIYaX-HLiy2O5Lr9tiolC2HI
```

The service origin allowlist must contain that Android origin plus the HTTPS
browser origin. Add an **additional** Digital Asset Links association for
`dev.hryshyn.remanence.recoverytest` and this signer at
`https://remanence.hryshyn.dev/.well-known/assetlinks.json`, using
both `delegate_permission/common.get_login_creds` and
`delegate_permission/common.handle_all_urls`; preserve existing associations.
The debug manifest already references that statement list, following the
[official Credential Manager prerequisites](https://developer.android.com/identity/credential-manager/prerequisites).
Do not assume debug, probe, Play app-signing and upload certificates are equal.

After staging is explicitly provisioned, acceptance uses only disposable test
accounts in the new package: create an account and encrypted content; protect
identity; save code externally; restore into a clean installation/other device;
open the SAME old ciphertext; restart and repeat. Test browser unlock with the
source phone unavailable, and code recovery with the provider unavailable.
Inspect failures for wrong account/code, unsupported PRF and tampering. These
phone steps have **not** been executed by the agent; the Windows QA host was
offline. Do not clear/uninstall the normal app or touch the Block Store probe.

## Verification and remaining product gates

Automated coverage includes Kotlin identity round-trip/old HPKE ciphertext,
Ed25519 signing, wrong secrets/bindings/tampering, atomic local installation,
session fencing, bounded transport, server WebAuthn signatures/UV/origin/replay,
account/session isolation, immutable records and concurrent one-use challenges.
The live local HTTP/browser test uses Chromium's **virtual authenticator**,
not Google Password Manager sync. WebCrypto interoperability uses only synthetic
fixtures produced by the Kotlin test. Real provider/native acceptance is separate.

Initially there is one immutable generation/credential per account. Re-enrollment,
rotation and deletion are refused. Cancelled enrollment can leave a provider
credential and an incomplete server enrollment, bounded to four attempts. If
upload succeeds but the response/readback is interrupted, the passkey may already
be usable while the code has not been shown; there is no code reissue UI yet.
Credential creation alone is never presented as a completed backup.

Production still requires independent security review, native/provider evidence,
device approval/replacement policy, credential revocation/purge, recovery-code
reissue/rotation and lifecycle UX. No server plaintext-key recovery, automatic
password reset, capsule format migration or identity regeneration is introduced.
Losing every usable provider credential and the independent code loses the data.
