# ADR-004: ORB as the MVP local-feature profile

Status: Superseded historical record. ADR-016 is the current production
SIFT/RootSIFT profile contract; the ORB profile is not shipped or callable.
The FRONT-only identity requirements remain defined by ADR-012.

## Context

Recognition must run locally across routed postcards and keep encrypted
fingerprints compact. This ADR retains the `mvp-orb-v1` extraction and scoring
profile for the required FRONT fingerprint. Its earlier side/pair assumptions
are historical evidence only and are superseded by ADR-012.

## Decision

- Use perspective-normalized ORB with Hamming KNN, ratio and mutual checks, homography/RANSAC, spatial coverage, and fail-safe score/margin gates.
- Keep all parameters in versioned `mvp-orb-v1` profile data.
- Use one required FRONT fingerprint per capsule. The manifest format/version
  is explicit solely for fail-closed parsing; it is not an identity-mode
  selector and contains no BACK field.
- Never auto-open multiple owner-scoped candidates; a recipient picker is a
  future milestone. Full E2EE verification remains mandatory after a single
  result or later explicit selection.
- The experimental V2 line locator may supply the first crop proposal, followed
  by the legacy contour and guide fallback through the same warp. Missing V2
  proposals and invalid V2 warps fall through; invalid final warp and empty
  features remain hard failures. Blur, darkness, small-card, and glare
  signals are advisory telemetry and do not short-circuit extraction or
  matching.
- This integration does not change the profile thresholds, strong gate,
  score/margin rules, or cryptographic verification. The debug build may opt
  in with `-Premanence.localization.v2.enabled=true`; release remains false so
  disabling the switch is the rollback path.

## Alternatives

- SIFT initially: deferred; potentially more robust but materially larger/slower descriptors.
- Dual ORB/SIFT fallback: rejected; doubles format/testing/tuning complexity before evidence.
- Global perceptual hash only: rejected; cannot identify physical instances and is fragile to postal changes.
- Microscopic print-defect identification: rejected; the FRONT design-to-many
  relation and future explicit picker are sufficient.

## Consequences

Initial numbers are seed thresholds, not claims. M3 must tune a locked
design-to-many dataset. If ORB misses the acceptance target, an ORB-vs-SIFT
experiment and new ADR/profile version precede any switch. ADR-012 defines the
current identity-mode and candidate-cardinality semantics.
