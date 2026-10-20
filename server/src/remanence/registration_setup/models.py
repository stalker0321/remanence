"""Public pre-account metadata and compact outcomes, never private plaintext."""
import uuid
from datetime import datetime

from sqlalchemy import BigInteger, Boolean, CheckConstraint, DateTime, ForeignKey, Index, LargeBinary, SmallInteger, String, Uuid, text
from sqlalchemy.orm import Mapped, mapped_column

from remanence.db.base import Base


class RegistrationPolicy(Base):
    __tablename__ = "registration_setup_policy"
    __table_args__ = (CheckConstraint("id = 1", name="ck_registration_setup_policy_singleton"),)
    id: Mapped[int] = mapped_column(SmallInteger, primary_key=True)
    required: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)


class RegistrationReservation(Base):
    __tablename__ = "registration_reservations"
    __table_args__ = (
        CheckConstraint("state IN ('RESERVED','ACTIVE','CANCELLED','EXPIRED')", name="ck_registration_reservation_state"),
        CheckConstraint("epoch > 0", name="ck_registration_reservation_epoch"),
        Index("uq_registration_current_subject", "subject", unique=True, postgresql_where=text("is_current")),
    )
    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    account_id: Mapped[uuid.UUID] = mapped_column(Uuid, unique=True, nullable=False)
    subject: Mapped[str] = mapped_column(String(255), nullable=False)
    email: Mapped[str] = mapped_column(String(320), nullable=False)
    is_current: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    state: Mapped[str] = mapped_column(String(12), nullable=False, default="RESERVED")
    epoch: Mapped[int] = mapped_column(BigInteger, nullable=False, default=1)
    setup_session_id: Mapped[uuid.UUID] = mapped_column(Uuid, nullable=False)
    setup_token_hash: Mapped[bytes | None] = mapped_column(LargeBinary(32), unique=True)
    setup_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    terminal_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    restart_authorized: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    restarted_into: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("registration_reservations.id", ondelete="SET NULL"))
    accepted_operation_id: Mapped[uuid.UUID | None] = mapped_column(Uuid)
    accepted_bundle_id: Mapped[uuid.UUID | None] = mapped_column(Uuid)
    accepted_credential_id: Mapped[str | None] = mapped_column(String(1366))
    accepted_handle: Mapped[str | None] = mapped_column(String(30))
    accepted_grant_hash: Mapped[bytes | None] = mapped_column(LargeBinary(32))
    activated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    receipt_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class RegistrationCredential(Base):
    __tablename__ = "registration_setup_credentials"
    id: Mapped[str] = mapped_column(String(1366), primary_key=True)
    reservation_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("registration_reservations.id", ondelete="CASCADE"), nullable=False, index=True)
    public_key: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    counter: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class RegistrationChallenge(Base):
    __tablename__ = "registration_setup_challenges"
    __table_args__ = (CheckConstraint("purpose IN ('REGISTER','SETUP','FINAL')", name="ck_registration_challenge_purpose"),)
    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    reservation_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("registration_reservations.id", ondelete="CASCADE"), nullable=False, index=True)
    epoch: Mapped[int] = mapped_column(BigInteger, nullable=False)
    setup_session_id: Mapped[uuid.UUID] = mapped_column(Uuid, nullable=False)
    purpose: Mapped[str] = mapped_column(String(12), nullable=False)
    credential_id: Mapped[str | None] = mapped_column(String(1366))
    challenge: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    secret_hash: Mapped[bytes | None] = mapped_column(LargeBinary(32), unique=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
