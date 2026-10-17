# Progress

Base: `5a6e7fd65f7be752daa1c933134df7918dde33ee`
Branch: `work/t213-remanence-web-20261017`
Worktree: `/home/vodkolyan/projects/Remanence-web-t213-20261017`
Last good commit: `ffcf64edd5879e8b9c2804599f6f17c22d553868` (C4 music search).

Current milestone: a confirmed recipient, one local front still, a note inside 1000 UTF-8 bytes, up to five local JPEGs, and one selected music hit stay in memory for this visit. The still is not a fingerprint. The photos are not resized. The music hit is the first search page only. Recognition stays fail-closed. Production send stays off.
Next exact action: add the settings hash and check locked deep links in Firefox. A route must not sign in, unlock keys, or admit a postcard.

## Implemented

- W0 `e5c29632206b84af76581b8c2d1cb71f016d6979`: Vite/React shell, session phases, EN/RU/UK, hash routes.
- W1 `85b81b96487b96a524bb0f501c2467d95f855227`: artifact AAD, recipient-envelope info, TINK HPKE open, TINK AES-GCM artifact decrypt, TINK Ed25519 publish sign/verify, memory key owner.
- Probe `openRecovery()` still returns the diagnostic and wipes seeds. `openIdentity()` feeds the key owner.
- W2 `3172229aedab5c7a6f4645a4f1f47b7476c1ea58`: nonce-bound `POST /v1/auth/google/options` and `/verify`. Server status chooses the route. `NEEDS_HANDLE`, link-required, and upgrade-required do not sign in and do not call `/register`. Signed-in restore uses `/v1/recovery/status` and, only for `STORED`, setup options, one passkey get, setup verify, and `GET /v1/recovery/record`. PRF is not sent. `NOT_STARTED` and `PENDING` do not create a passkey. Keys sit in `MemoryKeys` and drop on lock or sign-out.
- W3 `0b53d4881f15331c34e050b6b6eeed62b64af719`: one incoming page, sender key bundle, publish signature, and signed blob bindings. Download checks length and SHA-256. The camera still stays in memory. Recognition fails closed. A grant admits postcard-matched and does not decrypt.
- C2 `8100712482fc8ad330bf80e0e66e0de5198e19e0`: `receivePostcard` checks the incoming list, one local still, and the grant before decrypt. An empty list, a missing body, a late sign-out, and a cancel do not admit a sheet. The production match button uses `unavailableRecognition`. A camera that resolves after cancel has its tracks stopped.
- C3: no browser SIFT build is available. Desktop OpenCV 4.10 JNI and the OpenCV 4.12 app AAR are not a WASM worker. Emscripten is not installed. `unavailableRecognition` stays.
- C4 registration `69e42184b60647e1812a94ebc6bee45f4f7808ac`: a `NEEDS_HANDLE` ticket stays in memory. One local HPKE/Ed25519 identity is posted to the existing register endpoint. A taken handle retries the same keysets. An existing account or a link conflict wipes the draft and does not register.
- C4 enrollment `dee92c898273485e17e48462dd616ee6fe1bcf1a`: that same identity is sealed into the existing recovery record after one passkey create, setup, and `POST /v1/recovery/record`. Readback unlocks the keys. `STORED` reads the record and does not create a credential or a package. A missing identity does not generate a replacement and does not call the network. The one-time code is shown in the page and is not stored. The record is a JS producer.
- C4 recipient `740d7902e88e67f3416901aa409cf0f6606d10ec`: `GET /v1/directory/handles/{handle}` returns one snapshot. Confirmation freezes it. For myself requires the signed-in user id. A late reply, a lock, and a sign-out drop it. Nothing is sent.
- C4 front `662b9197ca21091727f9125c45ce156ab65c2ae2`: one camera still is bound to that snapshot. Cancel, close, lock, and a different recipient zero the bytes and stop the tracks. It is not uploaded and it is not a fingerprint.
- C4 note `915fad474887f2a61d3fbce822834cfe4d207775`: the note is counted in UTF-8 bytes against `note_max_utf8_bytes` (1000). Over-limit text stays editable and is not accepted. Lock clears it.
- C4 photos `d7178d9e51c01c0d86fea7bf6903154bc74887e1`: the draft holds 3 to 5 JPEGs under `normalized_photo_max_plaintext_bytes`. A sixth file, a non-JPEG, and an oversized file are refused. Remove and reorder stay local. Lock zeroes the bytes. The long edge is not measured.
- C4 music `ffcf64edd5879e8b9c2804599f6f17c22d553868`: `GET /music/v1/search` with `q`, `limit=10`, and `offset=0`. A blank query and a query over 100 code points do not call the network. A bad page, a 401, and a 422 are refused. A 429 and a 503 stay unavailable and are not an empty page. One selected hit survives a late page. Lock and sign-out drop it. Cover bytes are not fetched. The recording id is kept on the hit and is not shown.
- C1 `a86fab4d4647b8d24703842b98bb10c0c36a9808`: GENEX03/GENEX04 and BEXPR02 bytes match the Android goldens. `prepare` rejects those codec goldens and paints a bundled press sheet. Unsupported expression magic is refused and not drawn. A verified v3 cover replaces the music band. A missing cover stays text. Overflow rejects the whole sheet. The product shows a sheet only after `presentation-admitted`. The fixture page is a separate Vite root.
- Node 22.22.1 on `msi-workstation` at `/home/msi-workstation/remanence-web-t213-20261017/tools/node`.

