# UI / scenario audit for the Android friends beta

2026-10-11. Source + existing native visual fixtures + regression evidence;
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
| First send | New versioned terms admission before Create. | Pinned action; readable/wrapping content, labeled checkbox, server confirmation. Missing network stays retryable and does not upload. |
| Publish / interrupted upload | Existing resumable owner-bound state machine. | Confirm progress vs successful send are visually distinct; retry must not create duplicates. |
| Capsule loading/failure | Previously raw English literals; now localized opening/failure/retry/close. | A terminal expired grant must not invite an impossible retry; expiry copy should say to scan again. |
| Open capsule | Art stays primary. New quiet share action opposite Close. | Export current content only, not app chrome/buttons/keys; exclude broken/loading states. Check narrow layouts. |
| Share / save | System chooser or document picker; one clear plaintext-export warning. | Cancel returns cleanly; no false success if export fails; visually compare PNG to rendered art. |
| Settings | Flat account/language/access/privacy/about/logout; new legal/deletion links. | Contact safety collapsed by default, not an always-visible administrative form. Confirm every link live before inviting. |
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
the Play-signed build still needs its own signing/integration smoke check.
