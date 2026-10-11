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

1. Finish full catalog, known-song queries and backend wiring/rollback.
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
