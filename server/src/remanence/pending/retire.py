"""Locator retirement: owner-only, idempotent, background (T185 M3).

Owner override (binding): content display NEVER waits for this. After
actual presentation success the client enqueues a durable account-fenced
request (opaque IDs only) and retries in the background; backend failure
never affects display or entitlement. No sender notification/analytics.

Completion and locator retirement commit in ONE transaction; envelope,
claim, and incoming availability are never deleted or rotated here.
A lost response or crash before pixels is recoverable: owner sync
returns the same envelope + completion, and a fresh scan resumes.
"""

from __future__ import annotations

import hashlib
import uuid
from collections.abc import Callable
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from remanence.capsules.models import Capsule
from remanence.pending.claim_models import (
    LocatorOpenCompletion,
    PendingClaim,
    PendingDelivery,
    RetireOutbox,
)
from remanence.pending.locator_store import LocatorStoreError, retire as retire_locator


class RetireError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__("locator retirement failed")
        self.code = code


def owner_publication_hash(
    session: Session, *, capsule_id: uuid.UUID, account_user_id: uuid.UUID
) -> bytes | None:
    """Return the expected publication hash iff the account owns the
    capsule via v1 receipt or a v2 claim. Otherwise None (generic denial,
    no existence oracle)."""
    capsule = session.get(Capsule, capsule_id)
    if capsule is not None and capsule.recipient_user_id == account_user_id:
        if capsule.signed_statement_sha256 is None:
            return None
        return bytes(capsule.signed_statement_sha256)
    claim_row = (
        session.execute(
            select(PendingClaim, PendingDelivery)
            .join(PendingDelivery, PendingClaim.delivery_id == PendingDelivery.id)
            .where(
                PendingDelivery.capsule_id == capsule_id,
                PendingClaim.account_user_id == account_user_id,
            )
        )
        .first()
    )
    if claim_row is not None:
        _, delivery = claim_row
        if delivery.publish_statement_sha256 is None:
            return None
        return bytes(delivery.publish_statement_sha256)
    return None


def _admission_present(session: Session, *, capsule_id: uuid.UUID, account_user_id: uuid.UUID) -> bool:
    """Read admission from the DB — the frozen R15 DTO carries NO client
    readiness field, and the endpoint must not silently require new client
    fields. v1: the existing durable first-open admission row. Pending:
    the durable CLAIM row (claim fixes ownership; presentation readiness
    itself is client-attested per contract — a dishonest owner can only
    retire its own code early, never gain or transfer access)."""
    capsule = session.get(Capsule, capsule_id)
    if capsule is not None and capsule.recipient_user_id == account_user_id:
        return capsule.first_opened_at is not None
    delivery = session.scalar(
        select(PendingDelivery).where(PendingDelivery.capsule_id == capsule_id)
    )
    if delivery is None:
        return False
    claim_row = session.get(PendingClaim, delivery.delivery_target_id)
    return claim_row is not None and claim_row.account_user_id == account_user_id


def complete_locator_open(
    session: Session,
    *,
    capsule_id: uuid.UUID,
    account_user_id: uuid.UUID,
    publication_sha256: bytes,
    locator_id: uuid.UUID | None,
    now: datetime,
) -> LocatorOpenCompletion:
    """Atomic completion + ACTIVE->RETIRED_OPEN. Frozen R15 DTO in/out;
    admission is read from the DB (no client readiness fields).

    Replay by the owner with the same publication hash returns the same
    durable completion; a changed hash conflicts. Concurrent replays are
    serialized on the locator row lock with a savepoint + recheck, so no
    flush PK error escapes."""
    from remanence.pending.models import GiftLocator

    if len(publication_sha256) != 32:
        raise RetireError("GIFT_UNAVAILABLE")
    if locator_id is None:
        # Completion exists only to operate the locator; legacy
        # no-locator capsules keep their existing admission behavior.
        raise RetireError("GIFT_UNAVAILABLE")
    # Lock first: the locator row serializes concurrent completions.
    locator = session.get(GiftLocator, locator_id, with_for_update=True)
    if locator is None or locator.capsule_id != capsule_id:
        raise RetireError("GIFT_UNAVAILABLE")
    expected = owner_publication_hash(session, capsule_id=capsule_id, account_user_id=account_user_id)
    if expected is None or expected != bytes(publication_sha256):
        raise RetireError("GIFT_UNAVAILABLE")
    if not _admission_present(session, capsule_id=capsule_id, account_user_id=account_user_id):
        raise RetireError("GIFT_UNAVAILABLE")
    existing = session.get(LocatorOpenCompletion, capsule_id)
    if existing is not None:
        if bytes(existing.publication_sha256) != bytes(publication_sha256):
            raise RetireError("CLAIM_CONFLICT")
        return existing
    completion = LocatorOpenCompletion(
        capsule_id=capsule_id,
        publication_sha256=bytes(publication_sha256),
        completed_at=now,
    )
    session.add(completion)
    try:
        # Savepoint + recheck: a concurrent winner's insert raises here;
        # re-read under our row lock and return its durable result.
        with session.begin_nested():
            session.flush()
    except IntegrityError:
        winner = session.get(LocatorOpenCompletion, capsule_id)
        if winner is not None and bytes(winner.publication_sha256) == bytes(publication_sha256):
            return winner
        raise RetireError("CLAIM_CONFLICT")
    try:
        retire_locator(session, locator_id=locator.id, terminal=False, now=now)
    except LocatorStoreError as exc:
        raise RetireError("GIFT_UNAVAILABLE") from exc
    session.flush()
    return completion


