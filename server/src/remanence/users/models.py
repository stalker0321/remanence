"""Users table model."""

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, FetchedValue, String, UUID, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from remanence.db.base import Base


class User(Base):
    __tablename__ = "users"
    __table_args__ = (
        UniqueConstraint("email_normalized", name="uq_users_email_normalized"),
        UniqueConstraint("handle_normalized", name="uq_users_handle_normalized"),
        # T185 receive-only accounts defer the immutable handle until first
        # send: both handle columns are NULL together, or both set together.
        # Partial NULLs are rejected. Multiple NULLs never conflict under the
        # unique constraint (PostgreSQL NULL semantics).
        CheckConstraint(
            "((handle_normalized IS NULL AND handle_display IS NULL) OR "
            "(handle_normalized IS NOT NULL AND handle_display IS NOT NULL))",
            name="ck_users_handle_paired_null",
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
        nullable=False,
    )
    email_normalized: Mapped[str] = mapped_column(String(320), nullable=False)
    # Nullable since T185 M2 (receive-only accounts). Existing rows keep
    # their handles; new sends still require a handle (server + client).
    handle_normalized: Mapped[str | None] = mapped_column(String(30), nullable=True)
    handle_display: Mapped[str | None] = mapped_column(String(30), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        server_onupdate=FetchedValue(),
    )
    disabled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
