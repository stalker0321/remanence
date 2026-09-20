# Status — what was done, blockers, misses, next

Written 2026-09-20 after the transfer dump and device notes. Canonical design
authority is now on this host:

`/home/vodkolyan/remanence_transfer_2026-09-20/Remanence-design/design/handoff/`

(`README.md`, `guide.mjs`, Remainder assets). Generator architecture:

`/home/vodkolyan/remanence_transfer_2026-09-20/Remanence/CANONICAL_PRODUCT_RENDERER_ARCHITECTURE.md`

plus grammar-test v1–v7. Do not treat `/home/vodkolyan/Projects/Remanence-design`
as complete: it has no `handoff/`.

Live Android worktree: `/home/vodkolyan/projects/Remanence-hold-integration`
on `ui/hold-integration`. Current ship: **0.2.0-sift-it.8 / versionCode 19**
(`8614e64`), GitHub prerelease
https://github.com/stalker0321/remanence/releases/tag/v0.2.0-sift-it.8
`origin/main` is still **sift-it.7** (`4e5333d`). Do not reuse code 18 or 19.

M4 worktree: `/home/vodkolyan/projects/Remanence-m4-recovery` on `work/m4-recovery`.
Do not mix Hold/IP/cancel into it.

---

## What was done before this note

### Product stack (send → scan → revoke honesty)

- SA-02 live earlier; cancel/first-open (`45309ac`) then SA-03, IP-01, IP-02,
  Hold rebased onto that stack.
- Prod API `remanence-api:cancel-46e6bac`, DB head `0007_m2_f3_first_open_claim`.
- sift-it.7 APK existed locally but GitHub Release was missing; later published
  at `v0.2.0-sift-it.7` (code 18, `4e5333d`).
- Device notes from two phones: Home card sizes random / empty third of screen,
  no press animation, Remainder looked off-centre, cancel scan looked like
  “didn’t recognize”, generator absent, **offline worked**.

### sift-it.8 hotfix (not fully canonical)

- Home: scan card `weight(1f)` so “got a postcard?” is the large surface;
  “someone in mind?” compact secondary; removed random inset padding.
- Press: `indication = null` plus snap-to-6.dp travel (visible at animator
  scale 0). Guide.mjs wants **3–4 px travel, ~110 ms, edge 5–6 → 1–2 px**.
- Remainder XML scaled to **0.80 / (15.2, 16)** for geometric centre 54,54.
  Handoff measured **0.72 / (21.96, 19.8)** so the curved shoulder stays
  inside the 66-unit circle. `.8` diverged from that on purpose after the
  device complaint; it is a known deviation.
- Scan no-match copy admits a withdrawn postcard. Matcher still cannot tell
  “never sent” from “revoked” once the fingerprint is gone.
- Generator not in the APK (correct: no ResolvedExpression wire).

### M4 (isolated, not in the phone APK)

On `work/m4-recovery`, not merged to main:

| Slice | Commit | What |
| --- | --- | --- |
| REC-03 | `6ff9ef3` | 256-bit ARK, device wrap ≠ identity wrap |
| REC-02 | `fa9ce50` | RecoveryPackage / Wrapper cryptors, TBD(RV-01) |
| REC-04 | `438b849` | Server opaque packages/wrappers, Alembic `0008` |
| REC-05 | `a73cb71` | Adapter negotiation, fail-closed; login is AUTHENTICATION_ONLY |
| REC-06 | `d5dd906` | Device transfer HPKE, 2 ACTIVE slots, revoke+purge |

No Google primitive selected. Login / Restore Credentials / passkey stay
auth-only. Physical REC-01 probes are still the morning throwaway app, not
Remanence.

Music design exists (`REMANENCE_MUSIC_LINKS_DESIGN_2026-09-20.md`) and is
**out of this queue** until send→scan→revoke is honest on devices.

---

## Blockers (still true)

1. **REC-01 physical evidence** — two Google phones, two test accounts, P1–P8.
   Without it M4 cannot pick an adapter; REC-05 production adapter stays
   `PendingRv01RecoveryAdapter` / unavailable.
2. **ResolvedExpression is not a capsule artifact** — protocol is JPEG + note.
   Generator cannot ship in-app until freeze/hashes/round-trip exist. Handoff
   and architecture both say this. Note ceiling in renderer (200–300 chars)
   must not silently replace v1’s 1000 UTF-8 bytes.
