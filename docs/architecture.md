# Architecture

Status: **DRAFT — Codex-owned; application code is blocked until the architecture gate.**

This document defines the system shape and lifecycle boundaries for the production-shaped MVP. Cryptographic wire details live in `security.md` and `protocol.md`; computer-vision details live in `recognition.md`.

## 1. Architectural decisions

The MVP uses the following constraints as architectural invariants:

1. The only shipping client is a native Android application.
2. Camera processing, feature extraction, matching, encryption, decryption, and signature verification happen on Android.
3. Raw postcard scans, plaintext capsule content, plaintext recognition descriptors, capsule keys, and private identity keys never leave the client.
4. The backend authenticates accounts, resolves handles, routes capsules, validates opaque upload structure, and stores ciphertext.
5. The backend cannot enforce possession of a postcard. The honest client enforces the physical-first product rule with a short-lived in-memory scan grant.
6. Incoming recognition indexes may exist locally as infrastructure, but no query or screen exposes them as an inbox or gallery.
7. A capsule is published atomically only after every declared ciphertext blob and the recipient envelope have been uploaded and verified.
8. Upload retry is blob-granular. Byte-range multipart upload is unnecessary for the deliberately size-limited MVP payload.
9. The server never records a semantic `OPENED` state. At most it knows that encrypted material was synchronized.
10. Music, contacts, iOS, web, notifications, AR, realtime frame analysis, and public/social surfaces are outside the core design.

## 2. System context and trust boundaries

```mermaid
flowchart LR
  P[Physical postcard]

  subgraph Android[Trusted Android client]
    UI[Compose UI]
    CAM[CameraX still capture]
    CV[OpenCV normalization and matching]
    CRYPTO[Tink crypto boundary]
    KS[Android Keystore KEK]
    DB[(Room metadata)]
    FILES[Private ciphertext files]
    WM[WorkManager sync]
    GRANT[In-memory scan grant]

    UI --> CAM --> CV
    UI --> CRYPTO
    CRYPTO --> KS
    UI --> DB
    UI --> FILES
    WM --> DB
    WM --> FILES
    CV --> GRANT --> UI
  end

  API[FastAPI HTTPS API]
  PG[(PostgreSQL auth and routing)]
  OBJ[Storage adapter: local filesystem or S3-compatible]

  P --> CAM
  WM <-->|auth, routing, ciphertext| API
  UI <-->|foreground API calls| API
  API --> PG
  API --> OBJ

  CAM -. raw scans never cross .-> API
  CV -. plaintext descriptors never cross .-> API
  CRYPTO -. keys and plaintext never cross .-> API
```

### Trusted client boundary

The Android application is trusted to enforce the intended UX and protect keys against ordinary device-storage extraction. A rooted device, a maliciously modified client, screen capture, or a person with an unlocked phone can bypass the product-level scan rule. That limitation is explicit: the postcard is not a cryptographic secret.

### Backend boundary

The API, PostgreSQL, object storage, logs, backups, and operators are outside the content-confidentiality boundary. Their compromise must not reveal photos, note text, optional place labels, recognition descriptors, or capsule keys.

The server necessarily sees metadata required for accounts and routing: normalized email, normalized/current handle, immutable user IDs, public key bundles and key IDs, sender/recipient IDs, object sizes and hashes, protocol versions, timestamps, status, and traffic patterns.

## 3. Technology choices

| Area | Choice | Concrete problem solved now |
| --- | --- | --- |
| Android | Kotlin, Gradle, Jetpack Compose | Native camera/security integration and a reproducible CLI build. |
| Capture | CameraX still capture | Two deliberate captures; no realtime frame pipeline is required. |
| Recognition | OpenCV Android | On-device rectangle normalization, ORB features, matching, homography, and RANSAC. |
| Local data | Room plus app-private files | Transactional metadata plus efficient ciphertext/descriptor file storage. |
| Background work | WorkManager | Resume ciphertext upload and incoming index sync after process/network interruption. |
| Cryptography | Google Tink plus Android Keystore | Established HPKE, AEAD, signatures, keysets, and a device-protected wrapping key. |
| Backend | Python, FastAPI | A small typed HTTP boundary with straightforward Linux operation and tests. |
| Persistence | PostgreSQL, SQLAlchemy 2, Alembic | Atomic handle uniqueness, routing relations, sessions, migrations, and publish transactions. |
| Blob storage | `BlobStore` interface | Same server logic with local filesystem in development and S3-compatible storage later. |
| Local environment | Docker Compose | Reproducible API/PostgreSQL stack without external accounts. |

Versions are pinned by lockfiles/version catalogs during M0, not in this document.

