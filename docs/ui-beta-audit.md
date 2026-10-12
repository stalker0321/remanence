# UI / scenario audit for the Android friends beta

2026-10-12. Source + existing native visual fixtures + regression evidence;
not an invented fresh physical walkthrough of every screen.

The goal is a clear action and a recoverable failure at each step, not more
decorations. Preserve Hold's paper/ink, typography, physical primary action,
quiet navigation, and the same vocabulary in English/Russian/Ukrainian.

| Surface / scenario | Current decision | Beta follow-up / acceptance |
| --- | --- | --- |
| Home | Two distinct intents: make/open. Signed-out entries lead to authentication. | First-time friend must explain the physical-postcard mechanism without help. Add one short explanation if testing shows confusion; no tutorial carousel by default. |
| Google entry | Branded button; real working/error/cancel states; language available. | Test actual Play signer, no stale disabled button after cancel. |
| Permanent handle | Separate validated form, pinned action, uniqueness/retry. | Clearly explain permanence before committing. Existing policy does not permit rename. |
| Passkey setup | Required verified enrollment; manager separate from login provider. | Test unsupported PRF/cancel/relaunch. Explain “your account is saved; access setup is unfinished”, never blame the user or pretend login alone restores keys. |
| Recipient | Manual handle plus raised `for myself / для себя / для себе`; same confirmation path. | Confirm own shortcut on actual account; no need to type one's own handle. |
| Camera / crop | Existing permission denial/permanent-denial/retry routes. | Device camera tests after OpenCV upgrade; laptop image is a valid technical scan input, not a new security factor. |
| Music | Existing search UI but old/disabled catalog cannot demonstrate usefulness. | Full search is a beta usability gate; known songs, no-results, network error, retry and selected-track snapshot. |
| First send | Versioned server terms admission; padded scrollable page with Back, legal rows and pinned Continue. | Large-font/small-viewport layout tested. Missing network stays retryable and does not upload. |
| Publish / interrupted upload | Existing resumable owner-bound state machine. | Confirm progress vs successful send are visually distinct; retry must not create duplicates. |
| Capsule loading/failure | Previously raw English literals; now localized opening/failure/retry/close. | A terminal expired grant must not invite an impossible retry; expiry copy should say to scan again. |
| Open capsule | Art stays primary. New quiet share action opposite Close. | Export current content only, not app chrome/buttons/keys; exclude broken/loading states. Check narrow layouts. |
| Share / save | System chooser or document picker; one clear plaintext-export warning. | Cancel returns cleanly; no false success if export fails; visually compare PNG to rendered art. |
| Settings | Grouped actionable rows with current values; language picker; access/privacy detail collapsed; logout separated. | Contact safety collapsed by default, not an always-visible administrative form. Confirm every link live before inviting. |
| Report / block | Real owner-scoped server action; report category separate from block. | Prefer contextual sender menu later; current minimal handle entry is usable but longer. Never label blocking as deleting existing copies. |
| Logout | Existing revoke/owner fence, not an account deletion. | Confirm another account never sees old plaintext/selection. |
| Account deletion | Explicit request via email; reachable without app from web page. | Confirm user understands external copies remain; operator ownership verification and processing are mandatory. |

## Priorities

P0 before friends: real Play-signing auth/recovery; native camera/recognition;
full music; working policies/deletion processing; reporting operations; content-only
PNG and save/chooser/cancel evidence; no indefinite loader or fake success.

P1 small usability improvements: contextual contact actions on received capsules;
one-sentence scan guidance; genuinely distinguish empty search from unavailable
backend; expiry should offer a clear rescan/close outcome rather than futile retry.

P2 after feedback: visual density refinements and optional tips. Do not add new
navigation, galleries, onboarding carousels or a PWA in this beta preparation.

Native image fixtures test layout, not a physical Google account or provider
backup. The owner's already successful second-phone opening of old ciphertext
is valid recovery evidence and need not be repeated just because styling changed;
Owner reports Play install/login/recovery working on 2026-10-12, but explicitly
did not check opening an old capsule on that Play build. Do not infer that check
passed from earlier debug/provider evidence.

## Owner-feedback polish — T175, versionCode36

Runtime ce306e4d731ef752038cb4adc8c25cf3d105466b; native fixture refinements
8f7419f4a5dddd0925d12b5ab2a4dd8715e65689 and
a19c7031912aa4b1f40df5ff9fed0383d907e463. No backend/auth/crypto/catalog change.
Exact asserted music canvas PNGs retained by test-only 6e67fc55.
Final static cover check is taken before the normal press indication at be252950.

- Music: verified artwork is unobscured; only no-artwork bands retain the play
  cue. The whole music region still opens the service picker. Sealed track
  copy, authored photo/note geometry and renderer fail-closed checks unchanged.
- Viewer: a quiet full-height mat, compact external controls and bounded whole
  sheet with a small shadow. System inset background matches the mat.
- PNG: capture the sheet/card only; app furniture and viewport bands excluded.
  Legacy BER includes its visible track in the same content layer and refuses
  export unless its renderer is Ready. Explicit plaintext warning, owner/grant
  validation, bounded pixels/cache and read-only provider preserved.
- Receive: no raw JPEG processing preview, so no rotated-photo flash. Shared
  neutral processing frame and matching capture/header vocabulary with Create;
  single-capture, release, retry and byte-zeroization contracts preserved.
- Consent: roomy legal/checkbox rows, scrollable content and pinned action;
  consent remains confirmed by the backend, never inferred from a local checkbox.
- Settings follows grouped rows/current values/secondary detail guidance from
  [Material settings patterns](https://m1.material.io/patterns/settings.html),
  using Hold typography/palette rather than replacing the established style.

58 focused JVM checks and native device fixtures/export checks recorded in
`/home/vodkolyan/remanence-agent-control/tasks/T175/`. Native evidence is synthetic
UI/GPU evidence, not a new physical camera recognition or Play/provider acceptance
claim. Owner should update in place and compare music cover/no-cover, scan,
settings and shared PNG on the phone; old capsule check remains outstanding.
