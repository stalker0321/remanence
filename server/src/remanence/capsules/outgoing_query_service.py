"""Sender-only metadata, never content/admission. T368-history-v1.

Read-only SQL projections deliberately exclude envelope/vault/statement/address
payloads. Keyset order uses immutable creation coordinates across both ledgers.
"""
from __future__ import annotations

import base64
import hmac
import json
import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import UUID, String, and_, cast, exists, func, literal, null, or_, select, union_all
from sqlalchemy.orm import Session

from remanence.capsules.models import Capsule, CapsuleState
from remanence.pending.claim_models import PendingDelivery
from remanence.pending.locator import is_canonical
from remanence.pending.locator_store import code_hmac
from remanence.pending.models import GiftLocator
from remanence.users.models import User


class HistoryQueryError(ValueError):
    pass


def _utc(value: datetime) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise ValueError("invalid stored history timestamp")
    return value.astimezone(timezone.utc)


def _iso(value: datetime | None) -> str | None:
    return None if value is None else _utc(value).isoformat()


def _cursor(owner: uuid.UUID, created: datetime, protocol: int, capsule: uuid.UUID) -> str:
    data = json.dumps([1, str(owner), _iso(created), protocol, str(capsule)], separators=(",", ":"))
    return base64.urlsafe_b64encode(data.encode("ascii")).decode("ascii").rstrip("=")


def _decode_cursor(value: str, owner: uuid.UUID) -> tuple[datetime, int, uuid.UUID]:
    try:
        if not 1 <= len(value) <= 1024 or not value.isascii():
            raise ValueError()
        data = json.loads(base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True))
        if not isinstance(data, list) or len(data) != 5 or type(data[0]) is not int or data[0] != 1:
            raise ValueError()
        if data[1] != str(owner) or not isinstance(data[2], str) or not isinstance(data[4], str) or type(data[3]) is not int or data[3] not in (1, 2):
            raise ValueError()
        created, protocol, capsule = _utc(datetime.fromisoformat(data[2])), data[3], uuid.UUID(data[4])
        if _cursor(owner, created, protocol, capsule) != value:
            raise ValueError()
        return created, protocol, capsule
    except (ValueError, TypeError, UnicodeError, OverflowError) as exc:
        raise HistoryQueryError("invalid cursor") from exc


def _projection(owner: uuid.UUID):
    # A v1/v2 capsule-id collision cannot attach another ledger's public code
    # or destination hint to this row. Pending owns its locator by explicit ID.
    collision = exists(select(PendingDelivery.id).where(PendingDelivery.capsule_id == Capsule.id))
    v1 = select(
        Capsule.id.label("capsule_id"), literal(1).label("protocol_version"),
        cast(null(), UUID).label("delivery_id"), cast(Capsule.state, String).label("state"),
        Capsule.created_at, Capsule.ready_at.label("published_at"),
        Capsule.draft_expires_at.label("expires_at"), Capsule.first_opened_at, Capsule.revoked_at,
        Capsule.recipient_user_id.label("recipient_id"), User.handle_normalized.label("handle"),
        GiftLocator.state.label("locator_state"), GiftLocator.code_ciphertext,
        GiftLocator.code_hmac, GiftLocator.hint_mask,
    ).join(User, User.id == Capsule.recipient_user_id).outerjoin(
        GiftLocator, and_(GiftLocator.capsule_id == Capsule.id, ~collision),
    ).where(Capsule.sender_user_id == owner)
    pending = select(
        PendingDelivery.capsule_id, literal(2).label("protocol_version"),
        PendingDelivery.id.label("delivery_id"), PendingDelivery.state,
        PendingDelivery.created_at, cast(null(), Capsule.ready_at.type).label("published_at"),
        PendingDelivery.claim_expires_at.label("expires_at"),
        cast(null(), Capsule.first_opened_at.type).label("first_opened_at"),
        cast(null(), Capsule.revoked_at.type).label("revoked_at"),
        cast(null(), UUID).label("recipient_id"), cast(null(), String).label("handle"),
        GiftLocator.state.label("locator_state"), GiftLocator.code_ciphertext,
        GiftLocator.code_hmac, GiftLocator.hint_mask,
    ).outerjoin(GiftLocator, and_(
        GiftLocator.id == PendingDelivery.locator_id,
        GiftLocator.capsule_id == PendingDelivery.capsule_id,
    )).where(PendingDelivery.sender_user_id == owner)
    return union_all(v1, pending).subquery()


