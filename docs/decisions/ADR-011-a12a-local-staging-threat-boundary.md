# ADR-011: A12a local index staging threat boundary

Status: Accepted for M2-A12a

Date: 2026-08-29

## Context

A12a stages one account/capsule-bound encrypted sender index bundle in
application-private storage. The implementation must reject traversal,
pre-existing symlinks and non-regular paths, serialize same-process work for a
capsule, survive partial writes and process death, and never replace or delete
an ambiguous durable winner.

## Decision

The M2 threat boundary trusts application-private storage against hostile
pathname mutation by another process or component running under the same UID.
That actor already controls the application security boundary and is not a
separate storage attacker for this milestone.

Within that boundary, A12a uses the existing bounded striped lock, strict
owner-root/no-follow path validation, and an exclusive `CREATE_NEW` fresh-part
operation that returns an already-open write capability. It publishes with
same-directory no-replace linking, verifies bounded contents before semantic
replay, and cleans only the exact invocation-owned part where ownership remains
detectable. Ambiguous link, read, force, cancellation, or cleanup outcomes
fail closed or preserve an orphan; they never justify deleting a possible
winner. No public or internal staging API accepts an arbitrary path for
opening a write stream.

Descriptor/native inode machinery is rejected for this checkpoint. Pure Java
pathname checks cannot make every check-to-link or check-to-unlink sequence
atomic against a hostile same-UID swap, while the attempted Android native
variant added substantial platform-specific error/descriptor lifecycle
surface without closing all such pathname races. A stronger hostile-local
storage guarantee requires a separately reviewed native descriptor-relative
design, including its Android API/SELinux/filesystem support and exact
descriptor-bound link/unlink semantics.

## Consequences

A12a claims crash/partial-write, corruption, traversal, symlink, accidental
same-process concurrency, and detectable ambiguous-publication/cleanup safety
only within the stated app-private-storage boundary. It does not claim to
protect against a compromised same-UID process or component that replaces a
pathname between independently checked operations. Such a threat is outside
this milestone's security claim and must not be inferred from the tests.

The effective implementation remains the e94af89 Java filesystem seam and its
exclusive already-open part capability. Any future stronger local-hostile
guarantee is a new security/platform decision, not an implicit A12a refactor.
