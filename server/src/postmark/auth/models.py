"""Auth credential and session tables."""

import uuid
from datetime import datetime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    LargeBinary,
    String,
    UUID,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column

from postmark.db.base import Base
from postmark.users.models import User as _User


class AuthCredential(Base):
    __tablename__ = "auth_credentials"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_auth_credentials_user_id_users", ondelete="CASCADE"),
        primary_key=True,
        nullable=False,
    )
    password_hash: Mapped[str] = mapped_column(String(512), nullable=False)
    password_changed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )


class AuthSession(Base):
    __tablename__ = "auth_sessions"
    __table_args__ = (
        UniqueConstraint("access_token_hash", name="uq_auth_sessions_access_token_hash"),
        UniqueConstraint("refresh_token_hash", name="uq_auth_sessions_refresh_token_hash"),
        UniqueConstraint("parent_session_id", name="uq_auth_sessions_parent_session_id"),
        CheckConstraint(
            "octet_length(access_token_hash) = 32",
            name="ck_auth_sessions_access_token_hash_32",
        ),
        CheckConstraint(
            "octet_length(refresh_token_hash) = 32",
            name="ck_auth_sessions_refresh_token_hash_32",
        ),
        CheckConstraint("refresh_expires_at > access_expires_at", name="ck_auth_sessions_expiry_order"),
        Index("ix_auth_sessions_user_id", "user_id"),
        Index("ix_auth_sessions_lineage_id", "lineage_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
        nullable=False,
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_auth_sessions_user_id_users", ondelete="CASCADE"),
        nullable=False,
    )
    lineage_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    parent_session_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "auth_sessions.id",
            name="fk_auth_sessions_parent_session_id_auth_sessions",
            ondelete="RESTRICT",
        ),
        nullable=True,
    )
    access_token_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    refresh_token_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    access_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    refresh_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    rotated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
