# Real Block Store probe design

Status: Design only; implementation pending; no RV-01 evidence

This document defines the smallest real-API extension of the throwaway REC-01
probe. It does not select a recovery primitive, implement product recovery, or
make a capability GO claim. The current app remains a fake/local dry-run until
this design is separately implemented, reviewed, and physically exercised.

The design is bounded by [R0](../../../docs/decisions/R0-durable-account-recovery-decision-packet.md),
[RV-01](../../../docs/decisions/RV-01-google-provider-capability-probe-packet.md),
and [RV-02](../../../docs/decisions/RV-02-recovery-protocol-contract.md). RV-01
is a provider-capability probe only. RV-02's production `RecoveryPackage`,
server authorization, identity, device, and purge contracts are not implemented
here.

## Non-negotiable two-artifact boundary

The probe has two deliberately independent artifacts:

| Artifact | Transport/storage | Contents | Security role |
| --- | --- | --- | --- |
| `U` | Google Block Store | A fresh random 32-byte value only | The candidate-held unwrap material under test |
| `P` | User-mediated Storage Access Framework (SAF) | A bounded opaque authenticated sidecar | A probe fixture emulating server-held ciphertext |

Block Store stores only `U` under a fresh unique opaque per-run key `K_U`. `U`
is exactly 32 random bytes. `K_U` is exactly 16 fresh random bytes encoded as
unpadded Base64URL, producing exactly 22 printable ASCII characters matching
`[A-Za-z0-9_-]{22}`. It is a bounded `String`, not raw bytes, URI text, an
email, a provider subject, an account identifier, or an account namespace.

The independent trusted controller fixture creates and retains the exact
`K_U` string alongside the synthetic case record. It supplies that string as
non-secret test metadata to every explicit `storeU`, `retrieveU`, and `deleteU`
call, including after process death, reinstall, and D2 restore. On D2 the
controller re-supplies the same fixture value; the app never regenerates it,
learns it from `P`, or recovers it from a URI. Possession of `K_U` identifies
only a provider slot; without the provider-returned `U` it cannot unwrap
anything. The controller may retain `K_U` in its test-only case manifest or
in-memory fixture, but it must never place it in `P`, logs, evidence, a
provider subject/email, an account namespace, or a URI.

Enforce the provider limits before every operation:

```text
MAX_ENTRY_COUNT = 16       // maximum distinct stored Block Store entries
MAX_SIZE        = 1024     // maximum bytes in any stored Block Store value
```

`MAX_ENTRY_COUNT` is the maximum number of distinct stored entries for the
provider. Every local store/retrieve/delete request must contain no more than
16 keys, regardless of the provider's existing history. This probe uses one
entry and stores 32-byte `U`; it rejects any request with more than 16 keys or
any Block Store value over 1024 bytes. Do not call `retrieveAll` or
`deleteAll`.

Block Store must never store the canary, plaintext, `P`, a ciphertext package,
an account identifier, an email, a Google subject, or any provider token.

`P` is selected and transported by the operator through SAF. SAF is an
untrusted byte source and `P` is only a probe fixture standing in for an
opaque server-held ciphertext. It is not a recovery secret, manual fallback,
device-transfer mechanism, provider evidence, or production `RecoveryPackage`.
The probe must never describe successful `P` transport as Block Store restore,
server availability, or end-to-end recovery. The user may retain, copy, move,
or lose the sidecar; the app cannot guarantee erasure of that external copy.

No human-entered secret, copied `U`, QR value, clipboard value, D1 private-app
export, or D2D/cable transfer may supply `U`, `P`, or recovery capability. A
candidate's own D2D restore may be tested only as the explicitly optional
P5-D2D provider-coverage case below. Remanence keys, ARK, user data, backend,
production OAuth credentials, and production accounts are never permitted.

## Trusted synthetic context

Unwrap receives a trusted `ExpectedContext` selected by the test scenario
controller outside `P`. It is never reconstructed from sidecar claims. The
fixture contains exactly these synthetic fields:

```text
ExpectedContext {
    accountBindingClass: A | B       // synthetic test class, not an identity
    profile: BLOCKSTORE_U_PLUS_P     // fixed enum
    profileVersion: 1                 // fixed integer for this design
    purpose: BLOCKSTORE_REC01         // fixed enum, not free-form text
    runId: fresh random 16-byte value
    generation: G1 | G2               // trusted fixture value only
    targetRole: D1_SOURCE | D2_TARGET
}
```

