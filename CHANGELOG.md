# Changelog

## 0.2.0-sift-it.5 — Android sync persistence hotfix

- Publishes verified incoming postcard index and ciphertext files with the
  Android-safe same-directory no-replace move path, preserving no-follow path
  safety, integrity/hash/size verification, durability ordering, ambiguous
  outcome reconciliation, exact owned-source cleanup, and unknown conflicts.
- Raw deterministic destination spelling remains the persisted Room `localPath`
  contract even when a trusted-root capability uses its canonical spelling.
  Provider move or mandatory durability unavailability is terminal and cannot
  loop as verified-payload persistence retry; diagnostics remain bounded and
  privacy-safe.
- Android `versionCode` is 16. This debug integrated-test build targets
  `https://remanence.hryshyn.dev/` and explicitly enables V2 line localization
  through the current `V2LinePostcardLocator`; the release/default feature
  remains disabled. SIFT/RootSIFT remains the sole current matcher profile;
  no legacy ORB path or threshold changes are included.
- Wire identity remains REST `/v1` and outer protobuf
  `remanence.protocol.v1`; recognition uses schema `remanence.recognition.v2`,
  fingerprint format 3, and profile `postcard-sift-rootsift-v1`.
- Tag: `v0.2.0-sift-it.5`. Artifact naming is
  `Remanence-android-v0.2.0-sift-it.5-code16-g<git-sha>-debug.apk`.
- The hosted server was not redeployed for this client hotfix, and Postmark
  remains stopped. This is an installable integrated-test prerelease: physical
  device, dataset, recovery, and later public-release gates remain separate
  evidence requirements. Rollback remains an explicit artifact/tag mapping
  and never reuses an Android `versionCode`.

## 0.2.0-sift-it.4 — Android trusted-root alias fix

- Fixes the Android local-staging false positive for a system-shaped alias at
  or above the app-private `filesDir` root. The trusted canonical root identity
  is snapshotted once, and all derived destinations remain fail-closed against
  traversal, sibling-prefix confusion, intermediate symlinks, leaf symlinks,
  and boundary-alias swaps.
- Android `versionCode` is 15. This debug integrated-test build targets
  `https://remanence.hryshyn.dev/` and explicitly enables V2 line localization.
- Wire identity remains REST `/v1` and outer protobuf
  `remanence.protocol.v1`; recognition uses schema `remanence.recognition.v2`,
  fingerprint format 3, and profile `postcard-sift-rootsift-v1`.
- Tag: `v0.2.0-sift-it.4`. Artifact naming is
  `Remanence-android-v0.2.0-sift-it.4-code15-g<git-sha>-debug.apk`.
- The hosted server was not redeployed for this client hotfix, and Postmark
  remains stopped. This is an installable integrated-test prerelease: physical
  device, dataset, recovery, and later public-release gates remain separate
  evidence requirements. Rollback remains an explicit artifact/tag mapping and
  never reuses an Android `versionCode`.

## 0.2.0-sift-it.2 — sync compatibility integrated-test candidate

- Adds the bounded incoming-sync compatibility hotfix: a deployed server that
  lacks the tombstone capability may return only the canonical `ROUTE_NOT_FOUND`
  or `METHOD_NOT_ALLOWED` problem response; that capability is then treated as
  empty while incoming capsule-index sync continues. Other auth, transport, and
  malformed-response failures remain fail-closed.
- An unavailable or empty candidate index is presented as sync/index unavailable,
  not as a visual no-match; evaluated weak matches retain the existing recapture
  behavior. Debug diagnostics expose only safe counts and matcher metrics.
- Android `versionCode` is 13. This debug integrated-test build targets
  `https://remanence.hryshyn.dev/` and explicitly enables V2 line localization.
- Wire identity remains REST `/v1` and outer protobuf
  `remanence.protocol.v1`; recognition uses schema `remanence.recognition.v2`,
  fingerprint format 3, and profile `postcard-sift-rootsift-v1`.
- Tag: `v0.2.0-sift-it.2`. Artifact naming is
  `Remanence-android-v0.2.0-sift-it.2-code13-g<git-sha>-debug.apk`.
- The hosted server was not redeployed for this client hotfix; rollback remains
  an explicit artifact/tag mapping and never reuses an Android `versionCode`.

## 0.2.0-sift-it.1 — integrated-test candidate

- P3A SIFT/RootSIFT is the sole current FRONT recognition profile.
- Android `versionCode` is 12.
- Wire identity remains REST `/v1` and outer protobuf
  `remanence.protocol.v1`; recognition uses schema `remanence.recognition.v2`,
  fingerprint format 3, and profile `postcard-sift-rootsift-v1`.
- This is an installable integrated-test prerelease. Physical-device, dataset,
  and later public-release gates remain separate evidence requirements.
- Planned tag: `v0.2.0-sift-it.1`, assigned only to the exact clean release
  commit. Artifact naming is
  `Remanence-android-v0.2.0-sift-it.1-code12-g<git-sha>-debug.apk`.
- Rollback baseline: `v0.1.0-m3-registration-maintenance-preview.1` at
  `e6bade61239132fd3cf44d5e78c3dfd4e5f86f46`, Android code 11. Because the
  SIFT cutover has no ORB compatibility path, rollback requires the documented
  clean recognition-state boundary; an Android rollback update must use a new
  higher code.
