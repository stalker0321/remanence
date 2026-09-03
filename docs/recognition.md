# Local postcard recognition

Status: **APPROVED architecture checkpoint via ADR-012; `mvp-orb-v1` remains the profile and seed thresholds remain uncalibrated until M3.**

Recognition runs entirely on Android from exactly one required FRONT still
capture. It identifies a design only among capsules routed to the
authenticated recipient. A design can map locally to zero, one, or many
capsules. It is not global image search, object recognition, OCR, AR, or a
cryptographic authentication mechanism. ADR-012 is the current contract; this
document records its FRONT-only implementation at `88fb80d` (M2-F0 closure, see implementation-plan §10a). Physical-device and dataset acceptance remain open.

Test8/Test9 recognition state is disposable development data. A clean app
database and server recognition state are accepted for the breaking reset.

## 1. Design goals

- Prefer false negatives/explicit choice over false positives.
- Use the printed FRONT as the design identity and candidate entry point.
- Keep BACK outside the production recognition contract; captured BACK images
  are ancillary research data only.
- Tolerate angle, scale, rotation, lighting change, crop, added postal marks, dirt, and partial damage.
- Keep raw FRONT images local and short-lived.
- Make every capture, feature, score, and acceptance parameter versioned/configurable.
- Keep the fingerprint algorithm/profile ID separate from the manifest format
  version. `mvp-orb-v1` remains unchanged unless a later ADR changes the
  algorithm/profile.
- Replace sender-before-mail fingerprints with recipient-after-delivery
  fingerprints as the preferred long-term reference without deleting the
  sender fallback.

## 2. Candidate universe

The authenticated device receives encrypted recognition manifests only for
capsules routed to that user. It locally decrypts and indexes their sender
fingerprints under an account-owner-scoped design key without exposing a global
or server visual index.

The local relation is `design -> 0..N capsules`. Zero candidates returns
`NO_MATCH`; one plausible candidate may proceed to full E2EE verification; more
than one is ambiguity and must never auto-open. A recipient picker is a later
UI milestone, not permission to guess. For first receipt, candidates are
pending/known sender fingerprints. For later use, accepted recipient
fingerprints are searched first, with sender fingerprints as fallback when
allowed by the documented FRONT rules.

An unknown postcard that has no capsule in the local index must produce `NO_MATCH`, not a network/global image-search attempt.

The local encrypted `SenderIndexBundle` has one explicit bundle format version
and contains a required FRONT fingerprint per capsule. The design relation and
all candidate queries are scoped to the authenticated owner. The clean reset
permits a new Room/index schema; no migration or compatibility reader is
required.

## 3. Capture sequence

Both sender and recipient use the same still-capture component and profile:

1. Show an adaptive postcard guide and short instruction: 3:2 in landscape
   frames and 2:3 in portrait frames. The guide is the shared normalized
   geometry used by the preview overlay and the still crop at every
   preview/capture aspect ratio.
2. Capture JPEG still with CameraX `ImageCapture`; no continuous `ImageAnalysis`.
3. Apply EXIF orientation, decode a bounded-resolution working bitmap, and strip metadata.
4. Detect the postcard quadrilateral and show the proposed crop briefly. If no
   convex four-point contour is credible, use only the bounded guide-aligned
   central crop; never silently use the full frame. The same blur, exposure,
   glare, and usable-ORB gates apply to either crop source.
5. If automatic corners fail or are visibly wrong, allow manual four-corner correction; do not silently use the full frame.
6. Run quality gates. Failed quality returns a specific recapture instruction.
7. Perspective-normalize and extract a fingerprint.
8. Release/delete the raw capture after the fingerprint has been encrypted/durably staged.

For the production contract, FRONT is required and is the only capture passed
to recognition. BACK is not captured, persisted, encrypted, or used by Create,
Scan, matching, indexing, acceptance, or grants.

## 4. Quality gates and normalization

All thresholds live in one immutable `RecognitionProfile` asset with `profile_id` and semantic version. Database/fingerprint records store that ID.

### Rectangle detection

On a downscaled preview copy:

- grayscale conversion and light denoise;
- adaptive/automatic edge detection;
- morphological close for broken border edges;
- contour enumeration;
- convex four-point polygon approximation;
- rank by area, rectangularity, edge support, and distance from guide overlay.

Initial capture gates for `mvp-orb-v1`:

| Parameter | Seed value |
| --- | ---: |
| Minimum postcard area / frame area | 0.35 |
| Minimum short edge after warp | 600 px |
| Canonical long edge | 1600 px |
| Maximum corner outside frame | 0 px |
| Accepted aspect ratio | 1.15–2.20, either orientation |
| Minimum contour ranking confidence | 0.70 |
| Minimum Laplacian variance on canonical grayscale | 80 |
| Maximum near-black pixel fraction | 0.25 |
| Maximum clipped-white pixel fraction | 0.20 |
| Maximum contiguous glare-region fraction | 0.12 |

