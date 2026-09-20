# Codex handoff — Hold chrome freeze

Date: 2026-09-20. Previous agent: Grok. Do not restart M4 or generator.

## Closed slice (done)

Branch `ui/hold-integration` (Hold tree:
`/home/vodkolyan/projects/Remanence-hold-integration`).

| Commit | What |
| --- | --- |
| `8614e64` | sift-it.8 APK (code 19, GitHub `v0.2.0-sift-it.8`) — Home fill, press snap, bbox-centred Remainder. **Superseded optically.** |
| `b910e15` | Remainder **back to handoff 0.72 / (21.96, 19.8)**; press 4.dp / 110 ms; `docs/hold/STATUS.md` |
| `8627de2` | Home gutters 24/20/18, compact+scroll at large type |
| `4c04cff` | Auth: `HoldFormScaffold` pins submit above IME; failed login keeps typed values |

These three after `.8`, plus the HOLD-05 capture copy and HOLD-06 scan product
copy (items 5–6) now feature-committed on this branch, are **not** in an APK
yet. Next distributed build must be **versionCode 20** (never reuse 18 or 19).
`.8` names the last shipped APK, not the worktree HEAD.

Orchestrator-managed commits and feature-branch pushes on `ui/hold-integration`
are allowed. `main`, tags, GitHub releases, APK publication, server deploy and
DB/Caddy changes still require explicit owner approval.

Release status: feature commit `24152ad` ("feat(android): localize Hold capture
and scan product copy") is **local only** — the feature push failed on missing
GitHub auth and was not retried. The `chore(release): prepare 0.2.0-sift-it.9`
commit sets versionName 0.2.0-sift-it.9 / versionCode 20. The APK build is
owner-authorized; tag `v0.2.0-sift-it.9`, the GitHub release, and any
`origin/main` fast-forward remain owner-gated.

`origin/main` is still sift-it.7 (`4e5333d`). PAT cannot open PRs.

## Canonical files (read first)

- `/home/vodkolyan/remanence_transfer_2026-09-20/Remanence-design/design/handoff/README.md`
- `.../handoff/guide.mjs`
- `docs/hold/STATUS.md` (queues, misses, blockers)
- `docs/hold/device-review.md`
- `CHANGELOG.md` Unreleased section (chrome after `.8`)

`/home/vodkolyan/Projects/Remanence-design` has **no** `handoff/`. Ignore
`implementation/remanence` in the transfer (it is sift-it.6).

## Items 5–6 (implemented, feature-committed)

Do **not** mix trees. Stay on `ui/hold-integration`. Both slices are
feature-committed on top of `77b3b38`. The gate is a filtered
`:app:testDebugUnitTest --tests` run: **131/131** (`capture.*`, `ui.scan.*`,
`RootScanFlowLayoutTest`, `CreateSmallViewportTest`), final independent review
PASS. It is **not** the full suite and not device evidence.

5. **Capture copy** — done (feature-committed). Quiet portrait 3:4 instruction +
   shutter, honest permission / binding / capture / processing / retry
   wording, rejection guidance EN/RU/UK; duplicate untranslated surface header
   removed. Matcher geometry, CameraX crop and SIFT unchanged.
6. **Scan product copy** — done (feature-committed). Per-state EN/RU/UK copy for
   Matching / Accepted (content-free) / RecaptureGuidance / IndexUnavailable /
   MaterialPending and the chooser (trusted / unverified / claim / date /
   scan-again), with bidi-isolated user placeholders. RecaptureGuidance is one
   honest withdrawn-aware message plus one real recapture action; the dead
   inline camera is removed. It stays reason-agnostic because
   `ScanMatchUiState` has no reason field. ScanViewModel / state / grants /
   matcher / crypto unchanged. RU/UK authored copy awaits physical/native
   context review.

Then 7 underlayer, 8 Carry — only after a device pass. **No Carry now.**

## Do not

- Generator / ResolvedExpression / music / share
- M4 (`Remanence-m4-recovery`) or `TBD(RV-01)` selection
- FF `origin/main` unless the owner asks
- Broad ScanViewModel refactors
- Fake capsule candidates or fixture compositions

## M4 (parked)

`work/m4-recovery` has REC-02…06. Next is physical REC-01, not REC-07.
See `docs/recovery/DEVICE-LOOP.md` on that tree.

## APK

Cut **0.2.0-sift-it.9 / code 20** after the items 5–6 feature commit is
reviewed and the owner approves (no APK yet). Debug:
`-Premanence.apiBaseUrl=https://remanence.hryshyn.dev/`
`-Premanence.localization.v2.enabled=true`
Then `gh release create` prerelease + named APK. Animator scale 1x to judge
press, then 0 to confirm snap.