## Actual verification

VM `msi-workstation`, Node v22.22.1, source synced from this worktree before the W3 commit. W2's earlier run was 12 files and 31 tests.

- `npm test` (vitest 5.0.5): 24 files, 73 tests, 0 failed. Includes the earlier fixtures, the receive negatives, one JS-produced press sheet, registration, enrollment, recipient lookup, the front still, the note byte limit, the photo draft, and the music search page. That sheet is not Android ciphertext. The registration identity and the recovery record are JS WebCrypto producers. The music page is a scripted transport, not a live catalog.
- `npm run typecheck` (`tsc --noEmit`, TypeScript 7.0.2): exit 0.
- `npm run build` (vite 8.3.4): exit 0. JS `index-DyRX6lAI.js` is 404.22 KB, gzip 118.58 KB. CSS `index-CovRG_mx.css` is 4.60 KB. The product page did not request the external `crypto` stub. The production bundle does not contain the fixture producer.
- Firefox 155.0.1 on the same VM, driven by the snap geckodriver against the real Firefox binary `/snap/firefox/current/usr/lib/firefox/firefox`. Static servers had no CSP. Command: `node web/scripts/firefox-smoke.mjs <web-dir> <out-dir>`.
- Production `dist/` at 1280×800 (en, ru, uk) and 390×844 (uk): `dataset.remanenceBoot=ready`, boot errors empty, no failed resources, no Google script, no fixture string in the bundle.
- Harness at 1280×900 and 390×2400: HPKE seal/open returned `open`. Plain, cover, and blank overflow sheets painted as before. A fourth sheet was decrypted from the JS producer, admitted, painted with note `hello`, then the object URLs were revoked on sign-out. The same page generated one local identity, saw its bundle id match the encryption keyset (`matched`), and wiped the seed copies. The recognition engine and the ciphertext in that page are synthetic. A real camera, a real Google account, and a real passkey were not used. Screenshots are in `tasks/T213/screenshots/` outside this repo.

## Blocked integration gates

- Real Google JavaScript origin and a real passkey provider were not exercised. The account button loads Google Identity Services only after a click, and no owner Google login was performed. There is no production login bypass.
- No SIFT/RootSIFT library is in this client. Desktop OpenCV 4.10 JNI and the app's OpenCV 4.12 AAR are present; Emscripten and `opencv.js` are not. The official `platforms/js/build_js.py` was not run. `unavailableRecognition` stays the production engine.
- `tools/recovery/crypto-interop.test.mjs` needs `android/core/crypto/build/recovery-interop/synthetic.json` from the Android test. Gradle was not run. The JS recovery fixture is not that vector.
- No Android-produced artifact ciphertext is in `protocol/fixtures`. The AES-GCM test is a JS producer of the ADR-005 frame.
- Physical postcard scan, camera, and Android-to-web open: not attempted.

## Not verified

- A camera capture, or a click through Google Identity Services. The production shell was rendered in Firefox.
- A real WebAuthn create or get, a virtual authenticator, or a physical passkey. Enrollment used a scripted credential port. The save-passkey button was not clicked in Firefox.
- The recipient form, the front camera button, the note field, the photo picker, and the music field were not clicked in Firefox. Lookup and music tests use a scripted transport. No live directory or catalog call was made. The front still was not taken by a physical camera. Photo bytes in the test are synthetic JPEG headers, not decoded images. Music cover bytes were not fetched. A MusicBrainz dump was not downloaded.
- Recognition parity against the frozen SIFT contract.
- `npm ci` from a clean directory (install used the existing VM `node_modules`).
- Kotlin recovery interop file and any ciphertext produced by Tink on Android beyond the three committed fixtures.

## Next 3 independent tasks

1. Add the settings hash and check locked deep links in Firefox. A route must not sign in or admit a postcard.
2. One frozen composition for an implemented family, still with production send off.
3. A browser SIFT build still needs a VM-local Emscripten pin and an OpenCV 4.12 `features2d` wasm. Thresholds stay put.
