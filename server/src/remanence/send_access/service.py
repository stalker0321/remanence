"""Caller-owned transactions. Source -> recipient (v1) -> existing access lock.

No lazy account creation during admission, no grant/refund on reads, and no
pending ORM receipt before optional source/locator savepoints have completed.
"""

import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from remanence.capsules.models import Capsule, CapsuleState
from remanence.pending.claim_models import PendingDelivery
from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.policy import SendAccessMode


class SendAccessError(RuntimeError):
    def __init__(self, code: str = "SEND_ACCESS_UNAVAILABLE"):
        self.code = code
        super().__init__("send access failed")


@dataclass(frozen=True)
class Identity:
    capsule_id: uuid.UUID
    account_id: uuid.UUID
    v1_capsule_id: uuid.UUID | None
    pending_delivery_id: uuid.UUID | None
    bucket: str
    recipient_user_id_at_publish: uuid.UUID | None
    classification: str


@dataclass(frozen=True)
class Allocation:
    identity: Identity
    publication_sha256: bytes
    funding: str
    free_slot: int | None
    published_at: datetime


def _identity(source: Capsule | PendingDelivery) -> Identity:
    if isinstance(source, Capsule):
        same = source.sender_user_id == source.recipient_user_id
        return Identity(
            source.id, source.sender_user_id, source.id, None,
            "SELF" if same else "OTHER", source.recipient_user_id,
            "ACCOUNT_EQUAL" if same else "ACCOUNT_OTHER",
        )
    if isinstance(source, PendingDelivery):
        return Identity(
            source.capsule_id, source.sender_user_id, None, source.id,
            "OTHER", None, "UNRESOLVED_AT_PUBLISH",
        )
    raise SendAccessError()


def _statement_hash(source: Capsule | PendingDelivery) -> bytes | None:
    return (
        source.signed_statement_sha256 if isinstance(source, Capsule)
        else source.publish_statement_sha256
    )


def provision(session: Session, account_id: uuid.UUID, *, now: datetime) -> None:
    """Registration/migration seam only. Idempotent, never resets provenance."""
    session.execute(
        insert(AccountSendAccess).values(account_id=account_id, policy_version=1, created_at=now)
        .on_conflict_do_nothing(index_elements=["account_id"])
    )


def _lock_access(session: Session, account_id: uuid.UUID) -> AccountSendAccess:
    row = session.scalar(
        select(AccountSendAccess).where(AccountSendAccess.account_id == account_id)
        .with_for_update().execution_options(populate_existing=True)
    )
    if row is None or row.policy_version != 1:
        raise SendAccessError()
    return row


def validate_replay(session: Session, source: Capsule | PendingDelivery) -> None:
    """Call only AFTER existing full-byte owner/state/request replay guards."""
    identity = _identity(source)
    try:
        receipt = session.scalar(
            select(SendPublication).where(SendPublication.capsule_id == identity.capsule_id)
            .execution_options(populate_existing=True)
        )
        if source.send_accounting_version is None and receipt is None:
            return  # Legacy/OFF; no retrospective spend or manufactured receipt.
        if (
            source.send_accounting_version != 1 or receipt is None
            or receipt.policy_version != 1
            or any(getattr(receipt, key) != value for key, value in vars(identity).items())
            or receipt.publication_sha256 != _statement_hash(source)
            or (isinstance(source, Capsule) and receipt.published_at != source.ready_at)
        ):
            raise SendAccessError()
    except SQLAlchemyError as exc:
        raise SendAccessError() from exc