## 4. Android project structure

The MVP uses five Gradle modules:

```text
android/
├── app/
├── core/model/
├── core/data/
├── core/crypto/
└── core/recognition/
```

```mermaid
flowchart BT
  model[":core:model"]
  data[":core:data"]
  crypto[":core:crypto"]
  recognition[":core:recognition"]
  app[":app"]

  data --> model
  crypto --> model
  recognition --> model
  app --> model
  app --> data
  app --> crypto
  app --> recognition
```

### `:core:model`

Pure Kotlin definitions with no Android, network, persistence, Tink, or OpenCV types:

- typed IDs (`UserId`, `CapsuleId`, `BlobId`, `KeyBundleId`);
- normalized handle value object;
- capsule and delivery lifecycle enums;
- protocol-independent domain failures;
- encrypted-blob references and recognition result summaries.

### `:core:data`

Android library responsible for:

- Room database and migrations;
- HTTP DTO/adapters;
- app-private ciphertext file store;
- repositories;
- outgoing upload and incoming sync workers;
- cursor and retry state.

It depends only on `:core:model`. It stores envelopes and recognition payloads as opaque bytes and does not interpret crypto or OpenCV structures. Encryption is completed before an outbox job is enqueued; incoming sync downloads ciphertext without decrypting it.

### `:core:crypto`

Android library responsible for:

- Tink registration and primitive creation;
- account encryption/signing key bundle lifecycle;
- Android Keystore-backed key-encryption key (KEK);
- capsule keyset generation;
- manifest/blob encryption and decryption;
- HPKE recipient envelope creation/opening;
- signing and verification;
- protocol test vectors.

Only this module may handle plaintext private keysets or capsule key material. It depends only on `:core:model`.

### `:core:recognition`

Android library responsible for:

- capture quality evaluation;
- postcard rectangle detection and manual-corner input model;
- perspective normalization;
- ORB fingerprint extraction/serialization;
- descriptor and homography matching;
- configurable scoring and ambiguity classification.

OpenCV classes never cross its public boundary. It depends only on `:core:model`.

### `:app`

One-activity Compose application that owns navigation, CameraX integration, dependency wiring, and use-case orchestration. Feature packages remain packages rather than Gradle modules because the MVP has few screens and one client:

```text
app.postmark/
├── auth/
├── home/
├── create/
├── scan/
├── capsule/
└── wiring/
```

Required top-level surfaces are onboarding/authentication, Home, Create, Scan, ambiguity choice, and fullscreen capsule presentation. There is no incoming list, sent list, history, or capsule deep link.

### Key interfaces

These are responsibility boundaries, not prescribed source signatures:

- `HandleResolver`: resolve and confirm a current handle snapshot, immutable user ID, and active key bundle.
- `CapsuleRepository`: persist opaque incoming capsule metadata and ciphertext references.
- `OutboxRepository`: persist blob-granular upload/finalize progress.
- `IdentityKeyManager`: generate, wrap, load, rotate, and later recover account identity keysets.
- `CapsuleCryptor`: create/open envelopes and encrypt/decrypt/verify capsule artifacts.
- `PostcardRecognizer`: normalize, fingerprint, rank, and classify candidates.
- `FingerprintStore`: persist encrypted sender/recipient fingerprints without exposing enumeration to UI.
- `ScanGrantManager`: issue and validate memory-only grants.

## 5. Android navigation and scan grant

`CapsuleScreen` accepts a random grant ID, not a capsule ID. `ScanGrantManager` holds `{grant_id, capsule_id, issued_at, expires_at}` only in process memory.

A grant is issued after:

1. both front and back were captured in the current scan session;
2. automatic recognition produced a unique accepted candidate, or the user selected a plausible ambiguous candidate;
3. the recipient envelope opened successfully;
4. signed artifact metadata and AEAD integrity verified.

The default grant lifetime is ten minutes and is configurable in one place. Leaving the capsule screen, process death, logout, or expiration consumes/invalidates it. Screen rotation does not invalidate it while the process and navigation entry survive.

This gate prevents accidental gallery-like navigation in the honest application. It is not claimed as DRM or cryptographic proof of postcard possession.

## 6. Local persistence model

Room contains infrastructure records only. No DAO may expose a query named or shaped as “all memories” to UI code.

### Tables

`local_account`

- `user_id` (single active account scope);
- current handle and public key bundle IDs;
- authentication state timestamps;
- no password, private key, or refresh-token plaintext.

`incoming_capsule`