The version-1 canonical encoding is fixed-width, big-endian, and exactly 50
bytes:

```text
magic             4 bytes: ASCII "EC01"
accountClass      1 byte:  0=A, 1=B
profile           1 byte:  1=BLOCKSTORE_U_PLUS_P
profileVersion    2 bytes: unsigned 1
purposeLength     1 byte:  unsigned 16
purpose           16 bytes: strict UTF-8 for ASCII "BLOCKSTORE_REC01"
runId             16 bytes: fresh random opaque value
generation        8 bytes: unsigned 1=G1 or 2=G2
targetRole        1 byte:  1=D1_SOURCE, 2=D2_TARGET
```

There are no optional fields or field tags in this version. Duplicate fields,
unknown enum values, unknown fields, noncanonical lengths, and trailing bytes
are rejected. The purpose bytes must decode as strict UTF-8 and equal the
fixed purpose; invalid UTF-8 is rejected, not replaced. No sidecar-provided
field can replace a trusted field. The mandatory `claimedContext` field in `P`
is parsed only for bounded framing and consistency; it is never used to select
the account, generation, purpose, role, profile, `K_U`, or unwrap key. The
trusted canonical encoding, not the claim, supplies AEAD AAD.

`U` is used as the AES-256-GCM test key only inside the probe process. The
32-byte random canary is the plaintext held in memory for a run. A successful
check means only that the exact `U`, exact `P`, and independently selected
`ExpectedContext` combine locally; it is not an ARK or production recovery
result.

## Sidecar `P` fixture

`P` is a probe-only, versioned authenticated package. It must be opaque to the
SAF provider and must not be called a production recovery package. The
implementation may use the existing authenticated-package seam, but it must
enforce this bounded fixture shape before allocation or decryption:

```text
magic              4 bytes: ASCII "RVP1"
formatVersion      2 bytes: unsigned 1
contextLength      2 bytes: unsigned 50
claimedContext     exactly 50 bytes: version-1 ContextV1 encoding
nonceLength        1 byte:  unsigned 12
nonce              exactly 12 bytes
ciphertextLength   2 bytes: unsigned 48
ciphertext         32-byte canary plus exactly one 16-byte AEAD tag
end                exact end of input; no trailing bytes
```

Version-1 `P` is exactly 121 bytes and is subject to
`MAX_SIDECAR_SIZE = 1024` before allocation. Reject truncation, overlong,
negative, or overflowing lengths, unknown versions, duplicate or ambiguous
metadata, invalid enum values, invalid UTF-8 in the context claim, trailing
bytes, nonce/tag length errors, and AEAD failure before returning plaintext.
The exact canonical `ExpectedContext`, not `claimedContext`, supplies AAD. A
version/profile/purpose/generation/role mismatch is a bounded integrity/format
rejection.

The sidecar reader must accept only bounded bytes from a user-selected SAF
document. It must not use a URI authority, URI string, display name, path,
MIME type, provider package, provider account, or document metadata for
security binding. Those values must not enter logs or evidence JSON. The
reader must not automatically delete or rewrite the selected external file.

For P5/P8, the operator-carried sidecar must come through a controlled fixture
classified only as `LOCAL`, `USB`, or `WORKSTATION`. That fixture is separate
from the Google account/cloud path under test: do not use a Google Drive or
same-account cloud document provider as the sidecar transport. Record only
the coarse fixture class. A SAF URI authority is never proof that Block Store,
Google cloud backup, or a server supplied `P`.

## Block Store API seam

The future real bridge uses the exact reviewed dependency:

```text
com.google.android.gms:play-services-auth-blockstore:16.4.0
```

The bridge is the only future code allowed to call the provider. Its bounded
operations are:

```text
Blockstore.getClient(Context): BlockstoreClient
BlockstoreClient.isEndToEndEncryptionAvailable(): Task<Boolean>
BlockstoreClient.storeBytes(StoreBytesData): Task<Integer>
BlockstoreClient.retrieveBytes(RetrieveBytesRequest): Task<RetrieveBytesResponse>
BlockstoreClient.deleteBytes(DeleteBytesRequest): Task<Boolean>
```