def admit(
    session: Session, source: Capsule | PendingDelivery, *,
    mode: SendAccessMode, publication_sha256: bytes,
    check_deadline: Callable[[], datetime],
) -> Allocation | None:
    """Choose funding under the sender lock; do not enqueue an ORM receipt."""
    try:
        mode = SendAccessMode(mode)
    except ValueError as exc:
        raise SendAccessError() from exc
    if mode is SendAccessMode.OFF:
        return None
    identity = _identity(source)
    if source.state != "DRAFT" or source.send_accounting_version is not None or len(publication_sha256) != 32:
        raise SendAccessError()
    try:
        if isinstance(source, Capsule):
            collision = session.scalar(select(PendingDelivery.id).where(
                PendingDelivery.capsule_id == identity.capsule_id,
                PendingDelivery.state.in_(("PENDING", "CLAIMED", "CANCELLED", "EXPIRED")),
                PendingDelivery.publish_statement.is_not(None),
            ))
        else:
            collision = session.scalar(select(Capsule.id).where(
                Capsule.id == identity.capsule_id, Capsule.state.in_((CapsuleState.READY, CapsuleState.REVOKED)),
            ))
        if collision is not None or session.get(SendPublication, identity.capsule_id) is not None:
            raise SendAccessError()
        access = _lock_access(session, identity.account_id)
        # This callback samples fresh time AFTER a possible access-lock wait.
        published_at = check_deadline()
        if mode is SendAccessMode.RECORD:
            return Allocation(identity, publication_sha256, "BETA_EXEMPT", None, published_at)
        if access.lifetime_granted_at is not None:
            return Allocation(identity, publication_sha256, "LIFETIME", None, published_at)
        used = set(session.scalars(select(SendPublication.free_slot).where(
            SendPublication.account_id == identity.account_id,
            SendPublication.bucket == identity.bucket,
            SendPublication.funding == "FREE",
        )))
        free_slot = next((slot for slot in (1, 2) if slot not in used), None)
        if free_slot is None:
            raise SendAccessError("SEND_LIMIT_REACHED")
        return Allocation(identity, publication_sha256, "FREE", free_slot, published_at)
    except SQLAlchemyError as exc:
        raise SendAccessError() from exc


def record(
    session: Session, source: Capsule | PendingDelivery, allocation: Allocation | None,
    *, now: datetime,
) -> None:
    """After source/locator persistence, outside any degrading savepoint."""
    if allocation is None:
        return
    expected_state = CapsuleState.READY if isinstance(source, Capsule) else "PENDING"
    if (
        source.state != expected_state or _identity(source) != allocation.identity
        or _statement_hash(source) != allocation.publication_sha256
        or source.send_accounting_version is not None
        or (isinstance(source, Capsule) and source.ready_at != allocation.published_at)
    ):
        raise SendAccessError()
    source.send_accounting_version = 1
    session.add(SendPublication(
        **vars(allocation.identity), publication_sha256=allocation.publication_sha256,
        funding=allocation.funding, free_slot=allocation.free_slot, policy_version=1,
        published_at=allocation.published_at, recorded_at=now,
    ))
    try:
        session.flush()
    except SQLAlchemyError as exc:
        raise SendAccessError() from exc


def grant_lifetime(
    session: Session, *, account_id: uuid.UUID, grant_key: str, origin: str,
    now: datetime | None = None,
) -> AccountSendAccess:
    """Trusted internal seam, no HTTP/payment/promo handler. Caller commits."""
    if (
        not isinstance(grant_key, str) or not grant_key.strip() or len(grant_key) > 160
        or not isinstance(origin, str) or not origin.strip() or len(origin) > 32
    ):
        raise SendAccessError("LIFETIME_GRANT_CONFLICT")
    try:
        row = _lock_access(session, account_id)
        if row.lifetime_granted_at is not None:
            if row.lifetime_grant_key != grant_key or row.lifetime_grant_origin != origin:
                raise SendAccessError("LIFETIME_GRANT_CONFLICT")
            return row
        owner = session.scalar(select(AccountSendAccess.account_id).where(
            AccountSendAccess.lifetime_grant_key == grant_key
        ))
        if owner is not None:
            raise SendAccessError("LIFETIME_GRANT_CONFLICT")
        row.lifetime_granted_at = now if now is not None else datetime.now(timezone.utc)
        row.lifetime_grant_key = grant_key
        row.lifetime_grant_origin = origin
        session.flush()
        return row
    except SQLAlchemyError as exc:
        # Includes the global grant-key race. Never continue a failed transaction.
        raise SendAccessError("LIFETIME_GRANT_CONFLICT") from exc
