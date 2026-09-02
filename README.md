# Remanence

## Product principles

- Send a memory, not just a postcard
- Нет открытки — нет воспоминания
- Делиться реже, но осмысленнее

## Current development status / Quick verification

Architecture gate passed. The current architecture checkpoint is ADR-012:
new capsules use explicit FRONT_ONLY recognition identity while legacy
two-sided v1 capsules remain strict/readable. This is a documentation contract
only; the code at `f721d1a` does not claim FRONT_ONLY implementation. Toolchain,
Compose, and optional device steps are in [`docs/development.md`](docs/development.md).

The bounded next contract is the staged migration in
[`docs/implementation-plan.md`](docs/implementation-plan.md), beginning with
typed mode/version seams and dual readers. It preserves outer protocol/AAD and
blob cardinality v1, keeps the local index owner-scoped, and does not prescribe
a Room migration before proving Room v7 insufficient.

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
