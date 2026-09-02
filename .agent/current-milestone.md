# Current milestone

`M2_FRONT_ONLY_RESET`

ADR-012 and the canonical recognition/protocol/milestone contract are the
current documentation checkpoint on branch
`integration/m2-front-only-reset` at `f01c379`. This docs-only checkpoint does
not claim FRONT-only production or test implementation. Test8/Test9 local and
server recognition state is disposable; deployment of the breaking reset uses
a clean app database and clean server recognition state.

The next approved implementation contract is the bounded M2-F0 sequence in
`docs/implementation-plan.md`: front-only schema/domain/index, outgoing,
incoming, Create, and Scan with owner-scoped 0/1/many classification and no
auto-open for N. The recipient picker is the separate future M2-F1 milestone;
duplicate prevention is M2-F2; cancellation is M2-F3. No compatibility reader,
optional BACK production mode, or data migration is required. The P14 physical
hardware blocker remains: CameraX/OpenCV and two-device evidence cannot be
claimed until exercised on hardware.

Supervisor role map: `main:0` is Codex architecture/review; `main:1-3` are
implementation workers. This file records intent only; no tmux session is
started by this commit.