- `capsule_id`, sender/recipient user IDs;
- sender signing key ID and recipient encryption key ID;
- protocol version, server lifecycle status, ready timestamp;
- signed statement bytes/reference;
- local material state: `DISCOVERED`, `INDEX_CACHED`, `MATERIAL_CACHED`, `FINGERPRINT_ACCEPTED`, `CORRUPT`;
- no note, place, photo thumbnail, or plaintext chooser label.

`incoming_envelope`

- capsule ID and recipient key ID;
- HPKE ciphertext and transport hash;
- received timestamp.

`blob_cache`

- blob ID, capsule ID, kind, ordinal;
- expected ciphertext size/hash;
- private local path and cache state;
- never a content URI visible to another app.

`recognition_fingerprint`

- fingerprint ID, capsule ID, side (`FRONT`/`BACK`), origin (`SENDER`/`RECIPIENT`);
- fingerprint format/profile version;
- path to locally encrypted fingerprint bytes;
- created timestamp and preferred flag;
- no raw bitmap.

`outbox_capsule`

- client-generated capsule ID and idempotency key;
- resolved recipient user/key snapshot;
- state: `PREPARING`, `ENCRYPTED`, `UPLOADING`, `FINALIZING`, `PUBLISHED`, `RETRYABLE_FAILURE`, `TERMINAL_FAILURE`;
- encrypted artifact paths and last structured error;
- no plaintext note or image bytes.

`outbox_blob`

- blob ID, capsule ID, kind, ordinal, local ciphertext path, size/hash;
- upload state and attempt counters.

`sync_cursor`

- authenticated user ID, stream name, opaque server cursor, last successful sync.

### Secrets and files

- The authentication refresh token is stored under a Keystore-protected local secret boundary, not Room plaintext.
- Exportable Tink private identity keysets are serialized only long enough to encrypt them with the non-exportable Android Keystore KEK. Room stores only the wrapped keyset location/version.
- Ciphertext blobs live in app-private files. Room transactions publish or remove their references.
- Selected photos are read through the Android Photo Picker, normalized to the MVP size limit, encrypted one at a time, and then released.
- Raw postcard captures are held only long enough to normalize and extract fingerprints. They are deleted after the encrypted artifacts are durably staged.
- Decrypted photos are decoded on demand after a scan grant. Persistent plaintext thumbnails are forbidden. If a decoder requires a temporary file, it must use app-private cache and delete it when the capsule screen closes.

## 7. Backend structure

```text
server/
├── src/postmark/
│   ├── api/          # HTTP parsing, auth dependency, error mapping
│   ├── auth/         # password verification and opaque sessions
│   ├── users/        # handles and public key directory
│   ├── capsules/     # draft/upload/finalize/routing services
│   ├── storage/      # BlobStore protocol and adapters
│   ├── db/           # SQLAlchemy models, session, migrations bridge
│   ├── settings.py
│   └── main.py
├── migrations/
└── tests/
```

Request handlers call service functions; services own authorization/state transitions; repositories own SQL; `BlobStore` owns byte streaming. Storage adapters never inspect ciphertext content.

The backend has no Android protocol implementation, OpenCV dependency, photo/media library, decryption primitive, recommendation module, contact graph, or gallery query.

## 8. Server database schema

All IDs are UUIDs generated client-side for capsule/blob idempotency or server-side for accounts/sessions. Timestamps are UTC.

### `users`

- `id` PK;
- `email_normalized` unique, private account identifier;
- `handle_normalized` unique and `handle_display`;
- `created_at`, `updated_at`, `disabled_at`.

Handle normalization is lowercase ASCII matching `^[a-z0-9_.]{3,30}$`. Every relationship uses `users.id`, never a handle.

### `auth_credentials`

- `user_id` PK/FK;
- Argon2id password hash and parameters;
- `password_changed_at`.

### `auth_sessions`

- `id` PK, `user_id` FK;
- hash of the opaque refresh token;
- `created_at`, `expires_at`, `last_used_at`, `revoked_at`;
- rotation lineage for replay detection.

### `user_key_bundles`

- `id` PK, `user_id` FK;
- encryption and signing public-key encodings;
- exact algorithm suite and protocol version;
- status `ACTIVE`, `RETIRED`, or `REVOKED`;
- `created_at`, `retired_at`;
- at most one active bundle per user via a partial unique index.

Old bundles remain readable so existing capsules remain verifiable/decryptable after rotation or recovery.

### `capsules`

- `id` PK (client generated);
- `sender_user_id`, `recipient_user_id` FKs;
- `sender_key_bundle_id`, `recipient_key_bundle_id` FKs;
- protocol version;
- state `DRAFT`, `READY`, `ABORTED`;
- signed publish-statement bytes and hash after finalize;
- `created_at`, `ready_at`, `draft_expires_at`.

