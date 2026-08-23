# Protocol and API contracts

Status: **APPROVED logical v1 contract; normative generated schemas/field fixtures freeze in M1.**

This document defines identifiers, canonical encrypted/signed payloads, REST contracts, state transitions, idempotency, limits, and error behavior. The backend treats encrypted artifacts as opaque bytes even when it validates their declared structure and signed publish statement.

## 1. Conventions

- Base URL: `/v1`.
- Control requests/responses: UTF-8 JSON with `snake_case` fields.
- Binary blobs: `application/octet-stream`.
- Small binary fields in JSON: unpadded base64url.
- IDs: lowercase canonical UUID strings at REST; 16 network-order bytes in canonical protobuf payloads.
- Time: RFC 3339 UTC at REST; signed integer epoch seconds in canonical protobuf.
- Unknown JSON response fields are ignored; unknown required protocol versions are rejected.
- Mutating JSON requests require `Idempotency-Key` (UUID) unless explicitly exempted.
- Auth: `Authorization: Bearer <opaque_access_token>`.
- API errors use `application/problem+json` and never echo secrets/ciphertext.

## 2. Handle rules

Input may optionally begin with `@`; the prefix is removed before validation.

```text
normalize(handle) = ASCII lowercase(handle)
allowed           = ^[a-z0-9_.]{3,30}$
```

No Unicode normalization is attempted in v1 because non-ASCII is rejected. PostgreSQL stores the normalized lowercase value; UI adds `@` only for display. Uniqueness is enforced only by a database unique constraint on normalized value, never by a pre-check alone.

Handles locate current accounts. Every returned/posted relationship also carries immutable `user_id`; subsequent operations ignore the original handle string.

### Email normalization

The server trims surrounding ASCII whitespace, validates syntax/domain normalization with one pinned email-validation library, and stores its normalized result case-folded as `email_normalized`. V1 intentionally treats case variants as the same login. The server is authoritative; Android performs only early UX validation and never computes uniqueness locally.

## 3. Canonical payload encoding

REST JSON is never signed directly. Encrypted and signed data use Protocol Buffers with deterministic serialization enabled. Repeated artifact bindings are sorted by `(kind numeric value, ordinal, blob_id bytes)` before serialization. No maps, floats, locale strings, or wall-clock timezone values occur in signed structures.

The normative `.proto` file created during implementation must express these v1 logical messages exactly; field numbers are frozen with the first golden-vector commit.

```proto
syntax = "proto3";
package postmark.protocol.v1;

enum ArtifactKind {
  ARTIFACT_KIND_UNSPECIFIED = 0;
  RECOGNITION_MANIFEST = 1;
  CONTENT_MANIFEST = 2;
  PHOTO = 3;
}

message ArtifactBinding {
  bytes blob_id = 1;              // 16 UUID bytes
  ArtifactKind kind = 2;
  sint32 ordinal = 3;             // -1 except PHOTO: 0..4
  uint64 ciphertext_size = 4;
  bytes ciphertext_sha256 = 5;    // exactly 32 bytes
}

message PublishStatement {
  uint32 protocol_version = 1;    // exactly 1 for v1
  bytes capsule_id = 2;
  bytes sender_user_id = 3;
  bytes recipient_user_id = 4;
  bytes sender_key_bundle_id = 5;
  bytes recipient_key_bundle_id = 6;
  int64 created_at_epoch_seconds = 7;
  repeated ArtifactBinding artifacts = 8;
}

message RecipientEnvelopePlaintext {
  uint32 protocol_version = 1;
  bytes capsule_id = 2;
  bytes sender_user_id = 3;
  bytes recipient_user_id = 4;
  bytes sender_key_bundle_id = 5;
  bytes recipient_key_bundle_id = 6;
  bytes capsule_aead_keyset = 7;  // serialized secret Tink keyset
  bytes publish_statement_sha256 = 8;
}

message ChooserHint {
  string sender_handle_snapshot = 1;
  int64 created_at_epoch_seconds = 2;
  optional string place_label = 3;
}

message RecognitionManifest {
  uint32 protocol_version = 1;
  bytes capsule_id = 2;
  ChooserHint chooser_hint = 3;
  bytes front_fingerprint = 4;    // versioned recognition payload
  bytes back_fingerprint = 5;
}

message PhotoEntry {
  bytes blob_id = 1;
  uint32 ordinal = 2;
  string media_type = 3;          // image/jpeg in v1
  uint32 width = 4;
  uint32 height = 5;
}

message TrackAttachment {
  string provider = 1;
  string track_id = 2;
  string title = 3;
  string artist = 4;
  uint32 start_ms = 5;
}

message ContentManifest {
  uint32 protocol_version = 1;
  bytes capsule_id = 2;
  repeated PhotoEntry photos = 3; // 3..5, sorted ordinal
  optional string note = 4;
  optional TrackAttachment track = 5; // MUST be absent in MVP
}
```

