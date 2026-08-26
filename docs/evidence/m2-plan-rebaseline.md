# M2 plan rebaseline evidence

Date: 2026-08-26

Branch/worktree: `plan/m2-rebaseline` in
`/home/vodkolyan/projects/Remanence-m2-plan`, based on rebrand commit
`f4858e1`.

## Reason

The original M2 queue was written before the real M1 vertical slice and its
state/lifecycle reviews. This rebaseline compares the plan with the actual M1
publisher, ciphertext outbox, incoming tables, crypto acceptance gate, local
recognition, scan grant, and account lifecycle. It changes documentation only.

## Corrected assumptions

1. M2 generalizes M1 components; it does not recreate them.
2. Distinct accounts require local account scoping before upload/sync/index
   integration.
3. A process-death-safe stale-recipient retry requires sender-owned wrapped
   capsule-key material; a recipient envelope is not sender-readable.
4. Recognition-first sync requires staged control/index and presentation
   acceptance around one canonical verifier; the M1 all-blobs gate cannot be
   applied unchanged.
5. `CIPHERTEXT_SYNCED` means all ciphertext, while `INDEX_CACHED` is local-only.
6. WorkManager transitions inherit the M1 generation, owned-job, scoped-file,
   visible-terminal-state, and logout cancellation requirements.
7. Object storage plus PostgreSQL is not a single atomic system; promoted
   orphan ciphertext is handled by garbage collection.

## Email invite finding

The future invite idea is recorded without entering M2 scope. Protocol v1
binds `recipient_user_id` into every artifact AAD and signed statement, so
“add the recipient envelope after registration” is insufficient when no user
ID existed during artifact encryption. ADR-009 defers the required choice:

- reserve the pending UUID as the exact future immutable user ID; or
- introduce a new protocol version with a stable recipient-target binding.

M2 continues to require an existing account, user ID, handle, and active key
bundle. No pending-email table, endpoint, or UI stub is planned.

## Queue and review policy

The implementation queue now has prerequisites `M2-P01..P14`, server tasks
`M2-S01..S21`, and Android/two-device tasks `M2-A01..A25`, each with explicit
dependencies and a minimum regression proof. Reviews occur at prerequisite,
server-finalize, Android-upload, incoming-index, presentation, and physical
evidence checkpoints rather than after every small commit.

## Validation

- Documentation-only diff; no production/test source changed.
- Cross-document terminology checks cover existing-user-only M2,
  `INDEX_CACHED`/`CIPHERTEXT_SYNCED`, sender retry wrapping, account scope,
  two-stage acceptance, and deferred email identity choice.
- `git diff --check` must pass before this evidence is committed.
