# Architecture Gate review

STATUS: PASS

## Critical

- None.

## Architecture deviations

- Grok-authored early drafts were treated as unapproved transcription only. Codex replaced/expanded the normative architecture and personally approved the package.
- Conceptual `Receive` is an internal first-receipt lifecycle handled by Scan, not an inbox or home surface.

## Security

- Authentication and E2EE identities are separate.
- Exact Tink HPKE/AEAD/Ed25519 construction, Keystore wrapping, AAD/context, signature binding, device-loss behavior, metadata leakage, active-directory limitation, and recovery path are explicit.
- Raw scans and plaintext/derived recognition material never reach the backend.
- First receipt can obtain matching material through recipient-routed encrypted manifests without server CV.
- Revoked verification keys fail closed; retired historical public keys remain retrievable by immutable ID.

## Recognition

- ORB decision, canonical crop, quality gates, fingerprint format, descriptor/homography pipeline, score formula, weak/strong gates, automatic margins, duplicate-front behavior, ambiguity fallback, recipient baseline, dataset, and M3 metrics are concrete and versioned.
- Seed thresholds are explicitly uncalibrated until M3; no automatic-accuracy claim is made.

## Build/tests

- `git diff --check`: PASS before package commit.
- Normative-document scan for `TODO`, `TBD`, fake crypto, server matching, gallery/inbox paths: PASS; prohibited terms occur only in explicit non-goals/acceptance checks.
- Cardinality trace: PASS — one recognition manifest, one content manifest, 3–5 photos, one recipient envelope, therefore 5–7 declared blobs.
- Lifecycle trace: PASS — sender publish, incoming bootstrap, first receipt, later scan, offline cache, stale key, process death, and device loss terminate safely.
- Physical Android evidence: NOT RUN; not required for Architecture Gate and recorded as an M1/M2 blocker.

## Recommended next actions

1. Assign `M0-01` only.
2. Complete M0 queue sequentially with one commit per task.
3. Run cumulative review no later than 60 minutes or at M0 gate, whichever comes first.

Reviewed commits: repository root through `b783bf19adacd6998612a4579d77871728db9b00`

Reviewed commit: `b783bf19adacd6998612a4579d77871728db9b00`