The signed publish object transported by REST is:

```json
{
  "statement": "<base64url deterministic PublishStatement>",
  "signature": "<base64url Ed25519 signature>",
  "sender_key_bundle_id": "uuid"
}
```

Signature input and AEAD/HPKE domain-separated contexts are normative in `security.md`.

## 4. Common REST resources

### User summary

```json
{"user_id": "uuid", "handle": "mykola"}
```

Email is returned only by `/me`, never handle lookup.

### Public key bundle

```json
{
  "key_bundle_id": "uuid",
  "user_id": "uuid",
  "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
  "protocol_version": 1,
  "encryption_public_keyset": "<base64url Tink public keyset>",
  "signing_public_keyset": "<base64url Tink public keyset>",
  "status": "ACTIVE",
  "created_at": "2026-08-23T03:00:00Z"
}
```

Private keysets are not accepted by any API field.

### Blob declaration

```json
{
  "blob_id": "uuid",
  "kind": "RECOGNITION_MANIFEST",
  "ordinal": null,
  "ciphertext_size": 43192,
  "ciphertext_sha256": "<base64url 32 bytes>"
}
```

Photo ordinal is 0–4. Non-photo ordinal is JSON `null` and canonical protobuf `-1`.

## 5. Authentication endpoints

### `POST /v1/auth/register`

Atomic creation of user, credentials, initial active public key bundle, and session.

```json
{
  "email": "private@example.com",
  "password": "user supplied secret",
  "handle": "mykola",
  "key_bundle": {
    "key_bundle_id": "client-generated uuid",
    "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
    "protocol_version": 1,
    "encryption_public_keyset": "...",
    "signing_public_keyset": "..."
  }
}
```

Response `201` contains user summary/email, active key-bundle ID, a `pm_at_...` access token with expiry, and a `pm_rt_...` refresh token with expiry. The client must durably wrap its private keysets before registration. A failed registration leaves only local orphan key material, which may be reused for a corrected retry or deleted.

### `POST /v1/auth/login`

Request: email and password. Response: same session/user shape plus active public key-bundle metadata. If local private key material for that bundle is absent, authentication succeeds but crypto state is `RECOVERY_REQUIRED`; the client must not generate a replacement bundle silently.

### `POST /v1/auth/refresh`

Request contains only the refresh token. Success rotates both access and refresh tokens. Reuse of an already rotated refresh token returns `SESSION_REPLAYED` and revokes the lineage.

### `POST /v1/auth/logout`

Revokes the authenticated session. Idempotent; returns `204`.

## 6. Account and directory endpoints

### `GET /v1/me`

Returns authenticated user summary, private email, current active key-bundle metadata, and account timestamps.

### `PATCH /v1/me/handle`

Request `{ "handle": "new_handle" }`. Database uniqueness is atomic. Response returns user ID plus normalized current handle. Existing capsule relations do not change.

### `GET /v1/directory/handles/{handle}`

Returns `404 HANDLE_NOT_FOUND` or user summary, active public key bundle, and an opaque `directory_version`. The sender does not cache it beyond the current create flow and stores immutable IDs/key ID after explicit confirmation.

### `GET /v1/directory/key-bundles/{key_bundle_id}`

