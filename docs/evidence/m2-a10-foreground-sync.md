# M2-A10 foreground incoming-sync evidence

Status: PASS for A10a-c at the reviewed implementation checkpoint.

The A10 boundary is complete only after all three bounded pieces are present:

- A10a keeps the incoming page loop account-scoped, uses Room's durable cursor,
  honors `hasMore`, and returns WorkManager retry at the named page cap.
- A10b obtains the authenticated owner from the existing session/account
  boundary and schedules one `CONNECTED` exponential-backoff
  `enqueueUniqueWork(..., KEEP, ...)` chain with owner-only WorkData.
- A10c connects one Compose `Lifecycle.Event.ON_RESUME` effect to
  `RootViewModel.onAppForegrounded()`. The root publishes only proven Active
  sessions before scheduling; signed-out/recovery/connectivity/invalid-owner
  states enqueue nothing. Logout snapshots the owner and awaits cancellation
  through its canonical account tag before credentials/account teardown.

The lifecycle test targets this narrow Compose effect rather than constructing
the full activity container, which would initialize production Keystore and
identity dependencies. Durable cursor restart behavior remains covered by the
A09/A10a repository and worker tests; A10c adds no second cursor store.

Verification under JDK 17 with `/usr/lib/android-sdk`:

- Focused RootViewModel, lifecycle, KEEP scheduler, and logout/account-switch
  command: 29 tests, 0 failures, 0 errors, 0 skipped.
- `:app:testDebugUnitTest`: 451 tests, 0 failures, 0 errors, 0 skipped.
- `:app:compileDebugKotlin`: passed.
- `git diff --check`: passed.
