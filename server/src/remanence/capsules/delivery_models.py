"""Recipient-scoped capsule ciphertext delivery state table."""

import enum
import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, UUID, func
from sqlalchemy import Enum as SaEnum
from sqlalchemy.orm import Mapped, mapped_column

from remanence.capsules.models import Capsule as _Capsule
from remanence.db.base import Base
from remanence.users.models import User as _User


class RecipientDeliveryStatus(str, enum.Enum):
    AVAILABLE = "AVAILABLE"
    CIPHERTEXT_SYNCED = "CIPHERTEXT_SYNCED"


class RecipientDeliveryState(Base):
    __tablename__ = "recipient_delivery_state"
    __table_args__ = (
        CheckConstraint(
            "((state = 'AVAILABLE' AND ciphertext_synced_at IS NULL) OR "
            "(state = 'CIPHERTEXT_SYNCED' AND ciphertext_synced_at IS NOT NULL))",
            name="ck_recipient_delivery_state_state_timestamp_coherence",
        ),
    )

    recipient_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "users.id",
            name="fk_recipient_delivery_state_recipient_user_id_users",
            ondelete="RESTRICT",
        ),
        primary_key=True,
        nullable=False,
    )
    capsule_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "capsules.id",
            name="fk_recipient_delivery_state_capsule_id_capsules",
            ondelete="CASCADE",
        ),
        primary_key=True,
        nullable=False,
    )
    state: Mapped[RecipientDeliveryStatus] = mapped_column(
        SaEnum(RecipientDeliveryStatus, name="recipient_delivery_status", native_enum=True),
        nullable=False,
    )
    available_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    ciphertext_synced_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
