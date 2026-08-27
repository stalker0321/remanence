# Security architecture

Status: **APPROVED for implementation; M1 must produce golden vectors before crypto is considered verified.**

This document defines what the MVP protects, what it intentionally does not protect, and the exact cryptographic/key-lifecycle design. Cryptographic operations use established library primitives; application code must not implement curves, KDFs, ciphers, padding, or signature algorithms.

## 1. Primary security objective

Compromise of PostgreSQL, object storage, their backups, or a read-only snapshot of both must not disclose:

- capsule photos or note;
- optional track/place/chooser metadata;
- raw postcard scans;
- plaintext recognition descriptors/keypoints;
- capsule symmetric keysets;
- account private encryption/signing keys.

Integrity failure must fail closed: the client presents no partial plaintext and does not create a recipient fingerprint from unauthenticated material.

## 2. Security claims and non-claims

### Claimed

- Capsule content is encrypted on the sender device for the confirmed recipient account key.
- The server stores only recipient-addressed envelopes and ciphertext artifacts.
- AEAD binds every encrypted artifact to its capsule/blob identity and role.
- A sender signature binds the complete published ciphertext set to the sender signing key known by the directory.
- Android private identity keysets are encrypted at rest by a non-exportable Keystore key.
- Password authentication and content-encryption identity are independent.
- Raw front/back images are not uploaded, even encrypted, in MVP because matching does not require them.

### Not claimed

- The postcard is not a high-entropy key and does not cryptographically gate decryption.
- A rooted/modified client or attacker controlling an unlocked process is not prevented from invoking keys or bypassing the scan UI.
- E2EE does not hide sender/recipient routing, object sizes, timestamps, IP addresses, or access patterns.
- Without key transparency or an out-of-band key verification ceremony, a malicious live directory can substitute a recipient public key at lookup time or a sender verification key at first contact.
- The application cannot prevent recipients from photographing, screenshotting, or otherwise copying content they can legitimately view.
- Availability after server deletion, account disablement, unrecoverable device loss, or object corruption is not guaranteed unless a tested recovery/backup mechanism exists.

## 3. Threat actors

| Actor | In scope | Expected protection |
| --- | --- | --- |
| Database/object-storage reader | Yes | No capsule plaintext, descriptors, or private/capsule keys. |
| Database/object-storage writer | Yes | Tampering detected by hashes, signed statement, HPKE context, and AEAD AAD. Availability may be lost. |
| Network observer | Yes | TLS protects transport metadata/content beyond normal endpoint visibility; E2EE still protects stored content. |
| Wrong resolved recipient | Yes | Explicit confirmation plus immutable IDs/key IDs; creation blocks before confirmation. |
| Another authenticated user | Yes | Server authorization prevents listing/downloading unrelated capsules; E2EE prevents decryption. |
| Person handling physical mail | Yes | Postcard imagery alone cannot derive capsule keys. They may read the physical back. |
| Lost locked phone, storage extraction | Partly | Keystore-wrapped keys and app-private ciphertext resist ordinary extraction. Device security level is recorded diagnostically. |
| Rooted phone/malicious OS/unlocked attacker | No strong guarantee | Keys may be invoked and plaintext observed. Documented limitation. |
| Fully malicious live backend/directory | Limited | Content already encrypted to honest keys remains confidential; key-substitution and first-contact forgery are not fully prevented in MVP. |
| Sender/recipient intentionally exfiltrating plaintext | No | Authorized endpoint can copy what it sees. |

## 4. Separate authentication and encryption identities

Authentication answers “may this client act as this server account?” E2EE identity answers “which client-held keys may decrypt/verify this capsule?” They must never be conflated.

### Authentication

- Login identifier: normalized private email.
- Secret: password hashed server-side with Argon2id using parameters stored with the hash and calibrated for the deployment.
- Access credential: short-lived bearer access token (target 15 minutes).
- Refresh credential: 256-bit random opaque token; only its hash is stored server-side; target lifetime 30 days.
- Refresh rotates on use. Reuse of a rotated token revokes its session lineage.
- Login/registration/refresh are rate-limited. Error responses do not reveal whether an email exists.
- Password reset can restore server authentication only. It cannot decrypt old E2EE content.

### Account cryptographic identity

Each active `user_key_bundle` contains two independent Tink keysets:

