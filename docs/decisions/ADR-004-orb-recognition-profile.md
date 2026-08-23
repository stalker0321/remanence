# ADR-004: ORB as the MVP local-feature profile

Status: Accepted as initial profile; thresholds require M3 calibration

## Context

Recognition must run locally across tens/hundreds of routed postcards and distinguish duplicate printed fronts by damage-tolerant back features. Fingerprints are synchronized encrypted and should remain compact.

## Decision

- Use perspective-normalized ORB with Hamming KNN, ratio and mutual checks, homography/RANSAC, spatial coverage, and fail-safe score/margin gates.
- Keep all parameters in versioned `mvp-orb-v1` profile data.
- Require both sides; weight back more strongly; require strong back evidence for duplicate-front groups.
- Use explicit plausible-candidate chooser rather than lowering automatic thresholds.

## Alternatives

- SIFT initially: deferred; potentially more robust but materially larger/slower descriptors.
- Dual ORB/SIFT fallback: rejected; doubles format/testing/tuning complexity before evidence.
- Global perceptual hash only: rejected; cannot identify physical instances and is fragile to postal changes.
- Microscopic print-defect identification: rejected; unnecessary given prepared back and manual fallback.

## Consequences

Initial numbers are seed thresholds, not claims. M3 must tune a locked physical-instance dataset. If ORB misses the acceptance target, an ORB-vs-SIFT experiment and new ADR/profile version precede any switch.
