"""Opaque recovery material and durable single-use ceremony state."""
import uuid
from datetime import datetime
from sqlalchemy import DateTime, ForeignKey, LargeBinary, String, Integer, BigInteger, Uuid, JSON, CheckConstraint
from sqlalchemy.orm import Mapped, mapped_column
from remanence.db.base import Base


class RecoveryCredential(Base):
    __tablename__ = "recovery_credentials"
    id: Mapped[str] = mapped_column(String(1366), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), nullable=False, index=True)
    public_key: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    counter: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class RecoveryRecord(Base):
    __tablename__ = "recovery_records"
    __table_args__ = (CheckConstraint("generation = 1", name="ck_recovery_generation_v1"),)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), primary_key=True)
    credential_id: Mapped[str] = mapped_column(String(1366), ForeignKey("recovery_credentials.id"), unique=True, nullable=False)
    bundle_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("user_key_bundles.id"), nullable=False)
    generation: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)


class RecoveryChallenge(Base):
    __tablename__ = "recovery_challenges"
    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("users.id"), nullable=True)
    # Registration/setup are bound to one authenticated session, not just user.
    session_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, nullable=True)
    purpose: Mapped[str] = mapped_column(String(16), nullable=False)
    credential_id: Mapped[str | None] = mapped_column(String(1366), nullable=True)
    challenge: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
