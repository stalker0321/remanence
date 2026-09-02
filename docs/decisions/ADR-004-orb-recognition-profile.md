# ADR-004: ORB as the MVP local-feature profile

Status: Accepted as initial profile; FRONT-only identity requirements are
defined by ADR-012; thresholds require M3 calibration

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
