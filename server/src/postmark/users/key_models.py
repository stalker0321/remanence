"""User key bundle table model."""

import uuid
import enum
from datetime import datetime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    LargeBinary,
    SmallInteger,
    String,
    UUID,
    func,
    text,
)
from sqlalchemy import Enum as SaEnum
from sqlalchemy.orm import Mapped, mapped_column

from postmark.db.base import Base
from postmark.users.models import User as _User


class KeyBundleStatus(str, enum.Enum):
    ACTIVE = "ACTIVE"
    RETIRED = "RETIRED"
    REVOKED = "REVOKED"


class UserKeyBundle(Base):
    __tablename__ = "user_key_bundles"
    __table_args__ = (
        CheckConstraint(
            "protocol_version > 0",
            name="ck_user_key_bundles_protocol_version_positive",
        ),
        Index(
            "uq_user_key_bundles_one_active_per_user",
            "user_id",
            unique=True,
            postgresql_where=text("status = 'ACTIVE'"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_user_key_bundles_user_id_users", ondelete="CASCADE"),
        nullable=False,
    )
    encryption_public_keyset: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    signing_public_keyset: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    suite: Mapped[str] = mapped_column(String(80), nullable=False)
    protocol_version: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    status: Mapped[KeyBundleStatus] = mapped_column(
        SaEnum(KeyBundleStatus, name="key_bundle_status"),
        nullable=False,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    retired_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
