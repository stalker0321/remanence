# Closed Play beta — release checklist

Owner scope: Android Remanence with friends, not a PWA/iOS claim and not an
unrestricted public rollout. Support contact: hryshyn.dev@gmail.com.

## Implemented preparation

- Explicit `beta` build type: package `dev.hryshyn.remanence`, non-debuggable,
  HTTPS-only, Google onboarding + PRF recovery enabled. Minimum Android 9 / API28,
  target API36. Legacy `release` defaults remain OFF, debug keeps `.recoverytest`.
- Upload signing reads `REMANENCE_UPLOAD_KEYSTORE`, `REMANENCE_UPLOAD_STORE_PASSWORD`,
  `REMANENCE_UPLOAD_KEY_ALIAS`, `REMANENCE_UPLOAD_KEY_PASSWORD`. No key/password
  is committed. Without signing inputs the beta artifact is **unsigned and not
  upload-ready**. Do not reuse a debug signing key as the Play upload key.
- Backend has explicit `REMANENCE_MODE=beta`, retaining PROD's recovery refusal.
  Use absolute blob roots and explicit OAuth/origin allowlists; never remove
  all security gates to make an upload succeed.
- Additive migration0011: owner contact blocks, metadata-only reports and
  versioned terms consent. Blocking is enforced on draft admission and incoming
  delivery discovery; it does not erase received/offline/exported copies.
- Beta Create requires actual server consent, not a checkbox stored locally.
- Settings links to privacy/terms/out-of-app deletion and offers contact safety.
  In-app deletion is an explicit **request**, via the support email, not an
  instant destructive operation or a claim that logout deletes anything.
- Public service pages: `/legal/privacy`, `/legal/terms`, `/legal/delete-account`.
  With the existing proxy prefix, URLs are
  `https://remanence.hryshyn.dev/onboarding-test/legal/<page>` **after deployment**.
- Explicit current-view PNG export with Android Sharesheet or document picker.
  Narrow FileProvider, no blanket storage permission, no key/recovery export.
- OpenCV upgraded4.10→4.12 for the 16KB native gap. Verify final binaries and
  actual recognition behavior; changing the version string is not proof.

## Still required before inviting friends

Deployment checkpoint2026-10-11: T174 activated the existing beta implementation
on the onboarding API (dfa5a6d5 source,image870e3c1e), schema0011 and mode=beta.
Privacy/terms/deletion request URLs now return200; existing15 tables preserved.
128 isolated server tests and live read-only smoke pass. T172 signed vc35 is
already uploaded as an Internal draft; T173's three Play signing keys/OAuth
clients are configured. Signing/build/config steps below are completed for this
artifact, not a request to rebuild it. Play-signed owner-only device smoke is
next; policy review, manual deletion/moderation execution and reviewer access
are not established by deployment.

1. Full catalog is wired into the existing hosted API (T171): 40,475,049
   recordings in the compact read-only runtime catalog. Verify known-song
   searches from the owner's phone; this does not require another APK.
2. Review/approve the published privacy/terms text and operational retention.
   An email link alone does not fulfill a deletion request: operator must verify
   ownership and actually remove the relevant account/data. Define backup expiry,
   preserve already received/exported copies honestly and never ask for keys.
   Current schema has RESTRICT FKs from capsules to users/key bundles; do not
   blindly `DELETE users`, TRUNCATE, or reset the entire test service.
3. Provide a real upload key with secure backup; create the actual Remanence app
   in Play Console and enroll Play App Signing. App-signing and upload keys are
   different roles. Get Play **app-signing** SHA1/SHA256 and configure the
   Android OAuth client, DAL exact package/certificate and native recovery origin.
   Preserve debug/probe associations and existing account/recovery data.
4. Build identifiable signed AAB under `/tmp/remanence-gradle.lock`:
   `scripts/verify-hold.sh :app:bundleBeta -Premanence.apiBaseUrl=https://remanence.hryshyn.dev/onboarding-test/`
   plus exact product version and monotonically increasing versionCode.
   Verify merged manifest, signing, HTTPS URL, flags, packaged ABIs, ELF16KB,
   bundletool APK ZIP alignment/download size and native runtime/recognition.
   Future R8 optimization requirements are a separate scheduled release gate.
5. Supply policy-consistent Data safety (encrypted data is not automatically
   exempt), privacy URL, deletion URL, ads declaration, content rating, target
   audience, permission descriptions, support email and honest store screenshots.
6. Reviewer access: reusable isolated demo account + recovery method and a
   reproducible postcard/QR/image input. Reviewer must not need the owner's
   Google account, phone or personal passkey. No production master key or bypass
   of other users' authentication/E2EE. Finalize and test this before review.