3. **Music legal / Create search** — Deezer ToS; flag off; not this pass.
4. **GitHub PAT cannot open PRs** (403). Push branch + tag; `/pull/new` or
   fast-forward main by hand.
5. **No adb on this host** — design feel is device-only; unit tests are not
   launcher/IME/Carry evidence.
6. **Animator duration scale** — if set to 0 for the Hold checklist, system
   ripple disappears. Judge press at 1x, then confirm snap at 0.
7. **Transfer dump is not git** — `remanence_transfer_2026-09-20` is a copy.
   Do not commit 3.9 GiB into Remanence. Copy only needed assets/docs.

---

## What was missed / wrong

- Shipped sift-it.7 without a GitHub Release (fixed later).
- Started M4 and Hold-hotfix without the handoff tree; `guide.mjs` was
  missing on the host until the second transfer folder.
- Remainder 0.80 vs measured 0.72 (safe-circle). Bounding-box centering is
  exactly what handoff forbids.
- Press 6.dp / snap / 140 ms vs guide 3–4 px / 110 ms / remaining 1–2 px edge.
- Home `expand` fill is directionally right (scan-led, grow surfaces) but not
  checked against compact display role at large text, nor 18–20 dp gutters.
- Carry (Home→auth, camera freeze, verified front→capsule, underlayer drag)
  was never implemented; only static Hold chrome.
- Public Home vs auth-first: Hold already has public Home + intent; still
  needs device confirmation.
- Cancel scan copy improved; no withdrawn-fingerprint path, so revoke still
  looks like no-match at the matcher.
- Generator playground/v7 was on the host; architecture PDF/md was not, until
  transfer. Even now, no Android adapter.
- `implementation/remanence` in the design dump is **sift-it.6**. Do not
  rebase onto it; live tree is hold-integration `.8`.

---

## Next — design (small pieces, this tree)

Authority: handoff README + `guide.mjs`. Existing crypto/scan/create fences
stay. No fake generator candidates, no share, no music controls.

Order (one slice per commit; bump versionCode only when cutting an APK):

1. **Remainder back to handoff XML** — scale 0.72, translate (21.96, 19.8);
   monochrome from the same silhouette. Device-check optical size after.
2. **Action-object pressure to guide** — 6.dp edge, 3–4.dp travel, ~110 ms,
   pressed edge 1–2.dp; keep immediate press (snap down) so scale-0 still
   shows contact. Primary/secondary buttons: 52 / 48 min hit.
3. **Home gutters** — done in this tree: 24 dp sides, 20 under 400 dp, 18
   under 360; group 16. Compact display + scroll when fontScale ≥ 1.3 or
   height < 640. Do not shrink user text.
4. **Forms** — IME: body scrolls, primary stays reachable; password masked
   (already); retain invalid values (handoff).
5. **Capture** — quiet portrait 3:4; instruction + shutter; permission
   recovery already exists — check copy, not geometry of the matcher.
6. **Scan product copy** — map real failure reasons, not a single glare
   sentence; keep withdrawn wording; no diagnostics dump.
7. **Opened capsule** — whole composition, no app header; underlayer drag +
   48 dp reveal; instant private teardown. Carry later, after layout is honest.
8. **Carry** — only after 1–7 survive a device pass. Reduced-motion must
   skip travel, not skip privacy gates.

Then: new APK (code **20**), GitHub prerelease, two-phone checklist in
`device-review.md` with animator scale 1x then 0.

---

## Next — M4 (other worktree)

Do not continue REC-07+ until REC-01 morning probes exist. Queue:

1. User runs REC-01 throwaway on two devices (see
   `docs/recovery/DEVICE-LOOP.md` on the M4 tree).
2. Record matrix; only then replace `TBD(RV-01)`.
3. REC-07 manual secret is optional, never onboarding, and not a Google
   fallback.
4. Server `0008` is **not** on prod. Do not migrate prod for M4 yet.

If design work is in flight, leave M4 parked.

---

## First slice after this file

Restore Remainder to the measured handoff transform and align action-object
press to 3–4.dp / 110 ms. No APK bump in that slice unless a device build is
explicitly requested.