Only authenticated sender may create/upload/finalize its draft. Only the bound recipient may list/download a ready capsule.

### `capsule_envelopes`

- `capsule_id` PK/FK;
- `recipient_user_id`, `recipient_key_bundle_id` FKs;
- HPKE ciphertext, byte length, SHA-256 transport hash;
- created timestamp.

MVP stores one recipient envelope. A future sender/recovery envelope requires an explicit product and protocol change.

### `capsule_blobs`

- `id` PK (client generated), `capsule_id` FK;
- kind `RECOGNITION_MANIFEST`, `CONTENT_MANIFEST`, or `PHOTO`;
- photo ordinal when applicable;
- object-storage key;
- expected ciphertext size and SHA-256;
- upload state `DECLARED` or `STORED`;
- unique constraints enforce one recognition manifest, one content manifest, and photo ordinals 0–4 per capsule.

### `recipient_delivery_state`

- `(recipient_user_id, capsule_id)` composite PK;
- state `AVAILABLE` or `CIPHERTEXT_SYNCED`;
- timestamps.

This state is an optimization for delivery/sync only. There is no server state for physical receipt, recognition result, decryption, capsule opening, or later scans. It is never exposed to the sender in MVP.

## 9. Capsule publication lifecycle

```mermaid
stateDiagram-v2
  [*] --> LocalPreparing
  LocalPreparing --> LocalEncrypted
  LocalEncrypted --> ServerDraft: create draft
  ServerDraft --> Uploading: upload declared blobs/envelope
  Uploading --> ServerDraft: retry individual blob
  Uploading --> Ready: finalize transaction
  ServerDraft --> Aborted: sender abort or expiry
  Ready --> RecipientDiscovered: incoming sync
  RecipientDiscovered --> IndexCached: envelope + recognition ciphertext cached
  IndexCached --> MaterialCached: content/photo ciphertext cached on demand
  MaterialCached --> FingerprintAccepted: successful first receipt
```

### Sender publish

1. Resolve the handle immediately before encryption and display the current handle plus stable account information for explicit confirmation.
2. Capture/normalize both sides and derive fingerprints locally.
3. Select and locally normalize 3–5 photos; create optional note and optional encrypted chooser hint.
4. Generate a fresh capsule AEAD keyset.
5. Encrypt recognition manifest, content manifest, and each photo locally.
6. Create a signed publish statement that binds IDs, key IDs, protocol version, blob kinds/ordinals/sizes/hashes, and ciphertext hashes.
7. HPKE-wrap the capsule keyset and statement digest for the resolved recipient key bundle.
8. Persist only encrypted outbox artifacts; delete raw captures/plaintext staging.
9. Create an idempotent server draft and declare all artifacts.
10. Upload each ciphertext blob independently. Retries replace only the same declared blob ID and exact hash/size.
11. Upload the envelope and finalize. Finalize is a PostgreSQL transaction that verifies ownership, active recipient key, required artifact counts, stored hashes/sizes, envelope, and signature structure before making the capsule `READY`.
12. After `READY` is confirmed, remove the local capsule key and staging ciphertext according to cache policy. No sent-memory gallery is retained.

If the recipient key bundle was retired after lookup, finalize fails with `RECIPIENT_KEY_STALE`; the client re-resolves, re-envelopes the same capsule key for the new key, and retries finalize. Content blobs do not need re-encryption.

### Incoming sync bootstrap

Recognition before download is a bootstrap problem. The solution is routing, not server-side CV:

1. The authenticated client lists ready capsules addressed to its immutable user ID.
2. It downloads each small HPKE envelope, signed statement, and encrypted recognition manifest.
3. It opens the envelope and decrypts only the recognition manifest locally.
4. It persists sender fingerprints re-encrypted under a local fingerprint-storage key, or retains the encrypted manifest plus key reference.
5. The UI receives no enumerable capsule list, counts, thumbnails, notes, or places.

The server can technically serve content ciphertext before a scan because it cannot prove postcard possession. The official app downloads content/photos only after a scan match unless an explicit offline-cache policy has already cached ciphertext. Plaintext content is never materialized without a current scan grant.

## 10. First receipt and later scan

### First receipt

- Candidate source: pending sender fingerprints from incoming sync.
- Both sides are captured and normalized.
- Matching is local and fail-safe.
- Automatic unique match or explicit plausible-candidate selection identifies a capsule.
- Envelope, signed statement, and AEAD artifacts are verified before presentation.
- The normalized delivered postcard produces a new immutable `RECIPIENT` front/back fingerprint pair.
- The recipient pair becomes preferred for future scans; sender fingerprints remain as fallback.