Before acquiring the client, check Google Play services presence with
`GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)`
and require `ConnectionResult.SUCCESS`. Missing or unusable Play services map
to `UNAVAILABLE`; the probe must not infer availability from a package name or
version string.

The documented source-app baseline is Android API 23+. For cloud-restore
targets, the documented guidance distinguishes Pixel API 29+ from other
vendors API 31+. These are eligibility filters to record, not capability
proof. The cloud path still requires a qualifying PIN, pattern, or password
screen lock and an actual successful
`isEndToEndEncryptionAvailable(): Task<Boolean>` result. A false, timed-out,
cancelled, or unrecorded result fails the cloud tuple. No Android/API version
inference can replace the observed Task result.

Enforce `MAX_ENTRY_COUNT = 16` as the maximum distinct stored-entry count, keep
every local request at no more than 16 keys, and enforce `MAX_SIZE = 1024` for
every request and response before invoking or accepting a provider operation.
`MAX_SIZE` applies to each Block Store value, including `U`; `P` has its
separate `MAX_SIDECAR_SIZE = 1024` bound. The probe sends one key and one
32-byte value.

For a cloud candidate, the bridge must first observe
`isEndToEndEncryptionAvailable() == true` and a qualifying PIN, pattern, or
password screen lock. Biometric-only state is non-qualifying. It then builds
the one-key operation as follows:

```text
StoreBytesData.Builder
    .setBytes(U)
    .setKey(K_U)
    .setShouldBackupToCloud(true)
    .build()

RetrieveBytesRequest.Builder
    .setKeys(listOf(K_U))
    .build()

DeleteBytesRequest.Builder
    .setKeys(listOf(K_U))
    .build()
```

`RetrieveBytesResponse` data is treated as provider-returned raw app-supplied
bytes; the exact `BlockstoreData.getBytes()` result is copied into bounded
memory and passed to the unwrap seam. The bridge must not derive an account
namespace from `K_U`, retrieve all keys, or silently try another key. Cleanup
uses the exact `K_U` only and never a delete-all request.

The `storeBytes` completion is recorded according to the current
`Task<Integer>` API result, not according to an older Boolean-shaped sample.
The result is never logged raw. All Task failures, timeouts, cancellation, and
unexpected result shapes map to bounded enums.

### Task ownership and late completion

Each provider Task is owned by one probe operation with a fixed
`TASK_TIMEOUT_MS = 10_000` deadline. The owner attaches separate success,
failure, and cancellation listeners and guards settlement with an atomic
owner token. Cancellation is not delivered through the failure listener; the
cancellation listener is required.

If the deadline or caller/process owner cancels first, the owner atomically
closes the operation and maps it to `RETRYABLE_UNAVAILABLE`. Any later success,
failure, or cancellation callback is ignored for state, evidence, logging, and
cleanup decisions. It must not turn a timed-out operation into `PASS` or
overwrite a subsequent operation's result. If the provider operation may have
committed despite timeout, the controller reruns the same case with the same
fixture-held `K_U`; it does not generate a second key or infer the commit.
Provider cancellation is handled by its cancellation listener and has the
same bounded outcome. There is no blocking wait or unbounded listener.

### Explicit U/P seam (replace the single-artifact seam)

The current `CandidateBackend` in `ProbeAdapters.kt` is a fake/local dry-run
seam only. The real Block Store path must bypass or replace it. In particular,
it must never reuse `capabilityMaterial()`, `storeOpaque()`, or
`retrieveOpaque()` to carry an ambiguous object. That old seam would send `P`
to Block Store and make the wrong object the P7 target.

The future implementation has separate interfaces with separate ownership:

```text
BlockStoreUStore
    storeU(K_U: String, U: ByteArray): TaskResult
    retrieveU(K_U: String): TaskResult<exact provider U bytes>
    deleteU(K_U: String): TaskResult

SidecarPTransport
    createP(canary: ByteArray, U: ByteArray, ExpectedContext): P bytes
    readP(operatorFixture: SAF fixture): TaskResult<exact SAF P bytes>
```

`TaskResult<T>` is a probe-owned sealed result wrapper around one provider or
sidecar operation; it is not a Google `Task` or any other Google API type. Its
only transport variants are `COMPLETED(value)`, `UNAVAILABLE`,
`RETRYABLE_UNAVAILABLE`, `INCOMPLETE`, and `INDETERMINATE`. The wrapper owns
the timeout/cancellation settlement described below; framing and AEAD code
maps a completed value to the fixed probe outcomes separately.

