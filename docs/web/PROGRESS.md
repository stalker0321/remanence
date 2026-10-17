# Progress

Base: `5a6e7fd65f7be752daa1c933134df7918dde33ee`
Branch: `work/t213-remanence-web-20261017`
Worktree: `/home/vodkolyan/projects/Remanence-web-t213-20261017`
Last good commit: `0b53d4881f15331c34e050b6b6eeed62b64af719` (W3). The C1 render commit follows this file.

Current milestone: C1 frozen collage render is in the working tree and verified on Firefox. Recognition parity remains blocked without a SIFT build.
Next exact action: connect account, keys, incoming sync, scan, grant, decrypt, and this renderer in one journey, including session fencing.

## Implemented

- W0 `e5c29632206b84af76581b8c2d1cb71f016d6979`: Vite/React shell, session phases, EN/RU/UK, hash routes.
- W1 `85b81b96487b96a524bb0f501c2467d95f855227`: artifact AAD, recipient-envelope info, TINK HPKE open, TINK AES-GCM artifact decrypt, TINK Ed25519 publish sign/verify, memory key owner.
- Probe `openRecovery()` still returns the diagnostic and wipes seeds. `openIdentity()` feeds the key owner.
- W2 `3172229aedab5c7a6f4645a4f1f47b7476c1ea58`: nonce-bound `POST /v1/auth/google/options` and `/verify`. Server status chooses the route. `NEEDS_HANDLE`, link-required, and upgrade-required do not sign in and do not call `/register`. Signed-in restore uses `/v1/recovery/status` and, only for `STORED`, setup options, one passkey get, setup verify, and `GET /v1/recovery/record`. PRF is not sent. `NOT_STARTED` and `PENDING` do not create a passkey. Keys sit in `MemoryKeys` and drop on lock or sign-out.
- W3 `0b53d4881f15331c34e050b6b6eeed62b64af719`: one incoming page, sender key bundle, publish signature, and signed blob bindings. Download checks length and SHA-256. The camera still stays in memory. Recognition fails closed. A grant admits postcard-matched and does not decrypt.
- C1: GENEX03/GENEX04 and BEXPR02 bytes match the Android goldens. `prepare` rejects those codec goldens and paints a bundled press sheet. Unsupported expression magic is refused and not drawn. A verified v3 cover replaces the music band. A missing cover stays text. Overflow rejects the whole sheet. The product shows a sheet only after `presentation-admitted`. The fixture page is a separate Vite root.
- Node 22.22.1 on `msi-workstation` at `/home/msi-workstation/remanence-web-t213-20261017/tools/node`.

## Actual verification

VM `msi-workstation`, Node v22.22.1, source synced from this worktree before the W3 commit. W2's earlier run was 12 files and 31 tests.

- `npm test` (vitest 5.0.5): 16 files, 48 tests, 0 failed. Includes the W1 golden fixtures, scripted Google responses, a JS recovery restore, a loopback fetch of the profile header, the golden publish statement as an incoming page, the scan-grant negatives, the expression goldens, and a JS-produced content open. That open is not Android ciphertext.
- `npm run typecheck` (`tsc --noEmit`, TypeScript 7.0.2): exit 0.
- `npm run build` (vite 8.3.4): exit 0. JS `index-BkS5lyFo.js` is 315.46 KB. `@hpke/common` still has a dynamic `import("crypto")` that Vite emits as `__vite-browser-external-D17scyon.js`. The production page did not request that file.
- Firefox 155.0.1 on the same VM, driven by the snap geckodriver against the real Firefox binary `/snap/firefox/current/usr/lib/firefox/firefox`. Static servers had no CSP. Command: `node web/scripts/firefox-smoke.mjs <web-dir> <out-dir>`.
- Production `dist/` at 1280×800 (en, ru, uk) and 390×844 (uk): `dataset.remanenceBoot=ready`, boot errors empty, no failed resources, no Google script, no fixture string in the bundle.
- Harness at 1280×900 and 390×2400: HPKE seal/open returned `open`. Plain sheet painted three photos, the note, and a text music band with a play mark. Cover sheet painted the cover and no play mark. Overflow stayed blank. RU and UK labels were visible. Screenshots are in `tasks/T213/screenshots/` outside this repo.

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

1. One journey from account and keys through incoming sync, scan, grant, decrypt, and the renderer, with logout and late-camera fencing.
2. Keep recognition parity as its own gate. Do not treat the unavailable engine as a scan.
3. New-user onboarding and the create flow stay behind that journey. Production send stays off.
