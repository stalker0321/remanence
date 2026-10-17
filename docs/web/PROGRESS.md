# Progress

Base: `5a6e7fd65f7be752daa1c933134df7918dde33ee`
Branch: `work/t213-remanence-web-20261017`
Worktree: `/home/vodkolyan/projects/Remanence-web-t213-20261017`
Last good commit: `85b81b96487b96a524bb0f501c2467d95f855227` (W1). W2 is the next commit on this branch.

Current milestone: W2 Google verify and signed-in passkey restore, checks green, commit in progress
Next exact action: commit W2, then W3 owner-scoped fetch and the recognition adapter boundary

## Implemented

- W0 `e5c29632206b84af76581b8c2d1cb71f016d6979`: Vite/React shell, session phases, EN/RU/UK, hash routes.
- W1 `85b81b96487b96a524bb0f501c2467d95f855227`: artifact AAD, recipient-envelope info, TINK HPKE open, TINK AES-GCM artifact decrypt, TINK Ed25519 publish sign/verify, memory key owner.
- Probe `openRecovery()` still returns the diagnostic and wipes seeds. `openIdentity()` feeds the key owner.
- W2: nonce-bound `POST /v1/auth/google/options` and `/verify`. Server status chooses the route. `NEEDS_HANDLE`, link-required, and upgrade-required do not sign in and do not call `/register`. Signed-in restore uses `/v1/recovery/status` and, only for `STORED`, setup options, one passkey get, setup verify, and `GET /v1/recovery/record`. PRF is not sent. `NOT_STARTED` and `PENDING` do not create a passkey. Keys sit in `MemoryKeys` and drop on lock or sign-out.
- Node 22.22.1 on `msi-workstation` at `/home/msi-workstation/remanence-web-t213-20261017/tools/node`.

## Actual verification

VM `msi-workstation`, Node v22.22.1, source synced from this worktree before the W2 commit.

- `npm test` (vitest 5.0.5): 12 files, 31 tests, 0 failed. Includes the W1 golden fixtures, scripted Google responses, a JS recovery restore, and a loopback fetch of the profile header.
- `npm run typecheck` (`tsc --noEmit`, TypeScript 7.0.2): exit 0.
- `npm run build` (vite 8.3.4): exit 0. The bundle is about 281 KB and includes the account flow plus `@hpke/core`. `@hpke/common` also contains a dynamic `import("crypto")` fallback; Vite externalizes that Node module. The library uses `globalThis.crypto` when the browser provides it. This build is not a browser run.

## Blocked integration gates

- Real Google JavaScript origin and a real passkey provider were not exercised. The account button loads Google Identity Services only after a click, and no owner Google login was performed. There is no production login bypass.
- `tools/recovery/crypto-interop.test.mjs` needs `android/core/crypto/build/recovery-interop/synthetic.json` from the Android test. Gradle was not run. The JS recovery fixture is not that vector.
- No Android-produced artifact ciphertext is in `protocol/fixtures`. The AES-GCM test is a JS producer of the ADR-005 frame.
- Physical postcard scan, camera, and Android-to-web open: not attempted.

## Not verified

- Browser render of the shell, or a click through Google Identity Services.
- A real WebAuthn get, virtual authenticator, or physical passkey.
- `npm ci` from a clean directory (install used the existing VM `node_modules`).
- Kotlin recovery interop file and any ciphertext produced by Tink on Android beyond the three committed fixtures.

## Next 3 independent tasks

1. Commit W2.
2. W3: owner-scoped encrypted manifest fetch with the existing limits, and a camera adapter that does not upload the raw scan.
3. Recognition parity: identify whether a browser SIFT build can match the frozen contract; if the toolchain blocks it, record the mismatch and keep the adapter separate from decrypt.
