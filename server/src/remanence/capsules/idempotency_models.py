"""Scoped capsule-route idempotency records."""

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Index, LargeBinary, SmallInteger
from sqlalchemy import String, UUID, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from remanence.db.base import Base
from remanence.users.models import User as _User


class CapsuleIdempotencyRecord(Base):
    __tablename__ = "capsule_idempotency_records"
    __table_args__ = (
        CheckConstraint(
            "method IN ('POST', 'PUT', 'PATCH', 'DELETE') AND method = upper(method)",
            name="ck_capsule_idempotency_records_method_uppercase",
        ),
        CheckConstraint(
            "octet_length(request_sha256) = 32",
            name="ck_capsule_idempotency_records_request_sha256_32",
        ),
        CheckConstraint(
            "response_status BETWEEN 200 AND 599",
            name="ck_capsule_idempotency_records_response_status_range",
        ),
        CheckConstraint(
            "expires_at > created_at",
            name="ck_capsule_idempotency_records_expiry_order",
        ),
        Index("ix_capsule_idempotency_records_expires_at", "expires_at"),
    )

    owner_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "users.id",
            name="fk_capsule_idempotency_records_owner_user_id_users",
            ondelete="CASCADE",
        ),
        primary_key=True,
        nullable=False,
    )
    method: Mapped[str] = mapped_column(String(8), primary_key=True, nullable=False)
    normalized_route: Mapped[str] = mapped_column(String(512), primary_key=True, nullable=False)
    idempotency_key: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        nullable=False,
    )
    request_sha256: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    response_status: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    response_json: Mapped[dict] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