1. Recipient encryption: HPKE `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM`.
2. Sender authentication: Ed25519 public-key signatures.

The server stores only the public portions, exact algorithm suite, bundle ID, owner user ID, and lifecycle status. One key must never be reused across encryption and signing purposes.

The bundle is account-scoped rather than handle-scoped. Handle changes do not affect envelopes, signatures, routing, or existing fingerprints.

## 5. Android key storage

X25519/Ed25519 Tink private keysets are deliberately exportable inside the application so a future user-controlled recovery package can restore the account identity. They therefore are not claimed to be hardware-resident private keys.

At initial registration:

1. Generate the HPKE and signature keysets with Tink using Android `SecureRandom` entropy.
2. Generate an AES-256-GCM key-encryption key (KEK) directly in `AndroidKeyStore` with encrypt/decrypt purposes and no export path.
3. Serialize each private Tink keyset only inside the crypto module.
4. Encrypt serialized keysets with the Keystore KEK using a fresh 96-bit nonce and versioned domain-separated AAD.
5. Persist only `{format_version, keystore_alias, nonce, wrapped_keyset}` in app-private storage.
6. Zero/release temporary byte arrays where APIs permit; never log or place them in Room, SavedState, Bundle, clipboard, or crash reports.

StrongBox is requested only when supported without changing app compatibility. Failure to obtain StrongBox falls back to a TEE/software-backed Android Keystore key and records the security level locally for diagnostics. MVP does not require biometric authentication for each decrypt because that would dominate the physical ritual and can cause key invalidation/recovery problems. Device screen-lock protection remains strongly recommended.

Auto Backup excludes wrapped identity keysets, tokens, decrypted cache, and fingerprint keys. Backing up ciphertext without its Keystore KEK is useless and can create misleading recovery expectations.

## 6. Capsule cryptographic construction

### 6.1 Capsule keyset

For every capsule, the sender creates a fresh Tink `AES256_GCM` AEAD keyset whose primary key uses output-prefix variant `TINK` (not `RAW`, `CRUNCHY`, or `LEGACY`). This serialized keyset is the conceptual random capsule key `K`. It is never derived from postcard pixels, handles, passwords, timestamps, photo bytes, or another capsule key.

Reusing one Tink AEAD keyset across the small number of capsule artifacts is acceptable because Tink generates independent nonces. Plaintext photos are normalized to a configured size limit and encrypted one at a time to bound memory. A future change to streaming AEAD requires a new protocol version and test vectors, not an invisible primitive swap.

### 6.2 Encrypted artifacts

Each capsule has:

- exactly one encrypted recognition manifest;
- exactly one encrypted content manifest;
- 3–5 encrypted photo blobs;
- one signed publish statement;
- one recipient HPKE envelope.

Only the recognition manifest, content manifest, and each photo blob use the capsule AES-GCM artifact framing. Ciphertext wire bytes are `5-byte Tink prefix || 12-byte IV || plaintext-length ciphertext || 16-byte tag` (exactly 33 bytes of AEAD overhead). The prefix is a Tink routing/key-ID hint, not an authentication substitute; the AEAD tag and AAD provide integrity. There is no `RAW` fallback and no heuristic alternate decoding. The signed publish statement is signed plaintext bytes transported in the control record. The recipient envelope is independently HPKE-encrypted. Android Keystore KEK wrapping is a different construction and is not this artifact framing.

The recognition manifest contains sender front/back fingerprints and minimal chooser hints. It does not contain note text or photo bytes. The content manifest contains note, photo ordering/media metadata, and nullable provider-neutral `TrackAttachment`. Separating them lets background sync prepare matching without handing content to UI code.

### 6.3 Associated data

Every AEAD call uses deterministic, versioned associated data. Canonical AAD bytes are the UTF-8/ASCII prefix `postmark/artifact/v1`, one `0x00` delimiter, then deterministic protobuf bytes of `ArtifactAadContext` (`docs/decisions/ADR-006-canonical-crypto-context-encoding.md`). The domain prefix is not a protobuf field. Logical fields of `ArtifactAadContext`:

```text
protocol_version
capsule_id
blob_id
artifact_kind
ordinal
sender_user_id
recipient_user_id
```

