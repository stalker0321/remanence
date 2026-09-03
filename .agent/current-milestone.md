# Current milestone

`M2_FRONT_ONLY_RESET`

ADR-012 and the canonical recognition/protocol/milestone contract are
implemented and independently reviewed through `88fb80d` on branch
`integration/m2-front-only-reset` (docs closure checkpoint `3da0cb2`;
closure record in `docs/implementation-plan.md` §10a). The `f01c379`
docs-only architecture checkpoint is historical record only: M2-F0-01..07
FRONT-only production and test implementation is complete and reviewed.
Test8/Test9 local and server recognition state is disposable; deployment of
the breaking reset uses a clean app database and clean server recognition
state.

The bounded M2-F0 sequence in `docs/implementation-plan.md` stands
implemented as: front-only schema/domain/index, outgoing, incoming, Create,
and Scan with owner-scoped 0/1/many classification and no auto-open for
N>1 pending the M2-F1 picker. The recipient picker is the separate future
M2-F1 milestone; duplicate prevention is M2-F2; cancellation is M2-F3. No
compatibility reader, optional BACK production mode, or data migration was
or is required. Still explicitly open as the next validation checkpoints:
physical-device evidence, camera-quality acceptance, and the dataset
checkpoint (acceptance-criteria M3); none is marked passed here.

Supervisor role map: `main:0` is Codex architecture/review; `main:1-3` are
implementation workers. This file records intent only; no tmux session is
started by this commit.