`storeU` validates one 32-byte value and the controller-supplied 22-character
key before calling `storeBytes`; `retrieveU` accepts only the bounded exact
`BlockstoreData.getBytes()` result; `deleteU` issues only the exact-key
request. `SidecarPTransport` never calls Block Store and never accepts a URI,
name, MIME type, provider identity, or sidecar claim as trusted context.
`TwoArtifactProbeRunner` receives the outputs of these separate seams and
passes `U`, exact `P`, and controller-selected `ExpectedContext` explicitly to
the probe-only unwrap function.

The fake backend may retain its old `storeOpaque` seam for the already-gated
local dry-run tests, but it must not be adapted to represent the real provider
path. A real-provider test is invalid if Block Store receives anything other
than `U`.

### Official documentation inconsistencies to preserve in review

The implementation writer must verify the API reference and keep these
known inconsistencies visible in code review:

1. The Android guide examples show `StoreBytesData.Builder.setKeys(...)`,
   while the current API reference uses `setKey(String)`.
2. Guide sample result handling differs from the current `storeBytes` return
   type `Task<Integer>`.
3. One guide statement says Android 9/API 29; Android 9 is API 28. The probe
   must use observed API/GMS behavior and the actual E2EE result, never infer
   capability from that statement.
4. Documented cloud restore support and device/API requirements vary by
   device family. Record the actual OEM/API/GMS tuple and use the documented
   Backup Now flow; do not generalize one successful device.
5. `setShouldBackupToCloud(false)` or leaving cloud backup unset can remove
   previously cloud-backed bytes on a later sync. A cloud case must set the
   intended value explicitly and record the setting.

The probe must use listeners or a reviewed coroutine adapter for these
asynchronous Tasks. Cancellation, timeout, and process death must not become a
success or an unbounded wait.

Official primary sources:

