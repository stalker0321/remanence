# Progress

Base: `5a6e7fd65f7be752daa1c933134df7918dde33ee`
Branch: `work/t213-remanence-web-20261017`
Worktree: `/home/vodkolyan/projects/Remanence-web-t213-20261017`
Last good commit: `8100712482fc8ad330bf80e0e66e0de5198e19e0` (C2).

Current milestone: C2 connects a granted postcard to the renderer. Recognition parity remains blocked without a SIFT build.
Next exact action: record whether a browser SIFT build already exists; if it does not fit the budget, leave recognition fail-closed and start new-user onboarding.

## Implemented

- W0 `e5c29632206b84af76581b8c2d1cb71f016d6979`: Vite/React shell, session phases, EN/RU/UK, hash routes.
- W1 `85b81b96487b96a524bb0f501c2467d95f855227`: artifact AAD, recipient-envelope info, TINK HPKE open, TINK AES-GCM artifact decrypt, TINK Ed25519 publish sign/verify, memory key owner.
- Probe `openRecovery()` still returns the diagnostic and wipes seeds. `openIdentity()` feeds the key owner.
- W2 `3172229aedab5c7a6f4645a4f1f47b7476c1ea58`: nonce-bound `POST /v1/auth/google/options` and `/verify`. Server status chooses the route. `NEEDS_HANDLE`, link-required, and upgrade-required do not sign in and do not call `/register`. Signed-in restore uses `/v1/recovery/status` and, only for `STORED`, setup options, one passkey get, setup verify, and `GET /v1/recovery/record`. PRF is not sent. `NOT_STARTED` and `PENDING` do not create a passkey. Keys sit in `MemoryKeys` and drop on lock or sign-out.
- W3 `0b53d4881f15331c34e050b6b6eeed62b64af719`: one incoming page, sender key bundle, publish signature, and signed blob bindings. Download checks length and SHA-256. The camera still stays in memory. Recognition fails closed. A grant admits postcard-matched and does not decrypt.
- C2 `8100712482fc8ad330bf80e0e66e0de5198e19e0`: `receivePostcard` checks the incoming list, one local still, and the grant before decrypt. An empty list, a missing body, a late sign-out, and a cancel do not admit a sheet. The production match button uses `unavailableRecognition`. A camera that resolves after cancel has its tracks stopped.
- C1 `a86fab4d4647b8d24703842b98bb10c0c36a9808`: GENEX03/GENEX04 and BEXPR02 bytes match the Android goldens. `prepare` rejects those codec goldens and paints a bundled press sheet. Unsupported expression magic is refused and not drawn. A verified v3 cover replaces the music band. A missing cover stays text. Overflow rejects the whole sheet. The product shows a sheet only after `presentation-admitted`. The fixture page is a separate Vite root.
- Node 22.22.1 on `msi-workstation` at `/home/msi-workstation/remanence-web-t213-20261017/tools/node`.

## Actual verification

VM `msi-workstation`, Node v22.22.1, source synced from this worktree before the W3 commit. W2's earlier run was 12 files and 31 tests.

- `npm test` (vitest 5.0.5): 17 files, 52 tests, 0 failed. Includes the earlier fixtures plus the receive negatives and one JS-produced press sheet. That sheet is not Android ciphertext.
- `npm run typecheck` (`tsc --noEmit`, TypeScript 7.0.2): exit 0.
- `npm run build` (vite 8.3.4): exit 0. JS `index-CR2o6p5G.js` is 364.07 KB. The Node `crypto` fallback chunk was not requested. The production bundle does not contain the fixture producer.
- Firefox 155.0.1 on the same VM, driven by the snap geckodriver against the real Firefox binary `/snap/firefox/current/usr/lib/firefox/firefox`. Static servers had no CSP. Command: `node web/scripts/firefox-smoke.mjs <web-dir> <out-dir>`.
- Production `dist/` at 1280×800 (en, ru, uk) and 390×844 (uk): `dataset.remanenceBoot=ready`, boot errors empty, no failed resources, no Google script, no fixture string in the bundle.
- Harness at 1280×900 and 390×2400: HPKE seal/open returned `open`. Plain, cover, and blank overflow sheets painted as before. A fourth sheet was decrypted from the JS producer, admitted, painted with note `hello`, then the object URLs were revoked on sign-out. The recognition engine and the ciphertext in that page are synthetic. A real camera and a real provider were not used. Screenshots are in `tasks/T213/screenshots/` outside this repo.

## Blocked integration gates

- Real Google JavaScript origin and a real passkey provider were not exercised. The account button loads Google Identity Services only after a click, and no owner Google login was performed. There is no production login bypass.
- No SIFT/RootSIFT library is in this client. A browser build was not downloaded. `unavailableRecognition` is the recorded toolchain boundary, not a match.
- `tools/recovery/crypto-interop.test.mjs` needs `android/core/crypto/build/recovery-interop/synthetic.json` from the Android test. Gradle was not run. The JS recovery fixture is not that vector.
- No Android-produced artifact ciphertext is in `protocol/fixtures`. The AES-GCM test is a JS producer of the ADR-005 frame.
- Physical postcard scan, camera, and Android-to-web open: not attempted.

## Not verified

- A camera capture, or a click through Google Identity Services. The production shell was rendered in Firefox.
- A real WebAuthn get, virtual authenticator, or physical passkey.
- Recognition parity against the frozen SIFT contract.
- `npm ci` from a clean directory (install used the existing VM `node_modules`).
- Kotlin recovery interop file and any ciphertext produced by Tink on Android beyond the three committed fixtures.

## Next 3 independent tasks

1. Look for an existing browser SIFT/RootSIFT build. Leave the production engine fail-closed if none fits.
2. New-user onboarding with a server-issued ticket, without re-registering an existing account.
3. Create stays behind a real seal. Production send stays off.
