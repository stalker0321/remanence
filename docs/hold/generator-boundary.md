# Generator adapter boundary (not yet a wire extension)

Use the existing separate generator. The real entry is between validated CONTENT and `CreateViewModel.startPublishing()`. The current protocol can publish only ordered JPEG photos and a note; it cannot round-trip a resolved composition. No generated-expression UI is enabled until that changes.

## Semantic contract to implement with the generator

| Operation | Required input | Result / ownership |
| --- | --- | --- |
| Generate | Generation/request ID; owner/session epoch; 3–5 stable photo IDs in authored order; original dimensions and client-owned read handles; exact note; sender representation. Music only after its product contract exists. | Bounded candidate set or explicit incompatibility; versioned canvas/grammar/branch, source-ID mapping, placements, crops, masks, type/font assets, palette, validation and dependencies. No credentials or private keys enter the generator. |
| Select | Candidate ID and generation ID | Exact candidate snapshot; previous/next restores it, never silently reruns generation. |
| Correct crop | Selected candidate ID, source ID, normalized crop window within source bounds | Revised candidate plus safety/fit diagnostics; every included object remains represented. Original photo and order stay unchanged. |
| Freeze | Selected validated candidate and content revision | Immutable resolved expression + content/asset hashes + renderer/font/grammar versions. Late generator callbacks from older content/owner epochs are rejected. |
| Publish | Frozen expression, exact ordered originals, existing confirmed recipient snapshot | Existing publication ownership/cancellation fences; new versioned encrypted artifact(s), covered by manifest hashes and recipient verification. No transport via arbitrary preview URLs. |
| Receive | Verified decrypted frozen expression and its declared dependencies | Fit the entire canvas preserving ratio. Open originals in authored order. No regeneration, recropping or new layout decisions at receive time. |

Android maps content URIs to bounded read handles and runs the generator via an adapter. A future web client maps File/Blob references to the same semantics. They need not share implementation code. Crop analysis is a generator concern; CameraX postcard recognition is separate and must not be reused as a photo-composition pipeline.

## Required integration evidence

- Canonical expression serialization, bounded sizes, dependency completeness, content/hash mapping and version rejection fixtures shared across clients.
- Publish → encrypt → transport → verify → decrypt → identical expression test, including optional note absence and mixed photo aspect ratios.
- Selection/crop/freeze invalidation on photo/note edits, owner change, cancellation and process death; sender snapshot never changes mid-publish.
- No plaintext generated previews in durable storage outside the existing owned staging lifecycle. Explicit cleanup policy for originals/derived assets.
- Define note limits against current v1's 1000 UTF-8 bytes; do not adopt the generator's provisional 200–300 character guidance as a new backend rule.
- Define provenance semantics and reader metadata before populating date/sender/share on the underlayer. Current photo viewer has no reliable such fields.

## Enumeration policy (orchestrator ruling, product-authoritative)

- Dependency versions (grammar/font/palette) are strictly positive at
  enumeration: zero and negative versions are rejected; unknown positive
  versions pass through recorded for renderers to interpret.
- `Incompatible` never suppresses later independent providers. An empty
  accepted set with at least one incompatible provider is a typed
  `INCOMPATIBLE` terminal outcome, never an empty success.
- Accepted nested containers (photos, placements, diagnostics) are
  deep-detached from provider-owned lists before accept/freeze.
- Source byte handles (`contentId`→bytes binding) are a mandatory G3
  adapter concern and must never enter G1 canonical bytes/hash.

This document is an interface handoff, not a claim of generator or new protocol support. Current Android continues to publish the existing real photo/note format.

## Device-feedback audit after `v0.2.0-rc.2` (2026-09-23)

The user-facing Generator is **not present** in that APK. `CreateScreen` goes from
3–5 picked photos and a note directly to `CreateViewModel.startPublishing()`.
The C3 `GeneratorCreateBridge` verifies, normalizes, stages, and freezes the
*input sources* inside that publication; its `FrozenHandoff` contains the
canonical `GeneratorInput`, not a selected `ResolvedExpression`. G1 defines
the expression contract and G2 defines provider discovery, but no production
provider, renderer, crop correction, candidate browser, or selection is wired.
Calling C3 a shipped Generator was incorrect.

The existing v1 `ContentManifest` has ordered JPEG photo descriptors and an
optional note only. `CapsulePublishRequest` carries those JPEGs and note, and
the recipient currently opens them as individual photos. An on-device
composition preview alone would therefore misrepresent what the recipient
receives. Do not enable or market a Generator screen until the exact selected
expression survives publish, encryption, transport, verification, decryption,
and rendering. The existing photo/note publication path remains valid and
should be labelled as such.

### Implementable milestones and acceptance gates

1. **Production expression and renderer.** Promote one approved layout/branch
   contract into a real G2 provider. Given 3–5 mixed-aspect originals and an
   optional note, generate a bounded candidate set with complete source-ID
   mapping, placements, crops, note treatment, dependencies, and deterministic
   identities. Render the *whole* 9:16 expression from that frozen metadata;
   use the same renderer on sender and recipient. Test every source represented,
   bounds/fit, stable candidate order, unknown dependency rejection, and
   different phone sizes without re-layout.
2. **Authoring and selection.** Insert a real `GENERATING → BROWSE → SELECTED`
   path after CONTENT. Show complete candidates, previous/next without
   regeneration, persistent selection, source-aware crop correction with
   accessible controls, then explicit publish. A photo/note edit, owner change,
   session exit, or new epoch invalidates the selected expression and cancels
   late callbacks. Keep originals in authored order and all existing recipient,
   capture, and publication guards. Test selection restoration and these races.
3. **Versioned encrypted delivery.** Define a new content-manifest version or
   authenticated expression artifact with canonical bytes/hash, selected
   candidate ID, source mapping and dependency versions. Bind it to the exact
   encrypted originals in the publish statement and acceptance gate; make the
   recipient reject missing/mismatched/unsupported dependencies before showing
   private content. Preserve explicit v1 decode for already-published capsules
   and fail closed on unknown versions. Test tamper, reorder, omission,
   duplicate, and v1 compatibility cases.
4. **End-to-end device gate.** Publish on phone A, scan the matching postcard
   on phone B, and compare the whole selected composition (including crops,
   note and photo order) to sender preview. Exercise retry, process death,
   backgrounding, account switch, cancellation, reduced motion, small/tall
   screens, and no plaintext preview leakage. A local `FrozenHandoff` test or
   screenshot of an untransmitted mockup is not acceptance evidence.

Until all four gates pass, release notes must say “source preparation and
publication bridge” rather than “capsule Generator.”
