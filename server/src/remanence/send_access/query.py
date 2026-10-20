"""Owner quota advice from one READ COMMITTED statement, never a reservation."""

import uuid
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from remanence.send_access.models import AccountSendAccess, SendPublication
from remanence.send_access.policy import SendAccessMode
from remanence.send_access.service import SendAccessError


def read_access(session: Session, *, account_id: uuid.UUID, mode: SendAccessMode) -> dict:
    if mode not in (SendAccessMode.RECORD, SendAccessMode.ENFORCE):
        raise SendAccessError()
    access, receipts = AccountSendAccess.__table__, SendPublication.__table__

    def used(bucket):
        return select(func.count()).select_from(receipts).where(
            receipts.c.account_id == access.c.account_id,
            receipts.c.funding == "FREE", receipts.c.bucket == bucket,
        ).correlate(access).scalar_subquery()

    # Scalar projections bypass dirty/stale identity-map entities. Both counts
    # and the lifetime trio are read by this single statement's MVCC snapshot.
    statement = select(
        access.c.policy_version, access.c.lifetime_granted_at,
        access.c.lifetime_grant_key, access.c.lifetime_grant_origin,
        used("SELF").label("self_used"), used("OTHER").label("other_used"),
    ).where(access.c.account_id == account_id)
    try:
        with session.no_autoflush:
            row = session.execute(statement).mappings().one_or_none()
    except SQLAlchemyError as exc:
        raise SendAccessError() from exc
    if row is None or type(row["policy_version"]) is not int or row["policy_version"] != 1:
        raise SendAccessError()
    at, key, origin = (row[name] for name in (
        "lifetime_granted_at", "lifetime_grant_key", "lifetime_grant_origin",
    ))
    lifetime = at is not None
    if lifetime:
        if (not isinstance(at, datetime) or at.tzinfo is None or at.utcoffset() is None
                or not isinstance(key, str) or not key.strip() or len(key) > 160
                or not isinstance(origin, str) or not origin.strip() or len(origin) > 32):
            raise SendAccessError()
    elif key is not None or origin is not None:
        raise SendAccessError()
    free = {}
    for bucket in ("self", "other"):
        count = row[f"{bucket}_used"]
        if type(count) is not int or not 0 <= count <= 2:
            raise SendAccessError()
        free[bucket] = {"limit": 2, "used": count, "remaining": 2 - count}
    return {
        "policy_version": 1, "mode": mode.value, "lifetime": lifetime, "free": free,
        "can_publish": {bucket: mode is SendAccessMode.RECORD or lifetime or values["remaining"] > 0
                        for bucket, values in free.items()},
    }