### Later scan

- Candidate source first: accepted recipient fingerprints.
- If no recipient candidate passes weak evidence gates, retry against sender fingerprints.
- The same two-side, ambiguity, signature, AEAD, and scan-grant rules apply.
- The original recipient fingerprint is not silently replaced on every scan, preventing gradual drift or poisoning. A future explicit “improve recognition” operation may add a new version after a high-confidence match.

## 11. Sync and retry strategy

- WorkManager uses one unique outgoing chain per capsule and one unique incoming-sync chain per authenticated account.
- Network calls are idempotent. Client-generated UUIDs identify capsule/blob resources; mutating requests also carry an idempotency key and request hash.
- Upload retry is per encrypted blob. A completed hash-identical blob is not transferred again.
- Finalize is repeatable and returns the existing `READY` result for an identical request.
- Incoming pagination uses an opaque cursor ordered by `(ready_at, capsule_id)`; a client may safely replay a page and upsert locally.
- Authentication refresh and upload retry are separate. A 401 triggers one serialized refresh attempt; failure moves work to `AUTH_REQUIRED`, not data loss.
- Exponential backoff has bounded jitter. Terminal protocol/crypto/schema errors do not retry forever.
- Logout cancels account-scoped workers, clears access/refresh material and plaintext cache, and leaves ciphertext/private records inaccessible until that account authenticates again.

## 12. Failure boundaries

| Failure | Required behavior |
| --- | --- |
| Handle unresolved | Creation cannot advance to recipient confirmation. |
| Handle resolves differently after confirmation | Publish is bound to confirmed user/key IDs; stale key finalize fails safely. |
| Process dies before encryption staging completes | Discard incomplete plaintext/raw capture state and restart creation. |
| Process dies after encrypted staging | Resume remaining uploads from outbox. |
| Blob hash/size differs | Server rejects it; client never finalizes. |
| Missing/corrupt envelope | Mark local capsule corrupt, show no content, allow re-sync. |
| AEAD/signature failure | Show no plaintext; record a redacted diagnostic code. |
| No visual candidate | Ask for recapture with quality guidance. |
| Multiple plausible candidates | Show only the scan-scoped ambiguity chooser. |
| Network unavailable on first receipt | Match may succeed from cached index; content opens only if ciphertext is cached. |
| Database/object storage restart | Migrations and durable blob state allow retry without duplicate capsule creation. |
| Device lost before recovery exists | Auth account may be recovered; old E2EE content is unavailable. Future capsules use a new key bundle. |

## 13. Build, configuration, and observability

- Android must build from Linux with `./gradlew assembleDebug`; local SDK setup and `adb install` are documented in M0.
- Backend dependencies are locked and the service starts through Docker Compose plus a direct test command.
- Secrets are injected through environment/config files excluded from Git. Development defaults must not be accepted in production mode.
- Logs use structured event names and opaque IDs. They never include password values, tokens, email unless strictly required for an audited auth event, handles in capsule logs, public/private key bytes, envelopes, ciphertext bodies, descriptors, note text, photo metadata, raw scans, or decrypted exceptions.
- Metrics are operational only: request latency/error class, worker outcomes, blob byte totals, and recognition timing on-device. Product engagement counters are not created.

## 14. Deliberately deferred complexity

The architecture leaves extension points but does not implement them now:

- recovery package and multi-device transfer use exportable identity keysets, but UI/workflow waits until security hardening;
- contacts may resolve to immutable user IDs through the existing directory, but there is no contacts table/UI in MVP;
- `TrackAttachment` remains a nullable, provider-neutral encrypted field;
- S3 is an adapter behind `BlobStore`; no Kubernetes, queue broker, cache cluster, CDN, or microservices;
- iOS can implement the documented protocol later; no shared UI or multiplatform abstraction is introduced now.

## 15. Architecture gate checklist

Application code remains blocked until all are true:

- `product.md` contains no gallery/social path and agrees with all lifecycle states;
- `security.md` defines the threat model, exact primitives, AAD, key lifecycle, recovery limitation, and metadata leakage;
- `protocol.md` defines canonical payloads, endpoints, idempotency, state machines, and error codes;
- `recognition.md` defines versioned fingerprints, capture normalization, scoring, thresholds, ambiguity, and evaluation data;
- `milestones.md` orders vertical implementation without fake mechanisms;
- `acceptance-criteria.md` has command-level and physical-device pass/fail checks;
- cross-document identifiers, state names, cardinalities, and trust boundaries are consistent;
- unresolved architecture changes require an ADR before implementation.