These are initial device-independent approximations, not product truth. The quality evaluator returns measured signals and reason codes such as `CARD_TOO_SMALL`, `CROP_UNCERTAIN`, `ANGLE_UNCERTAIN`, `RESOLUTION_INSUFFICIENT`, `TOO_BLURRY`, `TOO_DARK`, or `GLARE_EXCESSIVE`.

### Canonical image

- Order corners consistently clockwise from top-left.
- Preserve the detected aspect ratio; do not stretch every postcard to a fixed ratio.
- Warp perspective so the long edge is 1600 px.
- Produce canonical grayscale for features plus a small normalized luminance summary for diagnostics.
- Apply CLAHE only as an explicitly versioned extraction branch. `mvp-orb-v1` extracts ORB from both normal grayscale and CLAHE grayscale, deduplicates nearby keypoints, ranks by response/spatial distribution, and caps the merged result at 1,500 features for low-texture print regions.
- Coordinates stored in fingerprints are normalized to `[0,1]` relative to canonical width/height, so geometry is resolution-independent.

## 5. ORB versus SIFT decision

MVP selects ORB.

Why ORB now:

- compact 256-bit binary descriptors make an encrypted index for hundreds of cards practical;
- Hamming matching is fast on mid-range Android devices;
- the required detector/descriptor is in the main OpenCV Android distribution;
- perspective normalization removes much of the scale/projective burden before matching;
- the interaction is still-image, so accuracy can use up to 1,500 local features without realtime constraints.

SIFT may be more robust on some low-texture/scale cases, but its 128-float descriptors are materially larger/slower. It is not a silent fallback. M3 runs an offline ORB-vs-SIFT comparison on the same locked dataset only if ORB misses the acceptance target. Switching or adding SIFT requires an ADR, new fingerprint/profile version, storage/performance measurements, and migration/fallback behavior.

## 6. Fingerprint format

A fingerprint contains no pixels but remains sensitive derived visual data.

```text
PostcardFingerprintV2 {
  format_version = 2
  recognition_profile_id
  canonical_width
  canonical_height
  coarse_hash64
  keypoints[] {
    x_normalized
    y_normalized
    scale_normalized
    angle_quantized
    response_quantized
    octave
  }
  orb_descriptors[]  // 32 bytes each, aligned with keypoints
  extraction_quality {
    blur_score
    exposure_score
    glare_fraction
    detected_area_ratio
  }
}
```

The fingerprint format/profile is independent of the manifest format version.
The production manifest carries exactly one required FRONT record, so the
fingerprint has no production side discriminator. The parser rejects
unsupported versions and any fields outside this contract.

`coarse_hash64` is a DCT perceptual hash used only for cheap front diagnostics/tie context, never as an acceptance decision or secret. All numeric fields use a fixed binary encoding defined by the normative recognition protobuf/schema. Counts and lengths are bounded before allocation.

Initial ORB extraction parameters:

| Parameter | Seed value |
| --- | ---: |
| `nfeatures` | 1500 |
| `scaleFactor` | 1.2 |
| `nlevels` | 8 |
| `edgeThreshold` | 31 |
| `firstLevel` | 0 |
| `WTA_K` | 2 |
| `scoreType` | HARRIS_SCORE |
| `patchSize` | 31 |
| `fastThreshold` | 20 |

Sender fingerprints live inside the capsule’s encrypted recognition manifest. Recipient fingerprints are encrypted locally with a Keystore-protected fingerprint-storage key. Neither raw fingerprints nor coarse hashes are sent plaintext to the backend.

## 7. FRONT local-feature match

For one query FRONT and one reference FRONT:

1. Reject incompatible fingerprint/profile versions unless an explicit compatible matcher exists.
2. Match ORB descriptors with brute-force `NORM_HAMMING`, KNN `k=2`.
3. Apply the ratio test `best_distance / second_distance <= 0.75`.
4. Apply reverse matching and retain mutual-consistent matches.
5. Estimate query-to-reference homography with RANSAC from normalized keypoint coordinates.
6. Classify RANSAC inliers at a reprojection threshold equivalent to 5 px on a 1600-px long edge.
7. Compute descriptor matches, inlier count, inlier ratio, median inlier reprojection error, spatial coverage on query and reference, and homography plausibility.

### Spatial coverage

Coverage is the smaller of:

- normalized convex-hull area of inlier query points;
- normalized convex-hull area of inlier reference points.

A secondary 4×4 occupancy grid must contain inliers in at least three cells. This prevents a stamp corner or a short word from dominating an entire-card claim.

