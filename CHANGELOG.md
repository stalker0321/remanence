# Changelog

## 0.2.0-rc.2 — Generator C3 integration

Android `versionCode` is **23** (`22` was used for an unpublished owner test
APK and will not be reused). The debug candidate targets
`https://remanence.hryshyn.dev/` with V2 line localization enabled. The
intended prerelease tag is `v0.2.0-rc.2`; artifact naming is
`Remanence-android-v0.2.0-rc.2-code23-g<release-sha>-debug.apk`.

- Generator C1–C3: typed generator expression/discovery, owner-scoped
  original-byte staging, source binding, serialized create bridge, and the
  authoritative capsule publisher cutover. The legacy publisher fallback is
  removed; missing bridge, stale session, or source/binding failure fails
  closed instead of publishing through the old path.
- No recovery/Block Store probe, protocol, renderer, or backend change in this
  candidate. RV-01 remains a separate throwaway app and physical evidence is
  still pending.
- Generator final local unit gate on the merged code tree: **975 tests**, 0
  failures/errors, 2 documented environment skips. APK assembly and signing
  are verified separately for this version; phone install-over, capture,
  create/publish, and other physical checks remain OPEN.

## 0.2.0-rc.1 — Astra motion, self-open fix, in-app language switch

Android `versionCode` is **21**. Debug candidate targets
`https://remanence.hryshyn.dev/` with V2 line localization enabled. Tag
`v0.2.0-rc.1`, the GitHub prerelease, and any `origin/main` fast-forward
remain owner-gated; artifact naming is
`Remanence-android-v0.2.0-rc.1-code21-g<git-sha>-debug.apk`.

- Astra scan motion and drawn postcard: entry/waiting/chooser arrival
  transitions with the postcard carrying the wait; copy, actions, test tags,
  matcher geometry, and crypto unchanged.
- Self-addressed capsules open (dual-plane presentation plus grant routing).
- Discoverable in-app language switch (English / Русский / Українська plus
  System reset) on Home and Auth; single restart per selection
  (AppCompat-driven below 33, framework-driven on 33+); `localeConfig` and
  AppCompat theme parents on all tiers. No new destination; auth, grants,
  and flow epochs preserved across the switch.
- Unit evidence only (targeted locale/home/parity plus scan/self-send suites
  green on the integration branch). Phone-only checks remain OPEN, neither
  passed nor VPS-gate failures: camera bind/unbind across the switch,
  authenticated grant open after the switch, 33+ framework restart on
  hardware, Astra motion and language-row visual sign-off, release APK
  install/launch/smoke on device.

## 0.2.0-sift-it.9 — Hold capture and scan product copy

Android `versionCode` is **20**. Debug integrated-test build targets
`https://remanence.hryshyn.dev/` with V2 line localization enabled. The APK
build is owner-authorized; tag `v0.2.0-sift-it.9`, the GitHub release, and any
`origin/main` fast-forward remain owner-gated. Feature commit `24152ad` is
**local only** (feature push blocked by missing GitHub auth); artifact naming
is `Remanence-android-v0.2.0-sift-it.9-code20-g<git-sha>-debug.apk`.

- Remainder restored to the measured handoff transform: scale 0.72, translate
  (21.96, 19.8). Do not bbox-centre; the 0.80 `.8` glyph is superseded.
- Action-object press: 6.dp edge, 4.dp travel, 110 ms, pressed remainder 2.dp.
- Home gutters 24 / 20 / 18 dp; compact display + scroll at large type or
  short viewport.
- Auth forms pin submit above IME (`HoldFormScaffold`); failed login keeps
  typed values. Password stays masked.
- Capture copy (item 5): quiet portrait instruction and shutter, honest
  permission / binding / capture / processing / retry wording, and rejection
  guidance localized EN/RU/UK in `hold_strings.xml`. The duplicated,
  untranslated surface header is removed. Matcher geometry, CameraX binding,
  SIFT, the capture state machine, and every test tag are unchanged.
- Scan product copy (item 6): Matching/Accepted/RecaptureGuidance/
  IndexUnavailable/MaterialPending and the ambiguity chooser render authored
  EN/RU/UK copy (`hold_scan_*`, `hold_chooser_*`). RecaptureGuidance shows one
  honest message plus one real recapture action; the dead inline camera is
  removed and the action resets to `AwaitingCapture`. Trusted-vs-claimed
  identity is unchanged; every user-controlled chooser placeholder (trusted
  handle with its `@` prefix, claimed name, place label) renders as one
  bidi-isolated (FSI/PDI) unit so surrounding labels keep their order.
  ScanViewModel, `ScanMatchUiState`, grants, matcher and crypto are untouched.
