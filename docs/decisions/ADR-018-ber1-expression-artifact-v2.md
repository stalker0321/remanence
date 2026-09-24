# ADR-018: authenticated BER1 expression artifact (ContentManifest v2)

Status: Proposed — additive, implemented end-to-end in release preparation
(sender session-frozen selection + exact original-descriptor `BEXPR01` gate +
measured-note fit; receiver `Supported`→exact render / `Unsupported`→render
NOTHING) behind v1 compatibility; receiver policy owner-set; NOT merged,
tagged, deployed, or device-verified.

Date: 2026-09-24

## Context

The v1 capsule wire is ordered JPEG photos + optional note + a nullable
`TrackAttachment` that MUST be absent. It cannot carry the resolved BER1
composition, so a recipient cannot reconstruct the sender's preview layout.
Owner/orchestrator selected **Option B** (additive authenticated/encrypted
expression artifact) and set the receiver policy.

## Decision (Option B)

`ContentManifest.protocol_version` may be `1` (legacy, byte-frozen) or `2`.
A v2 manifest carries `optional ExpressionV1 expression = 6`; v1 manifests
never set it and their bytes/hash/goldens stay byte-identical.

- **Confidentiality & integrity**: the expression is nested inside the same
  AEAD-sealed `CONTENT_MANIFEST` artifact, so it inherits the canonical AAD
  (`protocol_version, capsule_id, blob_id, kind=CONTENT_MANIFEST, ordinal=-1,
  sender_user_id, recipient_user_id`) and is covered by the content-manifest
  ciphertext SHA-256 already recorded in the signed `PublishStatement`. No new
  artifact kind, no statement/AAD change, no server wire change.
- **Versioning**: `ExpressionV1.expression_version == 1`. Unknown/invalid
  versions fail closed (typed `Unsupported`/`Rejected`). Global v1 manifest
  version remains `1`; only a v2 manifest may carry an expression, and a
  `protocol_version == 1` manifest that carries field 6 is rejected rather
  than silently dropping it (v1 bytes/hash/goldens stay byte-identical).
- **Source binding**: `ExpressionV1.sources[] = {ordinal, blob_id (16-byte
  UUID), content_id}`. On receive this MUST exactly match the manifest photos
  by ordinal and blob_id, and the placement/source `content_id` set MUST match
  one-to-one in authored order. `candidate_id` <= 256 chars; `expression_hash`
  is lowercase hex SHA-256 of the reconstructed expression's canonical bytes.
- **Receiver policy (v2)**: reconstruct a G1 v2 `ResolvedExpression`, run
  `validateResolved`, require `canonicalHash == expression_hash`, and require
  `noteRegion` iff the note is non-empty. Valid → render the BER1 layout from
  the sealed expression + ordered originals. Absent/mismatched/unknown/invalid
  → typed `Unsupported`/`Rejected` and render **NOTHING** (never fall back to
  individual photos). v1 manifests/capsules decode/render unchanged.
- **Publish gate**: the sender freezes the selected BER1 expression from the
  exact PRE-READ originals; a non-empty note is admitted only from the host's
  real on-device measured fit (no assumed fit). Publish is admitted only when
  the frozen handoff descriptors are byte-identical to the session-frozen
  selection and the recomputed `BEXPR01` projection hash (candidateId +
  geometry + original descriptors + encrypted photo blob ids) equals the
  session-frozen value. Photo/note/owner/epoch edits invalidate the selection;
  a missing/stale measurement or any mismatch is a typed rejection, never a
  silent different layout.
- **Caps**: no cap change. BER1 expression is far below the existing
  `content_manifest_max_ciphertext_bytes = 65,536`; `protocol_version=2` is
  inside ciphertext and invisible to the server, which continues to validate
  only outer artifact kinds/versions/sizes. Strict server allow-lists already
  reject unknown outer kinds/fields.
- **Privacy**: the artifact carries only opaque ids/geometry/versions (no
  media, no handles, no plaintext). No plaintext expression/photo disk cache;
  preview plaintext bitmap/JPEG bytes are zeroized on selection reset.

## Receiver-recomputable projection hash (BEXPR01)

The G1 `canonicalHash(expression)` includes `GeneratorInput.ownerId/epoch`,
which are not on the wire. v2 therefore binds a distinct, versioned,
receiver-recomputable projection hash that EXCLUDES ownerId/epoch:

```
magic              "BEXPR01" (7 bytes, US-ASCII)
projectionVersion  i32 = 1
canvasVersion      i32
grammarId          str
grammarVersion     i32
branchId           str
noteTreatment      str
fontVersion        i32
paletteVersion     i32
noteRegion         present:byte then x:y:w:h:i32×4 iff present
candidateId        str
sources[]          count:i32, authored order, per source:
                     ordinal:i32 contentId:str contentHash:str
                     widthPx:i32 heightPx:i32 blobId:16 bytes
placements[]       count:i32, authored order, per placement:
                     contentId:str x:i32 y:i32 w:i32 h:i32
                     contentRect present:byte then x:y:w:h:i32×4 iff present
```
All integers big-endian; strings int32-BE length + UTF-8 (identical writer to
G1). `projectionHash` = lowercase-hex SHA-256 of exactly these bytes and
binds `candidateId`. Receiver reconstructs a G1 v2 `ResolvedExpression` from
the artifact + manifest note (using a fixed placeholder owner/epoch for
structural validation only) and recomputes this hash; sender computes the
identical projection from the session expression + source blob ids. `sources`
carry G1 upright dims and the ORIGINAL content SHA-256; `blobId` binds to the
exact encrypted photo blobs. Size: < ~2 KiB, far under the unchanged
`content_manifest_max_ciphertext_bytes = 65,536`. A BEXPR01 golden is checked
in; the G1 v1 expression golden is unchanged.

## Consequences

New clients fail closed on unverifiable v2 rather than mis-rendering; v1
byte/hash/goldens and v1 decode paths are untouched, and the change is
additive and rollback-safe. Server outer artifact kinds/AAD/statement framing
are unchanged because the expression is bound by the content-manifest
ciphertext hash already present in the signed `PublishStatement`; the outer
statement independently binds sender/epoch/nonce, so there is no cross-message
replay. Implementation is staged: (1) proto + projection + codec + tests;
(2) publisher/statement binding; (3) sender G2 selection + publish gate;
(4) receiver parse/validate/render or typed unsupported; (5) preview
zeroization + round-trip/tamper/mismatch/retry tests.
