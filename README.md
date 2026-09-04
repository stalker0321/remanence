# Remanence

## Product principles

- Send a memory, not just a postcard
- Нет открытки — нет воспоминания
- Делиться реже, но осмысленнее

## Current development status / Quick verification

M2-F0 FRONT-only reset is implemented and reviewed through `88fb80d`
(closure record in [`docs/implementation-plan.md`](docs/implementation-plan.md)
§10a; docs/status closure through `58362f5`). The production contract is
FRONT-only under ADR-012: one explicit front-only manifest format, front-only
local indexes, outgoing/incoming wiring, Create, and Scan with owner-scoped
design-to-0..N classification and no auto-open for N>1 pending the M2-F1
picker. Toolchain, Compose, and optional device steps are in
[`docs/development.md`](docs/development.md).

Existing Test8/Test9 recognition state is disposable development data;
deployment starts with a clean app database and server recognition state.
No compatibility reader or data migration is required. Explicitly still open:
physical-device evidence, camera-quality acceptance, and approximately
300-image dataset validation; the recipient picker remains the later M2-F1
milestone.

Canonical verification:

```sh
./scripts/verify-m0.sh
```

That command does not prove physical-device, camera, or M1/M2 success.

## Engineering package

- [`docs/product.md`](docs/product.md) — product scope and physical-first invariants
- [`docs/architecture.md`](docs/architecture.md) — Android/backend/storage/sync lifecycle
- [`docs/security.md`](docs/security.md) — threat model, keys, E2EE, recovery limits
- [`docs/protocol.md`](docs/protocol.md) — protobuf/REST/database state contracts
- [`docs/recognition.md`](docs/recognition.md) — local ORB/homography scoring pipeline
- [`docs/milestones.md`](docs/milestones.md) — vertical delivery gates
- [`docs/acceptance-criteria.md`](docs/acceptance-criteria.md) — pass/fail evidence
- [`docs/test-strategy.md`](docs/test-strategy.md) — automated, adversarial, and physical validation
- [`docs/implementation-plan.md`](docs/implementation-plan.md) — atomic Grok task queue
- [`docs/decisions/`](docs/decisions/) — accepted architecture decisions

The current recognition identity decision is
[`ADR-012`](docs/decisions/ADR-012-front-only-recognition-identity-contract.md).

Current status is recorded in [`.agent/current-milestone.md`](.agent/current-milestone.md). Codex owns architecture and review; Grok receives implementation tasks only after the gate passes.
