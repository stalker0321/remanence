"""Auth credentials table model."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, UUID, func
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
