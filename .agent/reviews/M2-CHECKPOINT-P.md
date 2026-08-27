# M2 Checkpoint P review

STATUS: PASS

## Critical

- None open across P01-P13 at reviewed commit.
- Physical CameraX/OpenCV evidence remains P14 and is an explicit integration
  gate, not part of the P01-P13 architecture checkpoint.

## Architecture deviations

- None accepted silently. Recovery is explicitly deferred to M4 by ADR-010.
- M2 remains existing-account recipient only; email invitations remain M2.x.

## Security

- Account-owned Room rows, filesystem roots, WorkManager identities, logout,
  plaintext staging, and cold-start identity binding are account-scoped.
- Publisher, envelope, statement, artifact AAD, control/index acceptance, and
  presentation acceptance retain the established cryptographic framing.
- Decrypted manifests fail closed on identity/layout/content constraints.
- DAO state transitions are canonical named operations; callers cannot provide
  arbitrary source sets or target states.
- Sender retry key material is wrapped, owner-bound, restart-safe, and removed
  only by its defined lifecycle.

## Recognition

- Matching remains local-only and account-scoped.
- Control/index acceptance can admit the recognition artifact before full
  presentation material without exposing capsule plaintext.
- Recipient presentation still requires the scan-grant path; no gallery or
  inbox route was introduced.

## Build/tests

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug --console=plain` — PASS; installable debug APK produced.
- `uv lock --check` — PASS.
- `uv run --locked pytest -q -W error` — PASS: 230 passed, 94 skipped. Skips are environment/integration-dependent and must be enabled at the server gate.
- `git diff main...HEAD --check` — PASS.
- Worktree clean at review completion.

## Recommended next actions

1. Create an integration branch and reconcile the M2 foundation with the latest main hotfixes without mutating main directly.
2. Run the integrated Android/server baseline.
3. Implement the M2 server routing vertical slice, then Android upload/incoming/prefetch wiring.
4. Complete P14 physical CameraX/OpenCV evidence before the product integration gate passes.

Reviewed commits: `55c729b..6431e6e`

Reviewed commit: `6431e6e`