The context protobuf must be fully populated, protocol version exactly 1, typed IDs exactly 16 bytes; `artifact_kind` cannot be unspecified; ordinal must match kind (`-1` for non-photo, `0..4` for photo). Unknown version/kind or malformed IDs fail before the AEAD primitive is invoked. The client reconstructs and compares the complete AAD context before returning plaintext.

### 6.4 Publish statement and signature

After encrypting all artifacts, the sender constructs a canonical `PublishStatement` containing:

- protocol version and capsule ID;
- sender/recipient immutable user IDs;
- sender signing and recipient encryption key-bundle IDs;
- each blob ID, kind, ordinal, ciphertext byte length, and SHA-256 transport hash;
- envelope-independent creation timestamp rounded to seconds.

The sender signs `"postmark/publish/v1" || deterministic_statement_bytes` with Tink Ed25519. The signature transported by REST is exactly the raw Tink output with the `TINK` output prefix and is exactly 69 bytes: `0x01 || key_id(4B big-endian) || r||s(64B)` (ADR-007). There is no prefix stripping, no `RAW` variant, and no fallback decode path. Signers fail closed unless their primitive emits this exact framing; verifiers apply a structural length/prefix/key-ID guard before invoking Tink verification. The signature authenticates the complete artifact set relative to the directory key. SHA-256 fields are transport/object identity checks; the signature and AEAD provide security integrity.

The recipient verifies the signature before decrypting/presenting content and confirms that the authenticated account, routed capsule row, envelope plaintext, statement, and AAD all agree on IDs/key IDs. A fixed non-secret Ed25519 keyset plus exact cross-platform golden vector live in `protocol/fixtures/publish-signature-v1.json`; Android and backend must reproduce/accept those bytes for protocol v1.

### 6.5 Recipient envelope

The envelope plaintext is canonical binary data containing:

- envelope/protocol version;
- capsule ID, sender user ID, recipient user ID;
- sender signing key-bundle ID and recipient encryption key-bundle ID;
- serialized capsule AEAD keyset;
- SHA-256 of deterministic signed publish-statement bytes.

Tink HPKE encrypts it to the recipient public key with context info. Canonical context-info bytes are the UTF-8/ASCII prefix `postmark/envelope/v1`, one `0x00` delimiter, then deterministic protobuf bytes of `RecipientEnvelopeContext` (`docs/decisions/ADR-006-canonical-crypto-context-encoding.md`). The domain prefix is not a protobuf field. Logical fields of `RecipientEnvelopeContext`:

```text
protocol_version
capsule_id
sender_user_id
recipient_user_id
recipient_key_bundle_id
```

The context protobuf must be fully populated, protocol version exactly 1, typed IDs exactly 16 bytes. Unknown version or malformed IDs fail before the HPKE primitive is invoked.

The server never receives the envelope plaintext. Envelope ciphertext is safe to route/store but is still excluded from logs.

### 6.6 Sender-owned retry wrapping

For M2 upload retry, Android also keeps a local sender-readable wrapped copy
of the capsule keyset. It is not a recipient envelope and is never uploaded.
Its versioned associated data binds local account, capsule, sender bundle,
protocol, and retry purpose. This is the only source for process-death-safe
`RECIPIENT_KEY_STALE` re-envelope; it is removed after successful finalize,
abort, or terminal cleanup. The recipient envelope cannot serve this purpose
because a distinct sender has no recipient private key.

### 6.7 Staged recipient verification

Control/index acceptance verifies the canonical complete declaration,
authoritative sender signature, routed/envelope IDs, and the downloaded
recognition binding/hash/AEAD before fingerprints enter the local index. It
does not claim undownloaded photo/content declarations were delivered.
Background full-cache verifies transport bindings but does not decrypt the
content manifest. Presentation acceptance runs only after a current physical
scan identifies the capsule; it requires every declared content/photo
ciphertext and verifies all bindings plus content-manifest AEAD/layout before
publishing a grant or exposing any note/photo plaintext. Both stages share one
canonical statement/ID verifier.

## 7. Canonical encoding and crypto agility