Returns the immutable public portion of an `ACTIVE`, `RETIRED`, or `REVOKED` bundle by ID to an authenticated client. Recipients use this endpoint to verify capsules signed before a sender rotated keys. It never resolves routing by handle and never returns email/private material. A revoked response remains available but is marked `REVOKED`; MVP fails closed and does not present a capsule authenticated only by a revoked bundle.

### Future key lifecycle endpoints

`POST /v1/me/key-bundles` and `POST /v1/me/key-bundles/{id}/retire` are reserved for M4 recovery/rotation. They are not required in M2, but server models must not assume only one historical bundle.

## 7. Capsule draft and upload endpoints

### `POST /v1/capsules`

Creates an idempotent `DRAFT`. The authenticated user is always the sender.

```json
{
  "capsule_id": "client uuid",
  "recipient_user_id": "uuid",
  "sender_key_bundle_id": "uuid",
  "recipient_key_bundle_id": "uuid",
  "protocol_version": 1,
  "blobs": ["5..7 blob declarations"]
}
```

Validation requires sender ownership, current recipient bundle, exactly one recognition manifest, one content manifest, and 3–5 sequential photo ordinals, unique IDs, size limits, and an identical request hash on replay. Response `201` or idempotent `200` returns capsule state and per-blob `DECLARED`/`STORED` state.

### `PUT /v1/capsules/{capsule_id}/blobs/{blob_id}`

Streams one complete `application/octet-stream` ciphertext body. Required headers are `Content-Length`, `X-Postmark-Ciphertext-SHA256`, and `Idempotency-Key`.

The service hashes while streaming to a temporary object, compares length/hash, atomically promotes through `BlobStore`, and marks `STORED`. A hash-identical replay returns `204`; different bytes for a stored blob ID return `BLOB_CONFLICT`. There is no multipart/range API in v1. WorkManager resumes by skipping stored blobs.

### `POST /v1/capsules/{capsule_id}/finalize`

```json
{
  "signed_publish_statement": {
    "statement": "...",
    "signature": "...",
    "sender_key_bundle_id": "uuid"
  },
  "recipient_envelope": {
    "recipient_key_bundle_id": "uuid",
    "ciphertext": "...",
    "ciphertext_size": 812,
    "ciphertext_sha256": "..."
  }
}
```

Within one database transaction, finalize verifies draft ownership/state, non-expiry, current and matching user/key IDs, deterministic v1 statement structure, sender Ed25519 signature, exact stored blob declarations, envelope limits/hash, and artifact cardinality. It then inserts envelope/delivery state and marks `READY`.

A failed transaction leaves an unrouteable `DRAFT`. Identical finalize replay returns existing `READY`; different data returns `FINALIZE_CONFLICT`. `RECIPIENT_KEY_STALE` requires re-resolve, re-envelope, statement update, and re-sign, but not photo re-encryption.

### `DELETE /v1/capsules/{capsule_id}`

Sender may abort only a `DRAFT`; returns `204`. Ready capsules cannot be revoked in MVP. Expired drafts and unreferenced blobs are garbage-collected.

## 8. Recipient delivery endpoints

### `GET /v1/capsules/incoming?cursor=&limit=`

Returns only `READY` capsules for the authenticated recipient, oldest page first. Each item carries route/key IDs, protocol/ready time, signed statement, recipient envelope, and blob declarations. It contains no note, place, sender handle, thumbnail, recognition result, or open state.

The cursor is an opaque encoding of `(ready_at, capsule_id)` and is safe to replay. Local upsert by IDs makes replay idempotent. `limit` defaults 50 and maxes 100.

### `GET /v1/capsules/{capsule_id}/blobs/{blob_id}`

Only the bound recipient (or sender while its draft is unfinished) may download the opaque blob. Whole-response cache validators are allowed; v1 does not require ranges. Client verifies hash before crypto use.

### `POST /v1/capsules/{capsule_id}/material-synced`

Recipient may idempotently change server delivery state from `AVAILABLE` to `CIPHERTEXT_SYNCED`. This does not mean physically received, recognized, decrypted, or opened and is never queryable by the sender in MVP.

