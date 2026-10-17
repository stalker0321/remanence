# Web backlog

Sub-item status for T213. This file does not replace `tasks/T213/status`.
Statuses: TODO, DOING, IMPLEMENTED, VERIFIED, BLOCKED.
A mocked call is not a live integration. Production send stays off.

## A

- A01 VERIFIED. Commit `ffcf64edd5879e8b9c2804599f6f17c22d553868`. Firefox 155.0.1 opened the production build on the VM. Bundle `index-DyRX6lAI.js` / `index-CovRG_mx.css`. Boot errors empty. The product page did not request the external crypto stub. HPKE open and the harness journey reached presentation-admitted. Screenshots for this run stayed on the VM at `/tmp/t213-c4-music-smoke`. Next: none.
- A02 VERIFIED. Commit `e2c65392e32e7f50c0a12aae5075ab683c16c55d`. Firefox 155.0.1 opened locked `#/create`, `#/scan`, and `#/settings` without signing in or admitting a postcard. An unknown hash returned home. `#/create?access=secret` did not render or store the query. Boundary: a late in-flight lookup after leaving the page is aborted in code and covered by the recipient and music unit tests, not by a Firefox click.
- A03 IMPLEMENTED. `SessionStore` phases and generation fencing on lookup, music, receive, and enrollment. Lock and sign-out drop the create drafts in unit tests. Next: one browser check of that teardown.
- A04 IMPLEMENTED. Profile header, bounded reads, dotted handle, refusal of `..` and percent-encoded parent segments, and one music query on `/music/v1/search`. Statuses 401, 403, 409, 426, and 429 are returned to the caller. A closed port is `TRANSPORT_FAILED`. The access token stays in Authorization and out of the error text. Next: a caller-facing map for 403, 409, and 426 where a real endpoint emits them.
- A05 VERIFIED. `npm ci --ignore-scripts` in `/home/msi-workstation/t213-a05/web` added 48 packages, 0 vulnerabilities, 112 MB. Typecheck, 74 tests, and the production build then matched `index-7ZKAT5tJ.js`. Boundary: `web/` does not typecheck without the repo `crypto.mjs`, `crypto.d.mts`, and `protocol/fixtures`. The existing install was left in place. No commit; this was a clean directory on the VM.

## B

- B01 VERIFIED. Commit `a86fab4d4647b8d24703842b98bb10c0c36a9808`. Codec goldens and an unknown expression are refused. The protocol is not drawn as HTML. Boundary: JS output is not Android ciphertext.
- B02 VERIFIED for one press family in the harness. The same commit paints a bundled press sheet. Plain, cover, and overflow sizes matched the earlier smoke. Boundary: opening does not recompute a user plan because no user plan is frozen yet.
- B03 TODO. Other frozen families are not painted. Next: inventory the families and implement the next one.
- B04 IMPLEMENTED. The admitted sheet has an original-photo viewer. It was not clicked in Firefox. Next: keyboard close and a non-cropped original.
- B05 IMPLEMENTED for a received cover byte already inside the capsule, and for a create search hit that does not fetch a cover. Commit `ffcf64edd5879e8b9c2804599f6f17c22d553868`. Next: client cover fetch only when a bounded resolver exists. No dump and no backend proxy.
- B06 TODO. Export control exists on the sheet and was not exercised. Next: composition-only export with a missing-artwork fallback.
- B07 IMPLEMENTED. Lock revokes the harness object URLs (`revoked=yes` in the smoke). Create photo URLs revoke on lock and unmount in code. Next: browser click of sheet close.

## C

- C01 BLOCKED. The JS recovery fixture is not an Android vector. Gradle was not run. Next: a read-only Android producer when a run is allowed.
- C02 IMPLEMENTED. HPKE open works in the harness on a JS-produced capsule. Wrong-owner and tamper paths exist in unit tests. Boundary: no Android-produced ciphertext.
- C03 TODO. Incoming read is one page. Pagination and a local owner index are not done.
- C04 IMPLEMENTED. Camera port, cancel stops tracks, permission denial is a named error. Orientation and normalization are not proved. The production camera button was not clicked.
- C05 BLOCKED. No browser SIFT build. Emscripten is not installed. Thresholds stay. `unavailableRecognition` remains the production engine. Next: a VM-local Emscripten pin, not a stub match.
- C06 TODO. No, one, and many match outcomes are not a product flow. The production match button stays unavailable.
- C07 IMPLEMENTED in the harness with a synthetic camera and matcher. Production bundle has no fixture producer. Boundary: that journey is not a physical scan.

