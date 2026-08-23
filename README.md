# Postcard Memory Capsules

## Product principles

- Send a memory, not just a postcard
- Нет открытки — нет воспоминания
- Делиться реже, но осмысленнее

Application code must not be started until the architecture gate is complete.

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