7. Moderation is operational: inspect pending `safety_reports` regularly, respond
   through support, verify complaints, act on abusive accounts and record outcome.
   A table that nobody checks is not an effective moderation process. Reports
   contain only IDs/category; ask for voluntarily supplied evidence if needed.
8. Internal-track installation first: Google sign-in, PRF setup, old-ciphertext
   restore, send to another friend, camera scan/open, share/save/cancel, Settings,
   deletion request, block/unblock/report, offline errors/relaunch. Then Closed.

Neither local tests nor an unsigned AAB establish Google Play approval. No
Console publication, signing-key creation, live mode change, policy deployment
or account deletion is implied by this document.

## Owner Console steps

Create **Remanence**, free app; do not reuse REC-01 or register `.recoverytest` as
the permanent product. Complete store/app-content setup, obtain signing certs,
upload signed AAB, and configure a Closed-testing email list or Google Group.
Invite friends with the opt-in link; joining and installing are distinct steps.
For covered personal developer accounts created after13Nov2023, 12 continuously
opted-in testers for14days are needed for the later production-access application,
not as a minimum group size to start the beta.

## Official references checked 2026-10-11

- https://support.google.com/googleplay/android-developer/answer/9845334
- https://support.google.com/googleplay/android-developer/answer/14151465
- https://support.google.com/googleplay/android-developer/answer/13327111
- https://support.google.com/googleplay/android-developer/answer/10787469
- https://support.google.com/googleplay/android-developer/answer/9876937
- https://support.google.com/googleplay/android-developer/answer/15748846
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/identity/credential-manager/prerequisites

User-facing crypto compatibility and these policy documents remain distinct:
closed testing is not an exemption from mandatory account/UGC safeguards.

## Historical QA checkpoint, 2026-10-11 (before T174 deployment)

v35 debug QA APK and unsigned beta AAB build successfully. APK/AAB ELF64
16K checks and debug APK ZIP alignment pass. On the existing API36 Play AVD,
native CameraX capture plus the production SIFT extractor/matcher, native DNN
probes and current-capsule GPU PNG pixels pass. Focused JVM tests:32 pass;
isolated server suites:44 pass,1 skipped. These are emulator/local evidence,
not physical-camera, provider-sync or Play-signed acceptance.

The current hosted backend remains on schema0010, without the beta safety
migration0011 or legal pages. Debug QA builds hide unhosted policy links; beta
builds still require policy/safety deployment before inviting friends. No
account reset, live schema migration or Play upload was performed.

Legacy BER presentation follow-up: its track card is a sibling of the captured
canvas, so that older format's PNG does not yet include the visible track card.
Current V3 collage and paginated photo fixtures have pixel evidence. Do not
claim every legacy presentation or saving a file to an external provider was
tested from the chooser/picker cancellation checks.

## Current checkpoint, 2026-10-12 — T175

The preceding schema0010/unsigned statements describe the earlier QA run, not
the current deployment. T174 deployed schema0011/legal/safety; owner reports
actual Play install/login/recovery working. Owner did not check old-capsule
opening on that Play build, so that gate remains outstanding.

UI feedback implemented at ce306e4d731ef752038cb4adc8c25cf3d105466b:
consent layout/back/large fonts, grouped settings, unobscured music cover and
no-cover cue, mat/sheet viewer, content-only PNG and no rotated scan-JPEG flash.
Legacy BER track moved into the captured content layer with renderer-Ready
guard; this fixes the known sibling omission but has no new native BER fixture.
58 focused JVM tests pass; 11 distinct native synthetic UI/GPU tests pass
(10 pass in native-r5 plus corrected static-cover check in native-r6, test pin
be252950; early stale-runner logs are not refinement evidence). Export tests
prove PNG ratio/pixels and chooser/document-picker launch/cancel, not an
external recipient app import or actual provider document write.

Frozen signed AAB:
`/home/vodkolyan/remanence-agent-control/tasks/T175/artifacts/remanence-v36-internal.aab`
145586752 bytes; SHA256
`c2590c1c98db96e7d3209844b1c8a413987036a96072ed6c036669314895b131`.
Permanent package dev.hryshyn.remanence, vc36, min28/target36, non-debuggable,
same upload signer/API URL/Google flags. JAR signature, all609 payload manifest
digests and ZIP integrity pass. All28 native library hashes and BundleConfig
are byte-identical to the v35 16KB-audited bundle; QA APK16KB ZIP alignment passes.
Self-signed/no-timestamp and AGP manifest-order JarInputStream warnings are
retained in evidence; JarFile signature and every payload digest verify.

No Play publication or backend/account changes in T175. Owner uploads vc36 to
the existing Internal app and updates in place; no uninstall, account reset or
new OAuth clients. Phone visual/camera/export smoke remains, as do the manual
moderation/deletion/reviewer-access obligations before a friends Closed beta.
