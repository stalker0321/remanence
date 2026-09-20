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

These three after `.8` are **not** in an APK yet. Next distributed build must be **versionCode 20** (never reuse 18 or 19).

`origin/main` is still sift-it.7 (`4e5333d`). PAT cannot open PRs.

## Canonical files (read first)

- `/home/vodkolyan/remanence_transfer_2026-09-20/Remanence-design/design/handoff/README.md`
- `.../handoff/guide.mjs`
- `docs/hold/STATUS.md` (queues, misses, blockers)
- `docs/hold/device-review.md`
- `CHANGELOG.md` Unreleased section (chrome after `.8`)

`/home/vodkolyan/Projects/Remanence-design` has **no** `handoff/`. Ignore
`implementation/remanence` in the transfer (it is sift-it.6).

## Your first work (item 5, then 6)

Do **not** mix trees. Stay on `ui/hold-integration`.

5. **Capture copy only** — `CaptureAttemptSurface.kt`. Portrait 3:4 already.
   Quiet instruction + shutter. Do not change matcher geometry, CameraX
   crop, or SIFT. Permission recovery already exists.
6. **Scan product copy** — `ScanScreen.kt` + `hold_strings.xml` (en/ru/uk).
   Map IndexUnavailable / MaterialPending / RecaptureGuidance / chooser.
   Keep withdrawn wording. No diagnostic dumps.

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

Cut **0.2.0-sift-it.9 / code 20** after 5–6 (or after chrome-only if they
want phones sooner). Debug:
`-Premanence.apiBaseUrl=https://remanence.hryshyn.dev/`
`-Premanence.localization.v2.enabled=true`
Then `gh release create` prerelease + named APK. Animator scale 1x to judge
press, then 0 to confirm snap.