- REST control messages use JSON, but all signed/encrypted logical payloads use deterministic Protocol Buffers (protobuf-lite on Android).
- A schema/protocol version is present inside and outside each artifact and is bound by signature/AAD.
- Protocol-v1 protobuf schema and capsule artifact `AES256_GCM`/`TINK`/33-byte framing are frozen now (`docs/decisions/ADR-005-capsule-artifact-aead-wire-format.md`). Exact HPKE/signature template, output-prefix, public-key serialization, and golden vectors must be frozen before their respective crypto implementation tasks.
- Unknown protocol/algorithm versions fail closed; the client does not “try” alternate algorithms or `RAW` ciphertext for capsule artifacts.
- Key rotation adds a new active bundle ID. It never silently mutates an existing key record.
- Crypto changes require an ADR, a new version if wire behavior changes, and cross-version test vectors.

## 8. Recipient resolution and stale keys

The handle lookup response contains current display handle, immutable user ID, active key-bundle ID, encryption public key, signing public key, and server-issued directory version. The sender must show the resolved recipient and obtain an explicit confirmation before encryption.

The publish statement/envelope bind the immutable user/key IDs, not the handle string. Finalize rejects a retired/revoked recipient bundle with `RECIPIENT_KEY_STALE`. The sender then re-resolves and creates only a new envelope for the same capsule key and statement version updated with the new key ID; ciphertext content must be re-signed because the statement binds the recipient key ID, but photo ciphertext need not be re-encrypted. After process death the capsule key for that re-envelope comes only from the sender-owned wrapped retry material defined in §6.6; the recipient envelope itself is not sender-readable once sender and recipient are distinct accounts.

Finalize resolves bundles by ID from the authoritative directory/database. A
recipient bundle must be ACTIVE and owned by the recipient. A sender signing
bundle must be owned by the sender and non-REVOKED; a valid RETIRED sender
bundle remains acceptable for the draft it signed so rotation during upload
does not silently destroy authenticated work. Request-adjacent public key
material is never a trust source.

Directory responses are not cached beyond the current create flow in MVP. This narrows stale-key and wrong-handle risk.

## 9. Key rotation, logout, and account deletion

- Encryption/signing key bundles rotate together in MVP to keep one directory snapshot.
- `RETIRED` keys remain usable for decrypting/verifying old capsules; they are not returned for new encryption.
- `REVOKED` denotes suspected compromise. Existing content confidentiality cannot be restored retroactively. MVP fails closed instead of presenting a capsule authenticated only by a revoked bundle, and uses a new bundle for future capsules.
- Logout removes session credentials, unwrapped keysets, plaintext caches, and scan grants from the running account context and cancels its WorkManager tags. Wrapped keys/ciphertext may remain for the same account unless the user chooses device-data removal, but every row/file/query is account-scoped and another login cannot enumerate or resume it.
- Account deletion is a separate destructive operation. Server rows/blobs can be deleted, but copies already downloaded by recipients cannot be remotely erased. MVP need not ship account deletion UI before M2, but schema ownership must support it.

## 10. Device loss and recovery path

### M0–M2 behavior

Before recovery export/import is implemented, loss of the only device means:

- password/account access may be restored;
- old private E2EE keys cannot be reconstructed by the server;
- old capsules are unreadable on a replacement device;
- a new key bundle can be registered for future capsules;
- pending senders using a stale key get a safe finalize failure and must re-envelope.

The UI/documentation must state this honestly. Support staff cannot recover content.

### Pre-release recovery architecture

ADR-010 defines the accepted recovery architecture. A random 256-bit Account
Recovery Key (ARK) is a platform-neutral wrapping root for a versioned recovery
package containing all still-needed private HPKE/signing keysets. It is not a
capsule key or the account signing identity, and account keys are not derived
from it. Each device protects the ARK with its own device-local secure key.

The server stores only an ARK-encrypted recovery package and one or more opaque
ARK recovery wrappers. A wrapper is decryptable only with a client-held
capability supplied by a recovery adapter, an existing-device transfer, or an
optional random manual recovery secret. Authentication, federated login, a
normal passkey assertion, and Android account restore do not by themselves
provide that unwrap secret. WebAuthn PRF is a candidate adapter only when the
actual authenticator/provider reports support.

Password-derived encryption is not selected because typical account passwords
permit offline guessing. Any future password option requires a separate KDF and
threat review. Multi-device recovery is not implemented in M2; until the
dedicated recovery milestone passes, device loss remains honestly unrecoverable.

## 11. Recognition and back-side privacy

