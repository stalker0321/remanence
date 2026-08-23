# ADR-003: Atomic publication with blob-granular retry

Status: Accepted for protocol v1

## Context

Capsules contain several bounded photo ciphertexts plus manifests. Upload must survive interruption and never expose a half-published capsule, but multipart/cloud-specific infrastructure would add scope.

## Decision

- Client creates a server `DRAFT` declaring immutable blob IDs, sizes, and hashes.
- Each whole encrypted blob uploads idempotently through a storage abstraction.
- Finalize verifies every stored artifact, envelope, current key IDs, and signed statement in one database transaction before `READY` routing.
- Retry resumes at the next missing blob; no byte-range multipart upload in v1.
- Development uses a safe local filesystem adapter; production may use an S3-compatible adapter behind the same `BlobStore` contract.

## Alternatives

- Single giant request: rejected; poor retry behavior and memory/timeout risk.
- S3 presigned multipart from day one: rejected; cloud coupling solves no current bounded-payload problem.
- Publish rows before all blobs arrive: rejected; recipients could observe incomplete/corrupt capsules.

## Consequences

At worst one bounded photo blob is retransmitted. Ready capsules are complete and immutable. Draft garbage collection is required. Large future media would require a versioned multipart/streaming decision.