- [Block Store overview](https://developer.android.com/identity/block-store)
- [Block Store restore-flow testing](https://developer.android.com/identity/block-store/testing-restore-flows)
- [Blockstore API](https://developers.google.com/android/reference/com/google/android/gms/auth/blockstore/Blockstore)
- [BlockstoreClient API](https://developers.google.com/android/reference/com/google/android/gms/auth/blockstore/BlockstoreClient)
- [StoreBytesData.Builder API](https://developers.google.com/android/reference/com/google/android/gms/auth/blockstore/StoreBytesData.Builder)
- [RetrieveBytesRequest.Builder API](https://developers.google.com/android/reference/com/google/android/gms/auth/blockstore/RetrieveBytesRequest.Builder)
- [DeleteBytesRequest.Builder API](https://developers.google.com/android/reference/com/google/android/gms/auth/blockstore/DeleteBytesRequest.Builder)
- [Google Play services setup](https://developers.google.com/android/guides/setup)
- [GoogleApiAvailability API](https://developers.google.com/android/reference/com/google/android/gms/common/GoogleApiAvailability)
- [ConnectionResult API](https://developers.google.com/android/reference/com/google/android/gms/common/ConnectionResult)
- [Google Tasks API](https://developers.google.com/android/reference/com/google/android/gms/tasks/Task)
- [SAF document providers](https://developer.android.com/guide/topics/providers/document-provider)
- [SAF document access](https://developer.android.com/training/data-storage/shared/documents-files)

## Probe procedures

### P1–P3: local two-artifact proof

1. The independent controller fixture creates and retains the exact
   22-character `K_U`, fresh `runId`, and `ExpectedContext`. The probe app
   independently creates the fresh random 32-byte canary and fresh random
   32-byte `U`; the controller never receives either sensitive value. The
   fixture keeps `K_U` available for every later retrieve.
2. Store exactly `U` in Block Store at `K_U`. Construct `P` by authenticating
   the canary with `U` and the trusted context as AAD. SAF may transport `P`
   only as an opaque fixture.
3. Retrieve exactly `K_U`, read the exact sidecar bytes, and unwrap with the
   externally supplied expected context. Compare the result to the in-memory
   canary, then zero plaintext and intermediate material.

Required local outcomes:

| Inputs | Required result |
| --- | --- |
| Correct `U` + correct `P` + matching trusted context | Success and in-memory canary equality |
| `U` only because `P` was not supplied | `INCOMPLETE`; no unwrap |
| `P` only because `U` was not supplied | `INCOMPLETE`; no unwrap |
| Correct `U` + missing/inaccessible `P` | `INCOMPLETE`; no fallback |
| Correct `P` + provider reports U capability unavailable | `UNAVAILABLE`; no fallback |
| Correct `P` + transient U retrieval interruption | `RETRYABLE_UNAVAILABLE`; no fallback or success |
| Any malformed, swapped, tampered, or mismatched input | Fail closed before plaintext |

`P` selection or loss never creates a manual recovery path. The local result
is tagged `LOCAL_DRY_RUN_NOT_EVIDENCE` unless it is part of the separately
recorded physical RV-01 matrix; even then the SAF half remains a harness
fixture, not proof of server behavior.

### Oracle boundary: pre-wipe P3 versus post-wipe P4/P5/P8

P3 is the only pre-wipe byte-equality oracle: the source process still holds
the ephemeral canary, so authenticated open must produce exactly that canary
and the probe compares the bytes in memory before zeroization. P4, P5, and P8
are post-death/reinstall/D2 oracles. The original canary and `U` are absent
from the controller fixture; the fixture contains no canary or `U`. Success is
only authenticated AEAD-open of exactly 32 plaintext bytes using the exact
Block Store-retrieved `U`, operator-carried `P`, and independent trusted
`ExpectedContext`, followed immediately by zeroization. There is no comparison
to a controller canary. No post-wipe state-machine transition may require
`CANARY_EQUAL`; that condition exists only for pre-wipe P3.

### P4/P5/P8 complete local pass rule

`PASS` for P4, P5, or P8 is not awarded for provider retrieval alone. The
complete sequence must succeed on the tested target:

1. The independent controller supplies the exact fixture-held `K_U` string as
   non-secret test metadata, including after reinstall, process death, or D2
   restore; the target calls only `retrieveU(K_U)`.
2. The target receives the exact opaque bytes from the operator-controlled SAF
   fixture and strictly parses `P` without trusting its claims.
3. The target receives the independently selected trusted `ExpectedContext`
   from the controller, never from `P`.
4. The target performs authenticated AEAD-open using the exact Block Store-
   retrieved `U`, exact operator-carried `P`, and trusted expected context. It
   accepts only exactly 32 plaintext bytes and immediately zeroizes those bytes
   and all intermediate material. It does not compare against a controller
   canary: after death/reinstall/D2, the original canary and `U` are absent
   from the controller fixture, and neither belongs in that fixture.

P4 performs this sequence after same-device uninstall/reinstall. P5 performs
it on fresh D2/A after the applicable Block Store cloud restore. P8 performs
it on fresh D2/A after D1 is wiped and unavailable, again using the applicable
cloud path. P5/P8 sidecar transport is always the separate local/USB/
workstation fixture defined above.

The only permitted success label is the fixed outcome
`U_DURABILITY_PLUS_HARNESS_P`; its tuple result may be `PASS` and its evidence
class remains `LOCAL_DRY_RUN_NOT_EVIDENCE` until the physical RV-01 record is
accepted. This label means U durability plus harness-P authenticated open of
exactly 32 bytes followed by immediate zeroization only. It never means server retrieval, server availability, end-to-end
recovery, takeover resistance, or capability GO.

### P6: full A/B cross matrix

Use independent synthetic A and B contexts, independent run IDs, independent
sidecars, independent Block Store keys/values, and controller-held `K_U_A`/
`K_U_B` values. The suffixes are test-case labels only; actual keys remain
random 22-character strings. The expected context is selected by the
independent trusted controller fixture, never read from `P`.

At minimum, execute and require fail-closed for:

| Block Store value | Sidecar | Trusted expected context | Required result |
| --- | --- | --- | --- |
| `U_A` | `P_A` | A | Success only when all other fields match |
| `U_B` | `P_B` | B | Success only when all other fields match |
| `U_A` | `P_B` | A | Failure |
| `U_B` | `P_A` | B | Failure |
| `U_A` | `P_A` | B | Account-binding failure |
| `U_B` | `P_B` | A | Account-binding failure |
| `U_A` | `P_B` | B | Failure |
| `U_B` | `P_A` | A | Failure |

The A/B result does not prove Google multi-account isolation. Block Store's
documented app/package/signature isolation is not an application-level
account namespace, so this synthetic matrix is only a local binding test.
The physical P6-A-to-B Google account case remains mandatory.

### Generation rollback dry-run

Use the same probe-only format with trusted fixture generations G1 and G2:

1. Construct valid `P_G1` with trusted `ExpectedContext(generation=G1)` and
   verify the matching `U`/`P_G1` local round trip.
2. Advance the independent fixture to G2 and construct valid `P_G2`.
3. With trusted `ExpectedContext(generation=G2)`, accept `P_G2`.
4. Replay `P_G1` after the fixture is reset/reopened at G2, and reject it.
5. Repeat with a changed generation field, changed profile/purpose, changed
   target role, and a changed run ID. All must reject before plaintext.

The expected G2 comes from the trusted synthetic fixture, not from `P_G1` or
`P_G2`. This dry-run proves authenticated-context crypto rejection only. It
does not prove production server monotonicity, durable server generation
state, provider history integrity, or provider rollback resistance.

### P7: separate tamper procedures

These are separate tuples and must never be collapsed into one P7 result.

#### P7-BLOCKSTORE-U-TAMPER / controlled same-key overwrite

- Retrieve the exact `BlockstoreData.getBytes()` result for `K_U`.
- In a deterministic harness, flip one byte in that exact returned `U` before
  passing it to unwrap, or inject a controlled same-key overwrite with a
  different fresh random 32-byte `U'`, then retrieve the exact current result.
- Attempt to unwrap the unchanged exact `P` with the trusted expected context.
- Require bounded rejection, zero plaintext, and no fallback.
- Delete only `K_U` during cleanup.

This tests the cryptographic consequence of a changed current provider value
and a controlled same-key replacement. It does not prove Block Store provider
rollback behavior or history integrity. No provider rollback is claimed.

#### P7-SIDECAR-TAMPER

- Read the exact bytes returned by the SAF document provider.
- Flip one byte at a fixed valid offset in the in-memory copy.
- Pass those exact mutated bytes, with the same `U` and trusted expected
  context, to the same unwrap entry point used by P3/P5/P8.
- Require bounded authenticated rejection, zero plaintext, and no fallback.

This tests local authenticated sidecar handling only. It is not a Block Store
retrieval object and does not prove provider rollback, server rollback,
server authorization, or ciphertext availability.

If a future candidate advertises a documented provider-side rollback operation,
that operation requires a separately named candidate-specific procedure and
evidence. Neither local tamper tuple is a substitute for it.

### Malicious SAF and bounded parser cases

The fake SAF backend must deterministically provide:

- missing/deleted/inaccessible document;
- provider exception or timeout with no raw message exposure;
- truncated header/body;
- overlong, negative, overflowing, or contradictory lengths;
- unknown format/profile version;
- invalid enum/context encoding;
- duplicate or ambiguous metadata;
- trailing bytes and invalid nonce/tag lengths;
- changed bytes, swapped A/B sidecar, and replayed G1 sidecar;
- misleading URI, display name, path, MIME type, and provider metadata.

Provider/transport failures map to a bounded unavailable/retryable category;
malformed or unauthenticated bytes map to bounded integrity/format rejection.
Neither category tries login, Restore Credentials, D2D, another key, another
sidecar, or a manually entered secret. No URI, name, MIME, provider identity,
provider exception, raw bytes, canary, `U`, plaintext, or traceback is emitted.

### Process death and zeroization

Inject process death or force-stop at each boundary:

1. after `U` store completion;
2. after `P` creation before SAF selection;
3. after SAF selection before validation;
4. after `U` retrieval before unwrap;
5. after authenticated open of exactly 32 bytes before result
   classification/zeroization;
6. after exact-key cleanup request before its Task completes.

After restart, the probe either reruns the case from the external trusted
fixture, with the controller re-supplying the exact same `K_U` string, or
reports bounded incomplete/unavailable. It never persists a canary,
plaintext, ARK-equivalent value, derived key, provider token, readiness marker,
or uncommitted recovery state. Every in-memory copy of the canary, `U`,
plaintext, derived AEAD material, and temporary sidecar bytes is zeroed in a
`finally`-equivalent cleanup path. Zeroization is tested as a local memory
hygiene property, not as a claim about provider or JVM memory erasure.

Provider cleanup is exact-key only: `setKeys(listOf(K_U))`. The implementation
must not invoke `setDeleteAll(true)`, `setRetrieveAll(true)`, or an equivalent
bulk operation. The external SAF sidecar is user-owned test transport; the app
must disclose that copied/exported sidecars cannot be remotely erased.

## Bounded failure mapping

The app emits only fixed enums and never raw exception text:

| Condition | Probe result | Follow-up |
| --- | --- | --- |
| E2EE unavailable, non-qualifying lock, provider absent | `UNAVAILABLE` | Stop cloud attempt; no fallback |
| Task timeout/cancellation or transient provider/SAF interruption before a definitive result | `RETRYABLE_UNAVAILABLE` | Rerun the same case with the same controller-held `K_U`; never report success |
| Controller fixture, exact U, or exact P is not supplied; process dies before unwrap | `INCOMPLETE` | Case is incomplete, not infrastructure failure; supply/reselect the fixture or rerun; never imply eventual progress |
| Wrong A/B context, role, profile, purpose, run, or generation | `REJECTED` | Terminal for tuple |
| Malformed framing or AEAD failure | `FAIL_CLOSED` | Zero plaintext; preserve only enum evidence |
| Tampered U or P rejected | `FAIL_CLOSED` | Expected negative result |
| Tampered U or P accepted | `UNEXPECTED_SUCCESS` | Failing test; never map to `PASS` |
| Correct U/P/context and authenticated open of exactly 32 bytes plus immediate zeroization in P4/P5/P8 | `PASS` with `U_DURABILITY_PLUS_HARNESS_P` label | U durability plus harness-P only; no server/end-to-end/GO claim |
| Correct U/P/context and pre-wipe P3 canary equality in a local fake-only run | `PASS` with `LOCAL_DRY_RUN_NOT_EVIDENCE` | Local behavior only; no provider claim |
| Unknown provider result or raw error shape | `INDETERMINATE` | Stop candidate path; no permissive mapping |

`INCOMPLETE` and `RETRYABLE_UNAVAILABLE` are distinct fixed enum values for
the future two-artifact result type. `INCOMPLETE` means the test inputs or
operator fixture are absent or the process stopped before a decision; it does
not mean a provider failure and does not promise that a repeated call will
make progress. `RETRYABLE_UNAVAILABLE` means a bounded
provider/transport operation had no definitive result and may be retried with
the same controller fixture. Neither value is `PASS`, `FAIL_CLOSED`, or a
fallback. The current f818c4e local `ProbeResult` is not changed by this design;
the future split U/P implementation must add these exact names rather than
silently collapsing them.

The current `EvidenceJson` enum-only restriction remains mandatory. Physical
operator notes may contain the required device/API/account-role tuple, but not
personal identifiers, provider subjects, tokens, raw secrets, package bytes,
URIs, names, provider messages, or tracebacks.

## Minimal code-seam plan (future implementation only)

No code is changed by this design. The future implementation should remain
inside the existing isolated project and use these bounded seams:

| Seam | Responsibility | Forbidden responsibility |
| --- | --- | --- |
| `probe/BlockStoreProviderBackend.kt` | Real 16.4.0 Task bridge; explicit `storeU`/`retrieveU`/`deleteU`; E2EE and Play-services detection; bounded mapping | Account identity, server calls, `retrieveAll`, `deleteAll`, fake success |
| `probe/SafSidecarReader.kt` | User-selected bounded byte read from a coarse local/USB/workstation fixture; no trusted metadata; no auto-delete | Provider identity, fallback secret, server emulation claim |
| `probe/ExpectedContextFixture.kt` | Independent A/B, profile/version, purpose, run, generation, role, and controller-held `K_U` | Deriving context or `K_U` from P, Google subject, email, URI, or account namespace |
| `probe/TwoArtifactProbeRunner.kt` | Correct U/P/context matrix, G1/G2, separated P7 tuples, zeroization | Production recovery/session/device state |
| Existing `probe/AuthenticatedTestPackage.kt` | Probe-only framing/AAD/AEAD with strict bounds | RV-02 `RecoveryPackage` implementation |
| Existing `probe/EvidenceJson.kt` | Fixed enum redaction | Raw output, diagnostic strings, provider metadata |
| Existing `ui/ProbeScreen.kt` | Local harness status and explicit disclaimer | Share-as-recovery UI, server login, provider success claim |

The current `BlockStoreExperimentalAdapter` and `PrfExperimentalAdapter` must
continue to default to unavailable. A real Block Store backend may report
available only after a genuine API result; a fake backend remains test-only.
No real Google SDK belongs in the production `android/app` settings or
dependencies.

## Acceptance matrix

| Layer | Required coverage | Pass means | Does not prove |
| --- | --- | --- | --- |
| Unit, fake-only | U/P success; U-only/P-only; A/B cross matrix; trusted-context mismatch; G1/G2; strict size/framing/version/AAD parsing; U tamper/same-key overwrite; exact sidecar tamper; missing/malicious SAF; bounded failures; exact-key deletion; no bulk delete; zeroization; enum-only redaction | Deterministic local behavior and no secret/log leakage | Google durability, package/signature behavior, server, takeover, provider rollback |
| Android instrumented harness | Real process-death boundaries; fresh random values; exact bytes into unwrap; manifest/no-network isolation; SAF chooser fixture; API Task timeout/cancellation; local UI disclaimer | App lifecycle and platform seam preserve fail-closed rules | Cloud restore or physical provider capability unless the actual provider path is used |
| Physical P1/P4 | Same-signed Play-distributed throwaway app; device/API/GMS tuple; capability detection; reinstall; complete local authenticated-open-32/zeroization sequence | Observed availability and same-device U durability plus harness-P result | Complete-loss recovery, server, account isolation |
| Physical P5-BS-CLOUD | Qualifying PIN/pattern/password; E2EE true; documented Backup Now; factory-reset fresh D2/A; cloud restore; retrieve exact U; operator-controlled local/USB/workstation P; independent context; complete local authenticated-open-32/zeroization sequence | `U_DURABILITY_PLUS_HARNESS_P` only after every step succeeds | SAF/server P durability; takeover resistance; production recovery GO |
| Physical P6-A-to-B | Fresh D1/A and D2/B, independent values/sidecars, A absent on B | No A U unwrap on B; bounded fail closed | General Google multi-account namespace semantics |
| Physical P8-BS-CLOUD | Source D1 wiped/unavailable; fresh D2/A uses applicable cloud restore path; controller re-supplies K_U; operator-controlled local/USB/workstation P; independent context; complete local authenticated-open-32/zeroization sequence | `U_DURABILITY_PLUS_HARNESS_P` only after every step succeeds | Server availability/auth, provider rollback, production monotonicity, full RecoveryPackage |
| Optional physical P5-D2D | Only if the candidate claims D2D support; documented cable/wireless flow | Extra D2D observation | Mandatory cloud baseline; D2D alone is never sufficient |

The applicable cloud path and complete-loss path remain mandatory for a Google
baseline. D2D is optional extra coverage only when explicitly claimed and can
never replace cloud/provider-sync evidence. SAF `P` in P5/P8 is still a
probe-side fixture and means the result is `U` durability plus harness-side
authenticated package handling, not end-to-end server recovery.

## Explicit limits and stop conditions

This design does not and cannot prove:

- server authentication, authorization, availability, package selection, or
  server-held ciphertext durability;
- resistance to Google-account takeover or a malicious authenticated user;
- Google application-level multi-account semantics beyond the required physical
  A/B test;
- provider-side rollback or history integrity;
- production server generation monotonicity;
- Remanence ARK recovery, device enrollment, revocation, purge, or readiness;
- a manual secret, device-transfer, Restore Credentials, passkey-authentication,
  or other fallback;
- an RV-01 capability GO or an RV-02/M4 implementation decision.

Stop the probe and mark the applicable tuple failed/indeterminate if a real
provider bridge cannot retrieve exact U bytes, if E2EE/lock evidence is absent,
if P is used as a recovery fallback, if expected context comes from P, if any
cross-account combination unwraps, if any tampered input succeeds, if a raw
secret/provider/SAF value is logged, or if cleanup requires bulk deletion.

No physical result, local fake result, SAF success, or API availability result
may unblock the recovery release by itself. The M4 recovery release remains
blocked until the final RV-01 matrix satisfies the reviewed R0/RV-01/RV-02
gates.
