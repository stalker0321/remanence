"""Provider identity is independent of encryption identity and passkey provider."""
import uuid
from datetime import datetime
from sqlalchemy import DateTime, ForeignKey, LargeBinary, String, Uuid
from sqlalchemy.orm import Mapped, mapped_column
from remanence.db.base import Base


class GoogleIdentity(Base):
    __tablename__ = "google_identities"
    subject: Mapped[str] = mapped_column(String(255), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"), unique=True, nullable=False)


class GoogleFlow(Base):
    __tablename__ = "google_flows"
    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    purpose: Mapped[str] = mapped_column(String(16), nullable=False)
    secret_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    subject: Mapped[str | None] = mapped_column(String(255))
    email: Mapped[str | None] = mapped_column(String(320))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
