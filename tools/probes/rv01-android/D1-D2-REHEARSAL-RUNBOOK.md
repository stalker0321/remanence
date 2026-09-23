# RV-01 first A/G1 D1→D2 rehearsal runbook (operator)

Scope: one throwaway-probe rehearsal of the D1→D2 handoff on the A/G1
context only. Everything here stays `LOCAL_DRY_RUN_NOT_EVIDENCE`; a debug
sideload never constitutes a physical PASS. No Remanence ARK, account keys,
or user data ever enter this app. Use test-only Google accounts; never
personal accounts.

## Prerequisites (both devices)

- Same-signed, Play-distributed install of this exact probe release on D1
  and on fresh D2. A debug sideload proves logic only, not capability.
- Same test Google account signed in on D1 and D2.
- D1 qualifying screen lock: operator-confirmed PIN, pattern, or password
  (biometric-only does not qualify).
- `isEndToEndEncryptionAvailable() == true` on the P1 check.
- Cloud backup eligibility confirmed and the documented Backup Now
  completed on D1.

## D1 (source)

1. Confirm the P1 inputs on screen (lock kind, backup eligibility,
   Backup Now), then re-check eligibility if it was blocked.
2. Start a new pre-wipe run and let P2 store the exact U.
3. Export the opaque P (D1, pre-wipe check) if a local P3 check is wanted.
4. Export the D2 handoff P (`rv01-sidecar-d2.bin`) through a fresh SAF
   document; keep the file reachable for D2 (USB/workstation copy).
5. Copy the displayed `RV01-D2-HANDOFF-V1` line exactly (22-char K_U plus
   runId/context fields). It is the only carrier of K_U/runId and never
   enters evidence, logs, URIs, or provider identity.

## D2 (fresh target)

1. Fresh-install the same probe release, sign in the same test account,
   and install/select the app through the cloud restore flow where
   applicable.
2. Reselect the SAF P file after reinstall: document selection never
   survives reinstall; the D2 Run button stays disabled until a file is
   selected and no run is active.
3. Paste the handoff line exactly and run D2 resume. The trusted context
   (A, G1, D2_TARGET) is built from the pasted fields, never from P.
4. Read the redacted result only: success is worded exactly
   `U_DURABILITY_PLUS_HARNESS_P`. Anything else is fail-closed or
   incomplete per the on-screen reason; do not retry with edited bytes.

## Cleanup sequencing

D1 cleanup deletes the exact provider U slot. Run it only after all D2
checks finish; early cleanup destroys the rehearsal with no recovery.
The same warning is shown beside the D1 cleanup control in the app.