### Homography plausibility

The transformed reference corners must:

- be finite and form a convex non-self-intersecting quadrilateral;
- preserve orientation (no reflection);
- have mapped area ratio in `[0.20, 5.0]` before canonical normalization correction;
- have no single edge-length ratio above 4× its opposite counterpart;
- have median inlier reprojection error within the configured limit.

Failure is a hard geometry rejection, not merely a small score penalty.

## 8. Match score

For a geometrically plausible match:

```text
count_score      = min(inliers / 40, 1)
ratio_score      = clamp((inlier_ratio - 0.20) / 0.60, 0, 1)
coverage_score   = clamp(coverage / 0.45, 0, 1)
reprojection     = 1 - clamp(median_error_px / 8, 0, 1)

front_score =
    0.35 * count_score
  + 0.25 * ratio_score
  + 0.25 * coverage_score
  + 0.15 * reprojection
```

Weak evidence gate (candidate may remain):

- at least 10 ratio/mutual matches;
- at least 6 RANSAC inliers;
- inlier ratio at least 0.25;
- spatial coverage at least 0.10;
- at least three occupied grid cells;
- plausible homography.

Strong evidence gate:

- at least 20 ratio/mutual matches;
- at least 15 RANSAC inliers;
- inlier ratio at least 0.35;
- spatial coverage at least 0.20;
- median reprojection error at most 4 px;
- plausible homography.

All constants above are fields of `RecognitionProfile`, not scattered code literals. A match report retains each raw signal and gate outcome; UI receives only classification and guidance.

## 9. Hierarchical candidate ranking

### Stage 1: FRONT design candidates

Compute the FRONT `front_score` against the active owner-scoped candidate
universe. Retain the bounded plausible set, ordered by score, and resolve each
match to its local design relation and capsule IDs. If none passes, return
`NO_MATCH_FRONT` and request recapture. A strong or single FRONT still requires
the E2EE gate; it is not a reason to infer a unique capsule when several local
candidates remain.

### Automatic acceptance

The candidate may proceed only if all applicable rules pass:

1. the manifest format/version is supported;
2. the required FRONT passes the applicable weak/strong gates;
3. exactly one capsule candidate remains, or the user explicitly selects one
   in the future ambiguity picker;
4. full E2EE verification succeeds for the selected capsule.

There is no “best candidate wins” fallback and no automatic opening for an
ambiguous design-to-many result.

### Ambiguity and retry

- If multiple capsule candidates remain for one design, return an explicit
  ambiguity result. The future scan-scoped chooser presents only the bounded
  plausible set sorted by score; until that UI exists, do not open any result.
- If exactly one plausible candidate is below automatic thresholds, request one
  guided recapture first. After another quality-passing scan, the user may
  explicitly confirm that single candidate rather than loop forever.
- If no plausible candidate exists, show recapture guidance; do not show arbitrary known capsules.
- Chooser rows reveal only locally decrypted sender handle snapshot, year/date, and optional place label. No thumbnails, notes, photo counts, or browsing after leaving the scan flow.

Manual selection is not treated as stronger vision evidence. It still must pass envelope, signature, IDs, hashes, and AEAD verification before a scan grant is issued.

## 10. First receipt fingerprint

After a first-receipt candidate is accepted and crypto verification succeeds:

1. Build a FRONT fingerprint from the current delivered normalized capture.
2. For a single-candidate result, store the FRONT `RECIPIENT` baseline after
   crypto verification. A selected ambiguous candidate is stored only after
   the user explicitly confirms the shown capsule corresponds to the physical
   postcard.
3. Mark the recipient FRONT baseline preferred and retain the sender baseline
   as fallback.
4. Never upload the recipient fingerprint plaintext.

The initial delivered baseline is immutable. Later scans do not silently overwrite it, avoiding gradual drift or false-match poisoning. A future explicit improvement flow may add another version after high-confidence comparison; it must retain prior versions and bound their count.

## 11. Later-scan strategy

1. Search the preferred recipient FRONT baseline first.
2. If exactly one candidate passes the FRONT rules, stop and verify crypto.
3. If multiple candidates are plausible, never auto-open; use the future
   explicit chooser.
4. Only when no recipient candidate passes weak evidence, repeat the hierarchy
   with retained sender fingerprints.
5. On successful sender fallback, do not silently replace the recipient
   baseline; record a redacted diagnostic suggesting an explicit improvement
   scan in future UX.

This compares an aged card primarily with its post-delivery identity, so original postal changes are already part of the baseline.

## 12. Expected failures and UX response