def get_completion(
    session: Session, *, capsule_id: uuid.UUID, account_user_id: uuid.UUID
) -> LocatorOpenCompletion | None:
    """Durable completion for retry/restore without code/OTP. Owner-only."""
    expected_owner = owner_publication_hash(
        session, capsule_id=capsule_id, account_user_id=account_user_id
    )
    if expected_owner is None:
        return None
    return session.get(LocatorOpenCompletion, capsule_id)


def enqueue_retire(
    session: Session,
    *,
    account_user_id: uuid.UUID,
    capsule_id: uuid.UUID,
    publication_sha256: bytes,
) -> RetireOutbox:
    """Enqueue background retirement. Opaque IDs only. Idempotent: the
    same (account, capsule, publication) triple returns the same row."""
    if len(publication_sha256) != 32:
        raise RetireError("GIFT_UNAVAILABLE")
    row = RetireOutbox(
        id=uuid.uuid4(),
        account_user_id=account_user_id,
        capsule_id=capsule_id,
        publication_sha256=bytes(publication_sha256),
        state="QUEUED",
        attempts=0,
    )
    try:
        # add() AND flush() inside the savepoint: SQLAlchemy autoflush
        # would otherwise fire the INSERT before the savepoint, leaving a
        # duplicate's IntegrityError outside it and breaking the whole TX.
        with session.begin_nested():
            session.add(row)
            session.flush()
    except IntegrityError:
        row = session.scalar(
            select(RetireOutbox).where(
                RetireOutbox.account_user_id == account_user_id,
                RetireOutbox.capsule_id == capsule_id,
                RetireOutbox.publication_sha256 == bytes(publication_sha256),
            )
        )
        assert row is not None
    return row


def drain_retire_outbox(
    session: Session,
    *,
    complete: Callable[[Session, RetireOutbox], None],
    limit: int = 100,
) -> tuple[int, int]:
    """Background worker pass: run complete() per QUEUED row (PG uses
    SKIP LOCKED so parallel workers never double-process). Success ->
    DONE; failure -> attempts++ and stays QUEUED for durable retry.
    Returns (done, remaining). Never gates content display."""
    query = select(RetireOutbox).where(RetireOutbox.state == "QUEUED").limit(limit)
    if session.bind is not None and session.bind.dialect.name == "postgresql":
        query = query.with_for_update(skip_locked=True)
    rows = session.scalars(query).all()
    done = 0
    for row in rows:
        try:
            complete(session, row)
        except Exception:
            row.attempts += 1
            session.flush()
            continue
        row.state = "DONE"
        session.flush()
        done += 1
    session.flush()
    remaining = (
        session.scalar(
            select(RetireOutbox.id).where(RetireOutbox.state == "QUEUED").limit(1)
        )
        is not None
    )
    return done, (1 if remaining else 0)


def queue_digest(*parts: bytes) -> str:
    """Log-safe opaque queue marker (hashes only, for worker diagnostics)."""
    return hashlib.sha256(b"|".join(parts)).hexdigest()[:16]
