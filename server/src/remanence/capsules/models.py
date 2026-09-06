"""Capsule ownership and publication state table."""

import enum
import uuid
from datetime import datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    LargeBinary,
    PrimaryKeyConstraint,
    SmallInteger,
    UniqueConstraint,
    UUID,
    func,
)
from sqlalchemy import Enum as SaEnum
from sqlalchemy.orm import Mapped, mapped_column

from remanence.db.base import Base
from remanence.users.key_models import UserKeyBundle as _UserKeyBundle
from remanence.users.models import User as _User


class CapsuleState(str, enum.Enum):
    DRAFT = "DRAFT"
    READY = "READY"
    ABORTED = "ABORTED"
    REVOKED = "REVOKED"


class Capsule(Base):
    __tablename__ = "capsules"
    __table_args__ = (
        CheckConstraint(
            "protocol_version > 0",
            name="ck_capsules_protocol_version_positive",
        ),
        CheckConstraint(
            "draft_expires_at > created_at",
            name="ck_capsules_draft_expiry_order",
        ),
        CheckConstraint(
            "signed_statement_sha256 IS NULL OR octet_length(signed_statement_sha256) = 32",
            name="ck_capsules_signed_statement_sha256_32",
        ),
        CheckConstraint(
            "publish_signature IS NULL OR octet_length(publish_signature) = 69",
            name="ck_capsules_publish_signature_69",
        ),
        CheckConstraint(
            "((state IN ('READY', 'REVOKED') AND ready_at IS NOT NULL "
            "AND signed_statement IS NOT NULL AND signed_statement_sha256 IS NOT NULL "
            "AND publish_signature IS NOT NULL) OR "
            "(state IN ('DRAFT', 'ABORTED') AND ready_at IS NULL "
            "AND signed_statement IS NULL AND signed_statement_sha256 IS NULL "
            "AND publish_signature IS NULL))",
            name="ck_capsules_state_finalization_shape",
        ),
        CheckConstraint(
            "((state = 'REVOKED' AND tombstone_sequence IS NOT NULL AND revoked_at IS NOT NULL) OR "
            "(state IN ('DRAFT', 'READY', 'ABORTED') "
            "AND tombstone_sequence IS NULL AND revoked_at IS NULL))",
            name="ck_capsules_tombstone_fields_shape",
        ),
        CheckConstraint(
            "tombstone_sequence IS NULL OR tombstone_sequence > 0",
            name="ck_capsules_tombstone_sequence_positive",
        ),
        UniqueConstraint(
            "recipient_user_id",
            "tombstone_sequence",
            name="uq_capsules_recipient_tombstone_sequence",
        ),
        Index("ix_capsules_sender_user_id", "sender_user_id"),
        Index("ix_capsules_recipient_user_id", "recipient_user_id"),
        Index("ix_capsules_draft_expires_at", "draft_expires_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        nullable=False,
    )
    sender_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "users.id",
            name="fk_capsules_sender_user_id_users",
            ondelete="RESTRICT",
        ),
        nullable=False,
    )
    recipient_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "users.id",
            name="fk_capsules_recipient_user_id_users",
            ondelete="RESTRICT",
        ),
        nullable=False,
    )
    sender_key_bundle_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "user_key_bundles.id",
            name="fk_capsules_sender_key_bundle_id_user_key_bundles",
            ondelete="RESTRICT",
        ),
        nullable=False,
    )
    recipient_key_bundle_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "user_key_bundles.id",
            name="fk_capsules_recipient_key_bundle_id_user_key_bundles",
            ondelete="RESTRICT",
        ),
        nullable=False,
    )
    protocol_version: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    state: Mapped[CapsuleState] = mapped_column(
        SaEnum(CapsuleState, name="capsule_state", native_enum=True),
        nullable=False,
    )
    signed_statement: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    signed_statement_sha256: Mapped[bytes | None] = mapped_column(
        LargeBinary(32),
        nullable=True,
    )
    publish_signature: Mapped[bytes | None] = mapped_column(
        LargeBinary(69),
        nullable=True,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    ready_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    tombstone_sequence: Mapped[int | None] = mapped_column(
        BigInteger,
        nullable=True,
    )
    revoked_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    draft_expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
    )


class RecipientTombstoneCounter(Base):
    """Transactional high-water mark for one recipient's tombstone feed."""

    __tablename__ = "recipient_tombstone_counters"
    __table_args__ = (
        PrimaryKeyConstraint(
            "recipient_user_id",
            name="pk_recipient_tombstone_counters",
        ),
        CheckConstraint(
            "last_sequence >= 0",
            name="ck_recipient_tombstone_counters_last_sequence_nonnegative",
        ),
    )

    recipient_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "users.id",
            name="fk_recipient_tombstone_counters_recipient_user_id_users",
            ondelete="CASCADE",
        ),
        primary_key=True,
        nullable=False,
    )
    last_sequence: Mapped[int] = mapped_column(BigInteger, nullable=False)