- Items 5–6 ship in this candidate; feature commit `24152ad` is local-only.
  Filtered `:app:testDebugUnitTest --tests` gate **131/131** (`capture.*`,
  `ui.scan.*`, `RootScanFlowLayoutTest`, `CreateSmallViewportTest`), final
  independent review PASS — not the full suite and not device evidence.
  RecaptureGuidance stays reason-agnostic (no reason field on
  `ScanMatchUiState`); RU/UK authored copy awaits physical/native context
  review.
- Full multi-module Android unit gate on the final test-only fixtures:
  **1894 tests, 0 failures, 0 errors, 3 environment skips** (`:app` 829,
  `:core:crypto` 235, `:core:data` 540, `:core:recognition` 225,
  `:core:model` 65). The three fixes are test-only (live session-lease
  bootstrap and two stale outbox/storage assertions); **production code and
  the signed APK are unchanged**.
- See `docs/hold/STATUS.md` and `docs/hold/CODEX-HANDOFF.md`.

## 0.2.0-sift-it.8 — Home layout, press, launcher icon

- Centers the Remainder glyph in the adaptive launcher icon.
- Home: “got a postcard?” fills leftover height; “someone in mind?” stays the smaller secondary action. Removes the inset that made the two cards look randomly sized.
- Press on Hold action cards and buttons snaps immediately (visible even if animator duration is 0).
- Scan no-match copy no longer pretends only framing failed; withdrawn postcards will not open.
- Generator is still not in the app. Music and M4 recovery are still out of this APK.
- Android `versionCode` is 19. Debug integrated-test build targets
  `https://remanence.hryshyn.dev/` with V2 line localization enabled.
- Tag: `v0.2.0-sift-it.8`. Artifact naming is
  `Remanence-android-v0.2.0-sift-it.8-code19-g<git-sha>-debug.apk`.

## 0.2.0-sift-it.7 — cancel, upload preflight, identity, Hold

- Ships sender cancellation vs first-open admission, SA-03 upload preflight,
  IP-01 chooser identity, IP-02 control-gate identity binding, and Hold UI.
- Server first-open (`POST /v1/capsules/{id}/first-open`) and pre-body blob
  authorization are already live as `remanence-api:cancel-46e6bac`
  (`sha256:e518ca4fa8677a0ef5bed8e5b58dbe6c63b802e39a2609d4a686916b3b39feb5`);
  database head is `0007_m2_f3_first_open_claim`.
- Android `versionCode` is 18. This debug integrated-test build targets
  `https://remanence.hryshyn.dev/` and explicitly enables V2 line localization;
  the release/default feature remains disabled. Physical two-device validation
  is still pending (morning device loop).
- Tag: `v0.2.0-sift-it.7`. Artifact naming is
  `Remanence-android-v0.2.0-sift-it.7-code18-g<git-sha>-debug.apk`.
- Music is not included. Recovery (M4) remains a separate worktree.
- This is an installable integrated-test prerelease. Rollback remains an
  explicit artifact/tag mapping and never reuses an Android `versionCode`.

## 0.2.0-sift-it.6 — Android scan sync lifecycle fix

- Fixes the scan-triggered owner-scoped incoming sync/readiness path with typed,
  bounded scheduling outcomes and privacy-safe progress diagnostics, while
  preserving KEEP semantics and fail-closed worker/session restore behavior.
- Fences Root flow admission, refresh coalescing, scheduling, diagnostics, and
  logout/account-session boundaries so stale owner work cannot publish, reopen
  Create/Scan, or schedule after logout, owner switch, or a fresh session.
- No matcher algorithm or threshold change is included. Crypto, matcher grant,
  path, size, hash, and integrity boundaries remain strict; there is no server,
  wire protocol, schema, or database change.
- Android `versionCode` is 17. This debug integrated-test build targets
  `https://remanence.hryshyn.dev/` and explicitly enables V2 line localization;
  the release/default feature remains disabled. Focused lifecycle coverage and
  the full unit gate are green; physical two-device validation is still pending.
- Tag: `v0.2.0-sift-it.6`. Artifact naming is
  `Remanence-android-v0.2.0-sift-it.6-code17-g<git-sha>-debug.apk`.
- The hosted server was not redeployed for this client hotfix, and Postmark
  remains stopped. This is an installable integrated-test prerelease; physical
  device, dataset, recovery, and later public-release gates remain separate
  evidence requirements. Rollback remains an explicit artifact/tag mapping and
  never reuses an Android `versionCode`.

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