## D

- D01 IMPLEMENTED as a scripted Google nonce and STORED restore. A real Google origin and a real passkey were not used.
- D02 IMPLEMENTED. Registration ticket and one local identity. Handle collision retries the same keysets in unit tests. No live register call.
- D03 IMPLEMENTED. Enrollment posts the existing recovery record and shows the code once. STORED does not create a credential. The save-passkey button was not clicked. The record is a JS producer.
- D04 IMPLEMENTED in part. Locked create and a missing identity do not call the network. Reload loss of a memory identity is not a separate browser test.
- D05 IMPLEMENTED. The one-time recovery code is shown and not stored. It is not required to finish onboarding. No password reset was added.
- D06 IMPLEMENTED for EN/RU/UK on the existing bar. Next: settings copy for login versus keys. No Google Console change.

## E

- E01 VERIFIED as a scripted directory lookup with confirm, late reply, lock, and sign-out. Commit `740d7902e88e67f3416901aa409cf0f6606d10ec`. The form was not clicked in Firefox. No live directory.
- E02 IMPLEMENTED. One front still is bound to the confirmed recipient. Commit `662b9197ca21091727f9125c45ce156ab65c2ae2`. It is not a fingerprint and was not taken by a camera.
- E03 IMPLEMENTED. Three to five JPEG headers under 8 MB. Commit `d7178d9e51c01c0d86fea7bf6903154bc74887e1`. Not decoded or resized.
- E04 VERIFIED for the UTF-8 byte limit. Commit `915fad474887f2a61d3fbce822834cfe4d207775`. The field was not typed in Firefox.
- E05 IMPLEMENTED. First search page and one selected hit. Commit `ffcf64edd5879e8b9c2804599f6f17c22d553868`. No dump, no cover fetch, no send. The field was not typed in Firefox. No live catalog.
- E06 TODO. One frozen composition for an implemented family. Production send stays off.
- E07 TODO. No client photo-analysis model is wired. Empty coordinates will not be presented as analysis.
- E08 TODO. No crop editor.
- E09 TODO. No variant pager and no freeze.

## F

- F01 TODO. Create does not seal. Existing open and publish primitives stay on the receive side.
- F02 TODO. No web publisher.
- F03 TODO. No outbox.
- F04 TODO. Reload drops memory keys. That is not an upload restart test.
- F05 TODO. No create to receive loop.
- F06 TODO. No revoke client.

## G

- G01 TODO. Settings beyond language and account are not a page yet.
- G02 TODO. No block, report, or deletion client. Unavailable stays unavailable.
- G03 TODO. No telemetry client and no new collection.
- G04 TODO. Pending-recipient adapters are not ported.
- G05 TODO. Depends on G04.

## H

- H01 TODO. No manifest.
- H02 TODO. No service worker. A future worker must not cache auth, recovery, API, or artwork.
- H03 TODO. No offline ciphertext cache.
- H04 TODO. No update prompt.

## I

- I01 TODO. The shell is quiet. Reduced motion and teardown timing are not measured.
- I02 IMPLEMENTED for the smoke viewports 1280 and 390. Zoom, keyboard trap, and landscape are not checked.
- I03 IMPLEMENTED for Firefox 155.0.1 only. Chromium and WebKit were not run.
- I04 TODO. No measured photo or camera leak pass beyond revoke on lock.
- I05 IMPLEMENTED for the codec and music page negatives already in vitest. No seeded fuzz.
- I06 TODO. Self-review is not written. It would not be an acceptance.

## J

- J01 IMPLEMENTED as the VM command sequence typecheck, vitest, build, harness, Firefox smoke. `npm ci` is not part of it yet.
- J02 TODO. No CI job file. Do not enable a workflow.
- J03 IMPLEMENTED for the committed collage goldens and the JS recovery fixture, labeled as JS. Android provenance is still missing.
- J04 TODO. No owner runbook.
- J05 TODO. `tasks/T213/result.md` is not written. The mission is still in progress.
