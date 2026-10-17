# Progress

Base: `5a6e7fd65f7be752daa1c933134df7918dde33ee`
Branch: `work/t213-remanence-web-20261017`
Worktree: `/home/vodkolyan/projects/Remanence-web-t213-20261017`
Last good commit: `e5c29632206b84af76581b8c2d1cb71f016d6979` (W0). W1 is the next commit on this branch.

Current milestone: W1 crypto/protocol adapter, checks green, commit in progress
Next exact action: commit W1, then W2 Google verify against the existing endpoints with synthetic responses where the real origin is unavailable

## Implemented

- W0 `e5c29632206b84af76581b8c2d1cb71f016d6979`: Vite/React shell, session phases, EN/RU/UK, hash routes.
- W1: artifact AAD, recipient-envelope info, TINK HPKE open, TINK AES-GCM artifact decrypt, TINK Ed25519 publish sign/verify, memory key owner.
- Probe `openRecovery()` still returns the diagnostic and wipes seeds. `openIdentity()` feeds the key owner.
- Node 22.22.1 on `msi-workstation` at `/home/msi-workstation/remanence-web-t213-20261017/tools/node`.

## Actual verification

VM `msi-workstation`, Node v22.22.1, source synced from this worktree before the W1 commit.

- `npm test` (vitest 5.0.5): 9 files, 21 tests, 0 failed. Includes the committed envelope, publish, and context fixtures.
- `npm run typecheck` (`tsc --noEmit`, TypeScript 7.0.2): exit 0.
- `npm run build` (vite 8.3.4): exit 0. The shell bundle does not yet import the crypto module, so the production JS is still the W0 shell (228 KB). Crypto is covered by the unit tests, not by that bundle.

## Blocked integration gates

- Real Google origin and a real passkey provider are not available from this client. W2 must not invent a production login bypass.
- `tools/recovery/crypto-interop.test.mjs` needs `android/core/crypto/build/recovery-interop/synthetic.json` from the Android test. Gradle was not run. The JS recovery fixture is not that vector.
- No Android-produced artifact ciphertext is in `protocol/fixtures`. The AES-GCM test is a JS producer of the ADR-005 frame.
- Physical postcard scan, camera, and Android-to-web open: not attempted.

## Not verified

- Browser render of the shell.
- `npm ci` from a clean directory (install used the existing VM `node_modules`).
- Kotlin recovery interop file and any ciphertext produced by Tink on Android beyond the three committed fixtures above.

## Next 3 independent tasks

1. Commit W1.
2. W2: nonce-bound Google options/verify client, with the server status choosing the route. Synthetic HTTP tests. Isolate the real-origin blocker.
3. Negative session tests for an existing account never entering handle creation.