| Condition | Detection | Response |
| --- | --- | --- |
| Low-texture FRONT | too few features/matches | Improve light/angle and recapture the FRONT. |
| Glare/blur/shadow | capture quality gates | Immediate targeted recapture instruction. |
| Border not visible | quad confidence/manual corners | Move farther away or adjust four corners. |
| Severe crop/occlusion/damage | weak coverage/inliers | Retry; chooser if a small plausible set remains. |
| One design with multiple capsules | more than one owner-scoped candidate | Never auto-open; use the explicit chooser when available. |
| Added stamps/marks | unmatched new features | Ignore as outliers; RANSAC preserved local features. |
| Wrong physical postcard | no plausible routed candidate | No match; no global lookup. |
| Profile version unsupported | format gate | Rebuild compatible fingerprint if local raw input exists; otherwise explicit unsupported state. |
| Candidate selected but crypto corrupt | signature/AEAD failure | Show nothing; re-sync/diagnostic path. |

## 13. Reproducible evaluation dataset

M3 creates a consented, non-production dataset stored outside normal user object
storage. Captured BACK images, if present, are ancillary research material
only; they do not enter production recognition or acceptance metrics.

Minimum initial composition:

- 30 physical postcard instances mapped across at least 10 designs;
- design-to-capsule groups covering zero, one, and multiple owner-scoped
  capsules, including repeated capsules for one printed design;
- sender-before-mail FRONT references;
- recipient-after-delivery or controlled postal-modification FRONT captures;
- any captured BACK images stored separately as ancillary research data and
  excluded from the production benchmark;
- multiple query captures covering rotation, perspective, low light, shadow, glare, crop, partial occlusion, added stamps/labels, dirt, and wear;
- unknown negative postcards and cross-instance negative pair comparisons.

Dataset split is by physical instance and design-to-capsule group, never random
frames of the same card across train and evaluation. Threshold tuning uses the
development split only; the evaluation split remains locked until a profile
candidate is frozen. Identical FRONT designs must remain represented as
multiple plausible capsule candidates rather than being collapsed into a
single top-1 truth.

Report:

- capture-quality rejection rate by condition/device;
- design candidate recall and zero-match rejection;
- single-candidate verify/open recall;
- multi-candidate candidate-set/chooser recall (once the picker exists);
- false automatic acceptance count/rate across all negative comparisons;
- ambiguity/retry rate;
- p50/p95 extraction and match latency versus candidate count;
- fingerprint encrypted size.

Initial M3 targets on quality-passing captures:

- zero false automatic accepts in the locked evaluation set, with sample size and statistical upper bound reported rather than claiming impossible zero risk;
- first-receipt FRONT design candidate recall at least 85%; correct capsule
  present in the single-candidate or chooser result at least 95%;
- later-scan FRONT design candidate recall at least 95%; correct capsule
  present in the result at least 98%;
- p95 end-to-end matching after capture under 2 seconds for 100 candidates on the documented reference device;
- median encrypted fingerprint baseline under 256 KiB and hard maximum under 1 MiB.

Failure to hit false-accept behavior blocks automatic opening. Failure only in auto recall may ship with more chooser/retry use if the physical two-user acceptance scenario remains usable.

## 14. Tests and instrumentation

### Unit/component

- corner ordering, perspective warp, normalized coordinates, profile parsing;
- fingerprint serialization bounds and malformed payload rejection;
- ratio/mutual matching, RANSAC inlier classification, coverage, homography gates;
- exact score/gate calculations from fixed synthetic reports;
- ranking, design-to-many grouping, margins, retry/chooser/auto classification;
- explicit manifest-format/version parsing and unsupported-version rejection;
- recipient-first fallback ordering and FRONT-baseline persistence rules;
- front-only 0/1/N classification, crypto verification, and clean-reset
  persistence/restart cases.

### Golden/image tests

- fixture images transformed deterministically for rotation, perspective, exposure, blur, crop, occlusion, and added marks;
- same-instance positive and different-instance negative matrices;
- identical-FRONT groups with multiple local capsule candidates;
- OpenCV instrumentation tests on at least one emulator ABI and real ARM64 device.

### Privacy/diagnostics

Recognition logs may include profile ID, timing, feature counts, inlier counts, coverage, score, result class, and opaque candidate count. They must not include bitmap bytes, descriptors, keypoint coordinates, chooser text, handles, capsule IDs in analytics, or address/note OCR. OCR is not part of the pipeline.

## 15. Primary references

- [OpenCV ORB API](https://docs.opencv.org/4.x/db/d95/classcv_1_1ORB.html)
- [OpenCV feature matching and homography tutorial](https://docs.opencv.org/4.x/d1/de0/tutorial_py_feature_homography.html)
- [OpenCV SIFT API](https://docs.opencv.org/4.x/d7/d60/classcv_1_1SIFT.html)
