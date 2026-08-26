# Remanence

## Product principles

- Send a memory, not just a postcard
- Нет открытки — нет воспоминания
- Делиться реже, но осмысленнее

## Current development status / Quick verification

Architecture gate passed. M0 foundation gate passed; M1 single-account mechanism work is in progress. Toolchain, Compose, and optional device steps are in [`docs/development.md`](docs/development.md).

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

Current status is recorded in [`.agent/current-milestone.md`](.agent/current-milestone.md). Codex owns architecture and review; Grok receives implementation tasks only after the gate passes.
