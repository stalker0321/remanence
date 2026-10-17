# Progress

Base: `5a6e7fd65f7be752daa1c933134df7918dde33ee`
Branch: `work/t213-remanence-web-20261017`
Worktree: `/home/vodkolyan/projects/Remanence-web-t213-20261017`
Last good commit: none yet (W0 not committed)

Current milestone: W0 foundation
Next exact action: install, typecheck, test, and production-build on the VM, then commit W0

## Implemented

- Isolated worktree created. Integration worktree left at the same BASE, clean.
- `web/` package skeleton, session phase machine, EN/RU/UK catalog, hash router, Hold-colored shell.
- Node 22.22.1 installed on `msi-workstation` at
  `/home/msi-workstation/remanence-web-t213-20261017/tools/node`
  after SHA-256 check of the official linux-x64 tarball.

## Actual verification

Not run yet.

## Blocked integration gates

- Real Google origin / passkey provider: not attempted.
- Physical Android ↔ web open: not attempted.
- Browser camera and SIFT parity: not attempted.

## Not verified

- `npm test`, `npm run typecheck`, `npm run build`
- Any browser render

## Next 3 independent tasks

1. Commit W0 after the VM checks pass.
2. W1: artifact AAD, HPKE envelope open, Tink AES-GCM artifact decrypt, memory key owner.
3. Negative vectors for wrong owner, tampered prefix, truncated input, unknown version.
