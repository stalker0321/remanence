"""Capsule blob declaration and upload state table."""

import enum
import uuid

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    ForeignKey,
    Index,
    LargeBinary,
    SmallInteger,
    String,
    UUID,
    UniqueConstraint,
    text,
)
from sqlalchemy import Enum as SaEnum
from sqlalchemy.orm import Mapped, mapped_column

from remanence.capsules.models import Capsule as _Capsule
from remanence.db.base import Base


class CapsuleBlobKind(str, enum.Enum):
    RECOGNITION_MANIFEST = "RECOGNITION_MANIFEST"
    CONTENT_MANIFEST = "CONTENT_MANIFEST"
    PHOTO = "PHOTO"


class CapsuleBlobState(str, enum.Enum):
    DECLARED = "DECLARED"
    STORED = "STORED"


class CapsuleBlob(Base):
    __tablename__ = "capsule_blobs"
    __table_args__ = (
        UniqueConstraint("object_key", name="uq_capsule_blobs_object_key"),
        CheckConstraint(
            "expected_ciphertext_size > 0",
            name="ck_capsule_blobs_expected_ciphertext_size_positive",
        ),
        CheckConstraint(
            "octet_length(expected_ciphertext_sha256) = 32",
            name="ck_capsule_blobs_expected_ciphertext_sha256_32",
        ),
        CheckConstraint(
            "((kind = 'PHOTO' AND ordinal IS NOT NULL AND ordinal BETWEEN 0 AND 4) OR "
            "(kind IN ('RECOGNITION_MANIFEST', 'CONTENT_MANIFEST') AND ordinal IS NULL))",
            name="ck_capsule_blobs_kind_ordinal_shape",
        ),
        Index("ix_capsule_blobs_capsule_id", "capsule_id"),
        Index(
            "uq_capsule_blobs_one_recognition_manifest_per_capsule",
            "capsule_id",
            unique=True,
            postgresql_where=text("kind = 'RECOGNITION_MANIFEST'"),
        ),
        Index(
            "uq_capsule_blobs_one_content_manifest_per_capsule",
            "capsule_id",
            unique=True,
            postgresql_where=text("kind = 'CONTENT_MANIFEST'"),
        ),
        Index(
            "uq_capsule_blobs_photo_ordinal_per_capsule",
            "capsule_id",
            "ordinal",
            unique=True,
            postgresql_where=text("kind = 'PHOTO'"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        nullable=False,
    )
    capsule_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey(
            "capsules.id",
            name="fk_capsule_blobs_capsule_id_capsules",
            ondelete="CASCADE",
        ),
        nullable=False,
    )
    kind: Mapped[CapsuleBlobKind] = mapped_column(
        SaEnum(CapsuleBlobKind, name="capsule_blob_kind", native_enum=True),
        nullable=False,
    )
    ordinal: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    object_key: Mapped[str] = mapped_column(String(512), nullable=False)
    expected_ciphertext_size: Mapped[int] = mapped_column(BigInteger, nullable=False)
    expected_ciphertext_sha256: Mapped[bytes] = mapped_column(
        LargeBinary(32),
        nullable=False,
    )
    state: Mapped[CapsuleBlobState] = mapped_column(
        SaEnum(CapsuleBlobState, name="capsule_blob_state", native_enum=True),
        nullable=False,
    )