- Raw front/back images are not needed by the server and are never uploaded in MVP.
- The sender uploads only a recognition manifest encrypted under the capsule AEAD keyset.
- ORB descriptors, keypoint positions, coarse image hashes, and chooser hints are sensitive derived data. They may reveal visual structure and must not be described as anonymized.
- Recipient fingerprints are stored locally encrypted with a separate Keystore-protected fingerprint-storage AEAD key.
- A future cross-device fingerprint sync must encrypt recipient fingerprints to the account identity/recovery context before upload; plaintext descriptor upload is forbidden.
- EXIF/location metadata is removed before photo encryption unless explicitly represented as encrypted capsule metadata. Raw capture EXIF is discarded.

## 12. Plaintext lifetime and rendering

- Compose state, ViewModels, SavedStateHandle, navigation arguments, analytics, and crash breadcrumbs must never contain photo bytes, private keys, capsule keysets, note text, or descriptor arrays beyond the minimal active operation.
- Decrypt one photo at a time or with a bounded prefetch window.
- Android secure-window screenshot blocking is a product choice, not a confidentiality guarantee; it is not required for MVP because the legitimate recipient can use another camera.
- Clipboard/export/share intents are not provided.
- On capsule close or grant invalidation, release bitmaps and delete any temporary plaintext cache files.

## 13. Backend and logging hardening

- HTTPS is mandatory outside loopback development. Certificate validation is never disabled in release builds.
- Authorization checks derive sender/recipient from the authenticated user, not request-provided handles.
- Blob object keys are random/opaque and never user-controlled filesystem paths.
- Local filesystem adapter prevents path traversal and writes atomically.
- Upload size, artifact count, content type, declared hash, and route-specific rate limits are enforced before/while streaming.
- SQL and object-store backups are encrypted operationally, even though application payload is E2EE.
- Structured logs use opaque IDs and redacted error codes. No request-body logging on auth, envelope, key, blob, or capsule routes.
- Crash reporting is disabled until explicit redaction tests exist.
- Development secrets and test keys are never accepted in production mode.

## 14. Security verification requirements

Before M2 passes, automated tests must prove:

- encrypt/decrypt and sign/verify golden vectors;
- wrong recipient private key cannot open an envelope;
- changed envelope context cannot open an envelope;
- any changed AAD field rejects artifact decryption;
- bit flips, truncation, blob substitution, reordered photo ordinal, wrong statement hash, and wrong signing key fail closed;
- private key bytes, capsule key bytes, note markers, and photo markers do not appear in captured API requests, PostgreSQL rows, object-store files, Room rows, or normal logs;
- a retired recipient key causes safe publish rejection;
- access control rejects unrelated authenticated users;
- refresh-token rotation/reuse detection and password-hash verification work;
- process death invalidates scan grants and does not persist plaintext navigation state.
- logout A/login B exposes no A outbox, cursor, fingerprint, chooser hint, blob, or worker transition;
- sender retry wrapping survives process death, rejects wrong account/key/AAD, supports stale-recipient re-envelope without artifact changes, and is cleaned at its terminal boundary;
- index-only sync never produces `CIPHERTEXT_SYNCED` or note/photo plaintext;
- PostgreSQL finalize and BlobStore failure injection prove rollback/orphan cleanup without claiming cross-system atomicity.

## 15. Residual risks requiring explicit future decisions

1. Key transparency/out-of-band verification: required to defend fully against a malicious directory or first-contact key substitution.
2. Recovery UX: required before claiming durable access after device loss.
3. Multiple devices: requires secure private-key transfer and fingerprint sync semantics.
4. Email invitation target identity: protocol v1 binds `recipient_user_id` in
   every artifact AAD. ADR-009 defers choosing a reserved future user ID versus
   a new protocol version; envelope-only re-wrap is insufficient.
5. Compromised sender device: content captured/encrypted on that device cannot be trusted or made secret from it.
6. Metadata minimization: sender/recipient graph and timing remain visible to the service; hiding them would require a materially different routing system and is not justified for MVP.

## 16. Primary references

- [Google Tink hybrid encryption and recommended HPKE suite](https://developers.google.com/tink/hybrid)
- [Google Tink key-management concepts](https://developers.google.com/tink/key-management-overview)
- [Android Keystore security properties and limitations](https://developer.android.com/privacy-and-security/keystore)
- [RFC 9180: Hybrid Public Key Encryption](https://www.rfc-editor.org/rfc/rfc9180)
