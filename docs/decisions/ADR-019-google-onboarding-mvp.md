# ADR-019 — Android MVP onboarding preparation

2026-10-10. Owner-approved direction; gated DEV/TEST implementation, not release
or native-provider acceptance. Extends ADR-011; no new capsule cryptography.

## Product decisions

- Android MVP first. No PWA or native iOS implementation in this slice.
- Google account sign-in and passkey storage are independent. A Google-authenticated
  account can use Bitwarden or any supported PRF provider; the provider selection
  belongs to Credential Manager, not Remanence's Google account selection.
- Sign in/sign up -> permanent unique handle for a new account -> save and verify
  encrypted-key recovery. Registration survives interruption; Create/Scan and
  background account work stay blocked until recovery is verified.
- A recovery code is optional and hidden behind an explicit reveal; continuing
  never requires claiming to have saved it. Lost all credentials plus no code
  still means lost encrypted data; no server plaintext-key fallback.
- Sign in with Apple is deferred. Google sign-in on future iOS/web remains a target.
  Apple Passwords/iCloud passkey storage is not Sign in with Apple.
- `remanence.to/<handle>` is a future recipient-addressed Create entry, not a
  capsule access link. No domain purchase/configuration or deep-link route now.

## Google protocol

Explicit DEV/TEST flags and exact OAuth audience/presenter allowlists. Server
creates an expiring random nonce; native Credential Manager returns a Google ID
token carrying it. Google's library verifies signature/certs, issuer, expiry and
audience. We require verified email, exact nonce and permitted `azp`. Immutable
Google `sub` binds the account; email is never the binding key. A matching email
on an unlinked legacy account is rejected; explicit account linking is deferred.

Existing Google identity -> ordinary Remanence session, never fresh private keys.
New Google identity -> short-lived registration ticket -> locally prepared
Keystore-wrapped HPKE/Ed25519 identity + handle -> atomic account/provider/public
bundle/session registration. No fabricated password credential is created.
Handle collision retains the ticket for correction; successful registration
consumes it. Concurrent registration has at most one winner.

Endpoints under `/v1/auth/google/`: POST `options`, `verify`, `register`. No web
Google UI or OAuth redirect implementation is claimed. A future web/iOS Google
client can use the nonce-bound token protocol with configured client IDs.

## Recovery readiness and restart

`GET /v1/recovery/status` exposes only the authenticated owner's state and
credential ID. Pending setup resumes the same credential. A stored record is
authenticated and locally unwrapped, never replaced with a new ARK/identity.
An explicit alternative-manager attempt is allowed only before the immutable
record exists, retaining the existing four-attempt enrollment bound.

After actual server readback unwrap, persist a non-secret account+bundle-bound
local receipt. It is an honest-client readiness marker, NOT cryptographic proof
to a server or a protection against a modified/rooted client. Backup is disabled.
Cold start derives setup state from existing account, identity and receipt;
missing private keys always require explicit restore. Signed-in restore limits
the credential to that SAME account, preventing a Google login from silently
switching to another account through a discoverable passkey selection.

In recovery-enabled mode, missing-key bootstrap refreshes the session so an
authenticated restore can resume after process death. Setup-required state never
admits Create/Scan, settings, capsule grants or owner background jobs; offline
state cannot bypass setup. Completed coherent sessions retain existing offline
behavior. Legacy recovery-disabled behavior remains unchanged.

In this gated MVP profile `PATCH /v1/me/handle` rejects changes with
`HANDLE_IMMUTABLE`. Legacy recovery-disabled API compatibility remains; it is not
the new MVP contract and must not be enabled on its eventual production rollout.

## Settings and verification boundary

Working settings: permanent handle, existing EN/RU/UK/system language control,
verified recovery status, privacy explanation, build version, logout. Language
is removed from authenticated Home, retained on pre-auth/setup surfaces. No
decorative cache deletion, account deletion, passkey rotation or device controls.

Code/default gates remain OFF; release forces OFF; enabled APK stays in the
separate `.recoverytest` package. Server rejects Google onboarding in PROD and
without recovery. OAuth project/client setup, hosted isolated DEV deployment/DAL,
real Google/Bitwarden UI, synced-new-device unwrap, iOS/browser matrix and M4
lifecycle/security review are not replaced by local tests.

## Music preparation boundary

`music_catalog_prepare.py` discovers the official core snapshot, performs a
read-only explicit disk-budget preflight, and optionally downloads/resumes with
exact size/SHA256 validation. No full download was authorized on this host.
This is NOT the full-dump disk-backed join, normalized database builder,
Meilisearch index builder, scheduler or atomic revision manager. Those remain
the next music implementation after target-machine disk/RAM measurements. Do
not point the sample importer at the full dump and call it a production catalog.

## Sources

- https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
- https://developers.google.com/identity/gsi/web/guides/verify-google-id-token
- https://developer.android.com/identity/passkeys/create-passkeys
- https://musicbrainz.org/doc/MusicBrainz_Database/Download
- https://musicbrainz.org/doc/About/Data_License
