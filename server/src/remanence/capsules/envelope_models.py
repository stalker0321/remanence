"""Recipient capsule envelope table."""

import uuid
from datetime import datetime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    LargeBinary,
    UUID,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column

from remanence.capsules.models import Capsule as _Capsule
from remanence.db.base import Base
from remanence.users.key_models import UserKeyBundle as _UserKeyBundle
from remanence.users.models import User as _User


class CapsuleEnvelope(Base):
    __tablename__ = "capsule_envelopes"
    __table_args__ = (
        CheckConstraint(
            "ciphertext_size > 0 AND ciphertext_size <= 16384",
            name="ck_capsule_envelopes_ciphertext_size_bounds",
        ),
        CheckConstraint(
            "octet_length(ciphertext) = ciphertext_size",
            name="ck_capsule_envelopes_ciphertext_size_matches",
        ),
        CheckConstraint(
            "octet_length(ciphertext_sha256) = 32",
            name="ck_capsule_envelopes_ciphertext_sha256_32",
        ),
    )

    capsule_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "capsules.id",
            name="fk_capsule_envelopes_capsule_id_capsules",
            ondelete="CASCADE",
        ),
        primary_key=True,
        nullable=False,
    )
    recipient_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "users.id",
            name="fk_capsule_envelopes_recipient_user_id_users",
            ondelete="RESTRICT",
        ),
        nullable=False,
    )
    recipient_key_bundle_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "user_key_bundles.id",
            name="fk_capsule_envelopes_recipient_key_bundle_id_user_key_bundles",
            ondelete="RESTRICT",
        ),
        nullable=False,
    )
    ciphertext: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    ciphertext_size: Mapped[int] = mapped_column(Integer, nullable=False)
    ciphertext_sha256: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
