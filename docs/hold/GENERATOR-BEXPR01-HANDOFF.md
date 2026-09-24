# Handoff — BER1 BEXPR01 expression artifact (ADR-018 Option B)

Status: **vertical complete and unit-gate-green in release preparation
(`0.2.0-rc.3`): sender session-frozen gate + measured-note fit + receiver
Compose supported/unsupported wiring landed.** End-to-end on unit gates;
device gate OPEN; not merged/tagged/deployed.

## Goal
A real generator: sender's exact BER1 layout is sealed in the capsule and the
receiver reconstructs it, or fails closed. No silent different layout.

## Invariants
- v1 ContentManifest bytes/hash/goldens frozen; v1 decode/render unchanged.
- v2 manifest carries `optional ExpressionV1 expression = 6` inside the same
  AEAD-sealed CONTENT_MANIFEST; bound by the signed statement's manifest
  ciphertext hash. No new artifact kind; no server/AAD/statement change.
- Expression carries only opaque ids/geometry/versions + source bindings
  (ordinal, blob_id, content_id, ORIGINAL content hash, upright dims). No
  ownerId/epoch, no media, no picker URIs.
- `BEXPR01` projection hash binds candidateId + geometry + sources; unknown /
  missing / mismatched v2 → typed reject, render nothing.

## Landed (all gated)
- `docs/decisions/ADR-018-ber1-expression-artifact-v2.md`
- `protocol/proto/.../remanence_v1.proto`: `ExpressionV1/ExpressionSource/
  ExpressionPlacement` + `ContentManifest.expression=6` (additive).
- `core/model/GeneratorExpressionProjection.kt` (`BEXPR01` canonical bytes/hash).
- `core/model/CapsulePhotoIdentity.kt`: `contentIdFor(originalHash)`,
  `photoBlobId(capsuleId, ordinal)` (tag 0x10+ordinal), `blobIdByContentId`,
  `projectedHash(...)` — shared by preview/publish/sealed.
- `core/model/GeneratorBer1Provider.kt` (real G2 provider over
  `GeneratorEditorialRows.plan`; NotApplicable for music/non-empty note).
- `core/model/GeneratorExpression.looksLikeUriOrHandle` (URI/handle reject).
- `core/crypto/ContentManifestCodec.kt`: `buildAndEncryptV2` + version-
  dispatched `decryptAndParse` (v1 byte-identical; v2 parses/reconstructs,
  cross-checks sources vs manifest photos ordinal/blob_id, placements order,
  noteRegion iff note, `validateResolved`, recomputed `BEXPR01` hash).
- `core/crypto/ExpressionReceiverAdmission.kt`: `Supported(expression)` only
  when v2 + valid + note/order/source-consistent; else `Unsupported(reason)`.
- `app/create/CapsulePublisher.kt`: `CapsuleExpressionArtifact` request field,
  emits v2 (`buildAndEncryptV2`) with `CapsulePhotoIdentity` blob ids;
  unique-contentIds guard; redacted `toString`; `validateResolved` before
  encrypt; candidateId ≤256.
- `app/create/GeneratorPreviewLoader.kt`, `app/ui/create/GeneratorPreviewState.kt`
  (state + `GeneratorPreviewCoordinator` + `zeroize()`), `GeneratorBer1Preview.kt`,
  `GeneratorBer1PreviewHost.kt`, `CreateScreen.kt` preview; preview/publish
  content ids unified; preview JPEG zeroized on reset.

## Green gates
- `:core:model:test` 191/22 ; `:core:crypto:testDebugUnitTest` 247/27 ;
  focused `:app:testDebugUnitTest` 15 (Coordinator 6, Loader 3, CreateUiHappyPath 1,
  ContentPublishRecovery 5). Logs `/tmp/opencode/ber1-v2-*.log`.

## Completed in release preparation (0.2.0-rc.3)
1. `CreateViewModel` sender gate — DONE
   - Session-frozen selection (`candidateId`, v2 expression, `projectedHash`)
     is computed from the SAME pre-read ORIGINALS and cleared on photo/note/
     owner/epoch invalidation (`dropGeneratorBound`).
   - At publish, after `bindGeneratorPhotos` freeze, the frozen handoff
     descriptors must be byte-identical and the recomputed `BEXPR01` projection
     must equal the frozen selection; otherwise typed rejection back to CONTENT
     and no `CapsuleExpressionArtifact` is sealed.
   - Non-empty note: the preview host performs the real on-device measurement
     and reports it through freshness-checked `onPreviewMeasured`; the VM
     freezes that measured plan. Missing/stale measurement or an incompatible
     mix is a typed rejection (no silent v1 fallback). Notes that do not fit the
     fixed ADR-017 region cannot publish.
2. Receiver Compose (`ui/capsule` presentation) — DONE
   - `PreparedPresentationMaterial`/readers expose `presentationAdmission`
     (`LegacyV1` | `Ber1(expression)` | `Unsupported(reason)`).
   - `CapsuleRoute`: `LegacyV1` = unchanged v1 photo pager; `Ber1` = exact
     sealed layout via `Ber1PresentationState` + the shared renderer host;
     `Unsupported` = typed notice, NOTHING rendered, no photo reads.
   - v1 (`protocolVersion == 1`) decode/render unchanged; v2 malformed/unknown
     fails closed at preparation.
3. Tests — DONE
   - `:core:model:test` 191/22, `:core:crypto:testDebugUnitTest` 248/27,
     `:app:testDebugUnitTest` 1039/146 (2 environment skips, 0 fail/error),
     server `pytest` 470 passed / 318 skipped / 0 failed, and
     `:app:assembleDebug` BUILD SUCCESSFUL (`0.2.0-rc.3` code 24 APK).

## Notes / risks
- Preview shows normalized downscaled bitmaps but identity uses ORIGINAL
  descriptors (`CapsulePhotoIdentity`); never hash normalized bytes.
- Server needs no runtime change (outer artifact kinds/AAD/statement
  unchanged); only the shared proto's generated Python module is regenerated.
- Caps unchanged (expression ≪ 65,536 content-manifest ciphertext cap).
- Non-empty note publishing requires a real measured fit; long notes are
  typed-incompatible. Device font/pixel acceptance remains OPEN.
- ADR-017/018 are Proposed; no main/deploy/release/device PASS.
