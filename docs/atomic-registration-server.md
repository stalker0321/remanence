# Atomic registration A — isolated server candidate

Owner decision 2026-10-21; common source base c4f0c9579cfb87b9c6af163661ee9d9c32423879.
Shared interface: T403 `api-contract-v1.md`, SHA256
`22e08ea72729432876b72e874cba349a54ae2e3bd78ee5fd78fe8e91ea818abf`.
This is implementation evidence, not independent acceptance or rollout approval.

## Boundaries and transaction map

`GoogleProof.onboarding_version=1` uses the existing verified Google subject to
reserve the immutable final account UUID. Existing subjects still log in to their
original account. A setup bearer is a different namespace (`rgs_`), stored only
as SHA-256, and cannot authenticate ordinary product routes. No User, permanent
bundle, GoogleIdentity, AuthSession or send provision exists at reservation time.

`registration_setup/{models,schemas,service}.py` owns only public draft metadata,
scoped ceremonies, final authorizations and compact accepted outcomes.
`api/registration_setup.py` exposes the frozen reservation routes; malformed
nested public DTOs fail closed without reflecting input. Existing real WebAuthn
and ADR-011 validators are reused. No server PRF/ARK/code/private identity input,
new ciphertext format, SEALED route, activation signature or digest protocol.

All same-subject operations acquire the same transaction advisory lock followed
by the reservation row lock. Fresh Google proof rotates epoch/session/bearer,
deleting old challenges/grants while retaining verified draft credentials. A GET
is a locked observation, **not** a fence against a later old-writer commit.
Finalization, takeover, status, leave, cancel, expiry and restart serialize.

Finalization burns the addressed grant before a savepoint, then validates exact
reserved account/bundle/credential/generation and the unchanged ADR-011 manifest
signature. The existing RegistrationService accepts a server-only reserved UUID;
it does not commit. In one outer transaction the service inserts User, send
provision, ACTIVE public bundle, session, Google link, selected recovery credential,
exact recovery record and accepted outcome, and removes consumed draft material.
No permanent table/FK was weakened. A savepoint is not a separate permanent commit.

An accepted exact replay compares every intent field to the actual immutable
permanent rows and returns outcome only, before checking old grant expiration or
consumption. No token/session/provision reissue. A changed operation or changed
intent fails closed. Current setup capability/epoch and accepted grant secret are
still required. Handle conflict rolls back all permanent work and unconsumes only
that grant, without extending its deadline; corrected handle uses a new operation
with the same candidate. Other failed bindings burn the grant.

Unexpected storage/commit failure is `503 SETUP_UNAVAILABLE`: outcome UNKNOWN.
Tests inject a *successful real PostgreSQL commit followed by lost acknowledgement*
and verify locked ACTIVE status/exact outcome replay, with no second account.

## Default OFF and safe stop

Both new settings default false. `registration_atomic_required` latches the
database policy on the first Google verify/register request handled with that
setting; new Google create-first requests/tickets then receive 426. Once set,
changing flags back cannot erase that fence. `registration_atomic_admission_enabled`
requires required+Google+recovery in DEV/TEST. BETA/PROD cannot use atomic setup.
Stopping admission leaves existing setup completion, resume, login and readback
available. No live environment/config/flag has been changed.

Policy cutover assumes all serving writers understand this additive migration;
an older binary that ignores the latch is NOT a safe downgrade plan. No migration
downgrade is supplied: it raises instead of silently erasing receipts or the latch.
Password and existing receive-only protocols are not converted by this change.

Absolute defaults: reservation 24h, capability 10m, ceremony/final grant 5m,
accepted compact receipt 30d. Setup state uses `app.state.registration_clock`
when supplied by isolated fixtures; otherwise UTC now. Existing Google nonce and
ordinary session clocks remain unchanged. Fresh proof renews only the capability,
not reservation expiry; replay renews nothing. Per-owner bounds: 4 credentials,
8 live challenges/grants; global bounds: 512 live reservations and 512 live
setup ceremonies. Consumed/expired addressed ceremonies are pruned on issuance.
Expiry/cancellation clears only unpublished data; ACTIVE never expires/cancels.
Terminal restart requires fresh Google proof and creates at most one successor.
No credential migration or automatic retry/create loop.

Expiry and receipt compaction are lazy on addressed reservations, with bounded
draft state per reservation. This does not promise a global retention sweep or
deletion schedule; no cron/cleanup deployment is introduced. Compact receipt expiry
does not delete or replace permanent identity, bundle, recovery or send accounting.

## Additive migration and readiness

`0018_atomic_registration` follows `0017_send_access`. Four new tables and the
singleton false policy row only; no old table/backfill/reset. Empty and populated
unique PostgreSQL fixture upgrades compare old table contents, including recovery,
sessions, send provision, capsules and pending delivery opaque bytes.

Approved A/M1+M2 publishes permanent account data at commit, **before** client GET
readback. Local READY still requires actual authenticated recovery GET, both public
anchors, actual unwrap/private-public correspondence and current owner-bound
installation. Server tests unwrap bytes fetched from PostgreSQL; browser lifecycle
and the web→API→PG composition belong to T404/T405 and are separate evidence.
Optional staged S1/S2 is not implemented.

## Verification and limits

New suites: `test_atomic_registration.py`, `test_atomic_registration_edges.py`,
`test_atomic_registration_migration.py`. Use installed dependencies, synthetic RSA
Google cert transport (real signature/claim verifier), ES256 WebAuthn proofs and
real Tink/ADR-011 crypto. Fault hooks fail operations; they never approve invalid
credentials. Only bounded budget tests insert synthetic draft metadata directly.

All PG tests use the existing `remanence_tmp_<uuid>` fixture and shared server
flock. Fixture teardown drops only its newly created database; no baseline or live
DB is reset. Raw failed development runs are retained with final pinned reruns.
Only two existing migration-head assertions were advanced to 0018; accounting,
history/read cache and pending delivery implementation are unchanged.

Inherited cross-stack/admission browser harnesses retain their own frozen 0017
baseline assertions and are not run or presented as T403 proof. Restricted T390/
T399 work is not continued. No Android/build/MSI/AVD, real provider/device, live
migration, deployment, release, independent review or browser acceptance is claimed.
