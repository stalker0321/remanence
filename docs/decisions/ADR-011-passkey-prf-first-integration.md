# ADR-011 — passkey PRF first product integration

2026-10-10. IMPLEMENTATION SLICE, not production/M4 acceptance.

Owner approved integration after reporting Google-backed PRF fixture unwrap in
Ubuntu Chrome, including with the source phone off. Native source/platform
coverage and fresh provider-vault restoration remain separate evidence.
ADR-010 remains the cryptographic foundation. This slice supersedes the old
Block Store adapter selection, not the device enrollment/security release gates
in R0/RV-02. Old probes/evidence/provider entry remain untouched.

## First usable slice

Existing email/password registration creates the SAME random HPKE/Ed25519
identity. An authenticated owner with that private identity explicitly adds a
new discoverable passkey. Registration is followed by assertion + local PRF
evaluation. Registration alone never establishes recoverability.

A random 32-byte ARK encrypts a binary package of the active private Tink
keysets. PRF -> HKDF-SHA256 -> AES-256-GCM protects the ARK. A separate random
32-byte recovery code gets its own domain-separated wrapper. The code is shown
only locally, never transmitted, logged, persisted or stored in a clipboard by
the app. Code recovery requires existing account password authentication.

The complete encrypted record is signed by the EXISTING Tink Ed25519 identity;
server verifies it against the active account public bundle. Passkey-only login
returns a normal session plus encrypted recovery record/public anchors. Clients
must validate/decrypt the package BEFORE publishing a crypto-ready session.
No plaintext ARK, PRF, private keysets or code are accepted by API schemas.

## V1 byte contract

UUIDs are canonical lowercase strings. Base64url is unpadded and canonical.
Global public PRF input = SHA256(UTF8(`remanence/recovery/prf-input/v1`)). It is
not secret; each credential has an independent PRF. HKDF uses a fresh 32-byte
record salt and context/AAD = UTF8 of newline-separated:
`REMANENCE-RECOVERY-V1`, account UUID, bundle UUID, `1`, credential ID, salt.
Append `\npackage`, `\nprf-ark` or `\ncode-ark` for independent purposes. AES-GCM
sealed values are nonce(12) || ciphertext || tag(16), with fresh random nonce.

Package plaintext: ASCII `RPK1` || u32be encryption-keyset length || keyset ||
u32be signing-keyset length || keyset; no trailing bytes. Both parsed private
keysets must export public bytes EXACTLY matching server public anchors. Bundle
UUID must also equal Java UUID.nameUUIDFromBytes(encryption public keyset).
Native restoration additionally proves HPKE encrypt/decrypt and Ed25519
sign/verify correspondence; browser proves Ed25519 and X25519 correspondence.

Record signature: Tink Ed25519 signature over
UTF8(context + `\nmanifest`) || SHA256(package sealed bytes) ||
SHA256(PRF ARK sealed bytes) || SHA256(code ARK sealed bytes).
User/bundle/credential/salt/version/generation and both wrappers are bound.

## Storage / lifecycle

Backend: public WebAuthn key/counter, account binding, short-lived one-use
challenge, encrypted record, identity signature, existing public keysets.
Device: restored private keysets ONLY wrapped with its existing device KEK;
ARK/PRF/code buffers exist temporarily. Browser: decrypted keysets in memory
only for validation; no localStorage, downloads, persistent session or gallery.
Google/provider: passkey credential/PRF capability, not Remanence private keys.

One immutable generation/credential per account initially. Identical retry
converges; replacement/rotation/removal is refused, not silently overwritten.
Local installation refuses another existing identity, atomically publishes one
combined wrapped recovery identity record, and never regenerates missing keys.
Legacy two-file identity remains readable and untouched.

## Explicit limits / release gate

Server API/UI are disabled by default and cannot be enabled in PROD. Android
release hard-disables the feature; debug requires explicit Gradle opt-in.
Enabled debug uses a separate `.recoverytest` application ID to preserve the
normal installed app. See `docs/recovery-passkey-integration.md` for staging.
No live DB migration or existing deployment is authorized by this slice.
No new browser product/gallery is claimed. No two-active-device enforcement,
approval/replacement, revocation/purge, retired key history, generation rotation,
provider migration, or rollback resistance against a fully malicious server
with no previously pinned anchor is claimed. Those remain required M4 work.
Web server compromise can serve malicious JS and steal unlocked plaintext;
DB disclosure alone exposes ciphertext/public metadata, not unwrap secrets.
Mutable buffers are cleared best-effort; managed strings, Tink handles, WebCrypto
and provider copies are not guaranteed zeroized. All methods lost = data lost.