def _locator(row, *, mode: str, custody: dict | None) -> dict:
    state = row["locator_state"]
    if state in ("RETIRED_OPEN", "RETIRED_TERMINAL"):
        return {"status": state, "code": None}
    unavailable = {"status": "UNAVAILABLE", "code": None}
    if state != "ACTIVE" or row["state"] not in ("READY", "PENDING", "CLAIMED"):
        return unavailable
    # Preserve the current finalize code-key custody boundary, not a new key
    # mechanism. Missing/broken custody must never fabricate or allocate a code.
    if mode not in ("dev", "test") or not isinstance(custody, dict):
        return unavailable
    key, tag_key = custody.get("locator_code_key"), custody.get("hmac_key")
    if not isinstance(key, bytes) or len(key) != 32 or not isinstance(tag_key, bytes) or len(tag_key) < 32:
        return unavailable
    try:
        from remanence.pending.vault import aead_from_raw_key
        code = aead_from_raw_key(key).decrypt(bytes(row["code_ciphertext"]), b"pending-locator-code-v1").decode("ascii")
        if not is_canonical(code) or not hmac.compare_digest(code_hmac(code, tag_key), bytes(row["code_hmac"])):
            return unavailable
        return {"status": "ACTIVE", "code": code}
    except Exception:
        # No key, ciphertext, decoded value or underlying exception is logged.
        return unavailable


class OutgoingQueryService:
    def __init__(self, session: Session) -> None:
        self.session = session

    def outgoing(self, *, owner: uuid.UUID, now: datetime, limit: int = 20,
                 cursor: str | None = None, mode: str = "prod",
                 custody: dict | None = None, pending_cancel_enabled: bool = False) -> dict:
        if type(limit) is not int or not 1 <= limit <= 50:
            raise HistoryQueryError("invalid limit")
        now = _utc(now)
        last = _decode_cursor(cursor, owner) if cursor is not None else None
        feed = _projection(owner)
        query = select(feed)
        if last is not None:
            created, protocol, capsule = last
            query = query.where(or_(
                feed.c.created_at < created,
                and_(feed.c.created_at == created, feed.c.protocol_version < protocol),
                and_(feed.c.created_at == created, feed.c.protocol_version == protocol, feed.c.capsule_id < capsule),
            ))
        query = query.order_by(feed.c.created_at.desc(), feed.c.protocol_version.desc(), feed.c.capsule_id.desc()).limit(limit + 1)
        with self.session.no_autoflush:
            rows = self.session.execute(query).mappings().all()
        has_more = len(rows) > limit
        rows = rows[:limit]
        items = []
        for row in rows:
            v1, state = row["protocol_version"] == 1, row["state"]
            opened = v1 and state == "READY" and row["first_opened_at"] is not None
            deadline = _utc(row["published_at"]) + timedelta(hours=24) if v1 and row["published_at"] is not None else None
            cancel = None
            if v1 and state == "READY" and not opened and deadline is not None and now <= deadline:
                cancel = "REVOKE_V1"
            elif not v1 and state in ("DRAFT", "PENDING") and pending_cancel_enabled:
                cancel = "CANCEL_PENDING_V2"
            items.append({
                "capsule_id": str(row["capsule_id"]), "protocol_version": row["protocol_version"],
                "delivery_id": str(row["delivery_id"]) if row["delivery_id"] is not None else None,
                "status": "OPENED" if opened else state,
                "created_at": _iso(row["created_at"]), "published_at": _iso(row["published_at"]),
                "expires_at": _iso(row["expires_at"]) if not v1 or state in ("DRAFT", "ABORTED") else None,
                "first_opened_at": _iso(row["first_opened_at"]), "revoked_at": _iso(row["revoked_at"]),
                "recipient": {
                    "user_id": str(row["recipient_id"]) if v1 else None,
                    "handle": row["handle"], "hint": row["hint_mask"],
                    "is_self": row["recipient_id"] == owner if v1 else None,
                },
                "locator": _locator(row, mode=mode, custody=custody),
                "cancel_action": cancel, "cancel_deadline": _iso(deadline),
            })
        next_cursor = _cursor(owner, rows[-1]["created_at"], rows[-1]["protocol_version"], rows[-1]["capsule_id"]) if has_more else None
        return {"items": items, "has_more": has_more, "next_cursor": next_cursor}

    def recent(self, *, owner: uuid.UUID, limit: int = 8) -> dict:
        if type(limit) is not int or not 1 <= limit <= 20:
            raise HistoryQueryError("invalid limit")
        last = func.max(Capsule.ready_at).label("last_sent_at")
        query = select(User.id, User.handle_normalized, last).join(
            Capsule, Capsule.recipient_user_id == User.id,
        ).where(
            Capsule.sender_user_id == owner, Capsule.recipient_user_id != owner,
            Capsule.state.in_((CapsuleState.READY, CapsuleState.REVOKED)),
            Capsule.ready_at.is_not(None), User.disabled_at.is_(None),
            User.handle_normalized.is_not(None),
        ).group_by(User.id, User.handle_normalized).order_by(last.desc(), User.id.desc()).limit(limit)
        with self.session.no_autoflush:
            rows = self.session.execute(query).all()
        return {"items": [{"user_id": str(row[0]), "handle": row[1], "last_sent_at": _iso(row[2])} for row in rows]}
