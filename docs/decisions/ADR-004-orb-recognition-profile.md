# ADR-004: ORB as the MVP local-feature profile

Status: Accepted as initial profile; identity-side requirements superseded by
ADR-012; thresholds require M3 calibration

## Context

Recognition must run locally across routed postcards and keep encrypted
fingerprints compact. This ADR retains the `mvp-orb-v1` extraction and scoring
profile. Its earlier two-sided physical-instance assumptions are historical
v1 behavior and are superseded for new identity semantics by ADR-012.

## Decision

- Use perspective-normalized ORB with Hamming KNN, ratio and mutual checks, homography/RANSAC, spatial coverage, and fail-safe score/margin gates.
- Keep all parameters in versioned `mvp-orb-v1` profile data.
- Use explicit identity-mode rules: legacy two-sided v1 requires both sides;
  v2 `FRONT_ONLY` requires FRONT and does not require BACK. Optional BACK
  disambiguation requires a separately versioned mode.
- Never auto-open multiple owner-scoped candidates; a recipient picker is a
  future milestone. Full E2EE verification remains mandatory after a single
  result or later explicit selection.

## Alternatives

- SIFT initially: deferred; potentially more robust but materially larger/slower descriptors.
- Dual ORB/SIFT fallback: rejected; doubles format/testing/tuning complexity before evidence.
- Global perceptual hash only: rejected; cannot identify physical instances and is fragile to postal changes.
- Microscopic print-defect identification: rejected; unnecessary given the
  optional BACK future disambiguator and manual fallback.

## Consequences

Initial numbers are seed thresholds, not claims. M3 must tune a locked
design-to-many dataset. If ORB misses the acceptance target, an ORB-vs-SIFT
experiment and new ADR/profile version precede any switch. ADR-012 defines the
current identity-mode and candidate-cardinality semantics.
