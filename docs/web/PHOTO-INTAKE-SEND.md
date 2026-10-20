# Photo intake / Send admission (T364)

F14: a ready A/B/C composition previously left Send enabled while D was being
read/normalized. The baseline browser regression actually admitted and signed
the old three-photo visit before D completed. This document describes the
bounded Create admission fix, not a change to the sender protocol.

## Ownership and timing

`PhotoIntakeActivity` tracks one lease per nonempty picker handler, including
handlers queued by the existing `PhotoIntake`. A lease belongs to its intake
instance, owner, session generation, confirmed-recipient identity and mounted
Create visit. It stores no files, keys, transport or attempt state.

Create acquires the lease synchronously before callbacks/awaits. Both the
render-time Send gate and `handleSend` consult the live lease set. Thus even a
previously-rendered ready callback cannot call `begin` during owned intake.
The photo draft exposes `aria-busy`; no new translation or retry UI is added.

The lease spans the handler's final reconciliation. Changed photo IDs/hashes
or any successful additions invalidate the old freeze **before** lease release,
not merely in the existing image-loading React effect. The existing composer
then loads the current normalized photos; the user explicitly chooses a new
composition before sending.

Completion removes only its own lease. An old recipient, generation, owner or
intake instance cannot change a replacement's notice, URLs, selection or busy
state. Unmount retires that instance's leases. Existing normalizer fencing
still rejects stale bytes; unabortable File reads may continue until settled.

## Failure and cancellation semantics

- Picker cancel / empty selection is a no-op: no lease, invalidation or attempt.
- A wholly refused read/decode, or cancelled intake with unchanged photos,
  releases its lease and preserves an otherwise valid old freeze. Read/decode
  refusal displays the existing photo rejection message. No selection is
  restored if another edit already invalidated it.
- A partially successful selection invalidates the old freeze even if other
  files are refused or the remaining intake is cancelled.
- With two picks queued, settling the first never enables Send while the
  second remains owned and pending.
- No automatic send, second begin, retry, rollback or attempt reset is added.
  Existing invalidation of an already-running Send is unchanged.

## Browser regression

`photoIntakeSend.page.tsx` reuses the real-App send-feedback fixture. Synthetic
JPEGs enter the actual file input, `PhotoIntake`, browser normalizer, composer,
controller, sealer and fetch publisher. File reads are deliberately gated or
rejected; malformed JPEG data exercises actual decoder refusal. The test
observes the original controller `begin` without replacing its behavior.
It also calls the genuine old React click callback to exercise the synchronous
admission boundary independently of disabled DOM dispatch.

`photoIntakeSendCheck.mjs` covers gated D, same-stack stale callback, queued
picks, read/decode failures, two queued failures, existing intake cancellation,
empty picker, recipient replacement, owner replacement and unmount/re-entry.
Pending work must yield zero begin/sign/publication calls. Each visit ends in
one explicit normal send with current photo IDs, freeze slots, owner and
recipient, one sign/draft/finalize and no second begin.

Run on the allocated cloud host under the shared web flock with pinned tools:

```sh
node test/browser/photoIntakeSendCheck.mjs "$PWD" /unique/proof/directory
```

`T364_MODES` optionally selects comma-separated scenarios. Default is all eleven.
The companion `intake-activity.test.ts` covers independent leases, scalar fence
capture, generation/recipient/owner loss and instance retirement.

These are synthetic loopback tests in pinned Chromium, not live-account,
real-provider, physical-camera, native OS-picker or Safari/Firefox evidence.
The explicit intake cancellation test invokes the existing `PhotoIntake.cancel`
API; it does not claim there is a new user-visible cancel button. Owner tests
use synthetic golden capabilities with the real session/draft lifecycle.
No service-worker, backend, Android, crypto or one-attempt code is changed.