## 9. Client state machines

Sender: `PREPARING -> ENCRYPTED -> UPLOADING -> FINALIZING -> PUBLISHED`. Network transitions may enter `RETRYABLE_FAILURE`; incompatible/corrupt/rejected state enters `TERMINAL_FAILURE`.

Recipient: `DISCOVERED -> INDEX_CACHED -> MATERIAL_CACHED -> FINGERPRINT_ACCEPTED`, with `CORRUPT` as a repair state. `MATERIAL_CACHED` may precede scan for offline policy, but plaintext access still requires a current scan grant. No local/server `OPENED` state exists.

## 10. Idempotency semantics

- Scope is `(authenticated_user_id, method, normalized route, Idempotency-Key)`.
- Server stores request SHA-256 and terminal response for at least 24 hours; resource UUID uniqueness remains permanent.
- Same key/request returns the stored result; different request returns `409 IDEMPOTENCY_CONFLICT`.
- A timeout is resolved by replaying the same key, never inventing another capsule/blob.
- Finalize and logout are intrinsically idempotent as well.

## 11. Error contract

Errors contain `type`, `title`, HTTP `status`, stable `code`, redacted `detail`, `request_id`, boolean `retryable`, and optional `fields`.

Required stable codes:

- `AUTH_INVALID`, `AUTH_EXPIRED`, `SESSION_REPLAYED`, `RATE_LIMITED`;
- `EMAIL_UNAVAILABLE`, `HANDLE_INVALID`, `HANDLE_UNAVAILABLE`, `HANDLE_NOT_FOUND`;
- `RECIPIENT_NOT_CONFIRMED`, `RECIPIENT_KEY_STALE`, `KEY_BUNDLE_INVALID`, `KEY_BUNDLE_NOT_FOUND`, `KEY_BUNDLE_REVOKED`;
- `CAPSULE_NOT_FOUND`, `CAPSULE_STATE_INVALID`, `DRAFT_EXPIRED`;
- `BLOB_NOT_DECLARED`, `BLOB_SIZE_INVALID`, `BLOB_HASH_MISMATCH`, `BLOB_CONFLICT`;
- `STATEMENT_INVALID`, `SIGNATURE_INVALID`, `ENVELOPE_INVALID`, `FINALIZE_CONFLICT`;
- `IDEMPOTENCY_CONFLICT`, `PROTOCOL_UNSUPPORTED`, `VALIDATION_FAILED`, `INTERNAL_ERROR`.

Unexpected crypto/parsing detail is not returned. `retryable` is true only for transient server/network classes, never integrity failures.

## 12. MVP limits

| Item | Limit |
| --- | --- |
| Email | 254 UTF-8 bytes after normalization |
| Password | 12–128 Unicode code points; no silent truncation |
| Handle | 3–30 ASCII characters |
| Note | 1,000 UTF-8 bytes |
| Optional place label | 120 UTF-8 bytes |
| Photos | exactly 3–5 |
| Normalized photo | JPEG, max long edge 2560 px, max plaintext 8 MiB |
| Encrypted photo | max 8 MiB plus protocol overhead |
| Recognition manifest ciphertext | max 1 MiB |
| Content manifest ciphertext | max 64 KiB |
| Recipient envelope ciphertext | max 16 KiB |
| Total capsule ciphertext | max 42 MiB |
| Draft lifetime | 7 days |
| Incoming page | default 50, max 100 |

Image normalization strips EXIF. Limits are centralized in shared fixtures and checked independently by Android and server.

## 13. Compatibility and test fixtures

Normative implementation assets are versioned `.proto` files plus checked-in non-secret fixtures for UUID/AAD encoding, deterministic protobuf bytes, test Tink keysets, artifact ciphertext/decryption, HPKE envelope/context, publish signatures, and malformed/unknown-version cases.

Android consumes all fixtures. Backend tests parse/verify only public statements/signatures and never receive decrypting keys. Any wire-affecting change requires a new protocol version or a proven backward-compatible field addition.
