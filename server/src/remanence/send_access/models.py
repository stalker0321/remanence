"""Immutable publication receipts, not mutable balance counters."""

import uuid
from datetime import datetime

from sqlalchemy import (
    CheckConstraint, DateTime, ForeignKey, Index, LargeBinary,
    PrimaryKeyConstraint, SmallInteger, String, UniqueConstraint, UUID,
)
from sqlalchemy.orm import Mapped, mapped_column

from remanence.db.base import Base


class AccountSendAccess(Base):
    __tablename__ = "account_send_access"
    __table_args__ = (
        PrimaryKeyConstraint("account_id", name="pk_account_send_access"),
        CheckConstraint("policy_version = 1", name="ck_account_send_access_policy"),
        UniqueConstraint("lifetime_grant_key", name="uq_account_send_access_grant_key"),
        CheckConstraint(
            "(lifetime_granted_at IS NULL AND lifetime_grant_key IS NULL "
            "AND lifetime_grant_origin IS NULL) OR "
            "(lifetime_granted_at IS NOT NULL AND lifetime_grant_key IS NOT NULL "
            "AND lifetime_grant_origin IS NOT NULL AND btrim(lifetime_grant_key) <> '' "
            "AND btrim(lifetime_grant_origin) <> '')",
            name="ck_account_send_access_lifetime_shape",
        ),
    )
    account_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_account_send_access_user", ondelete="CASCADE"),
        primary_key=True,
    )
    policy_version: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    lifetime_granted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    lifetime_grant_key: Mapped[str | None] = mapped_column(String(160))
    lifetime_grant_origin: Mapped[str | None] = mapped_column(String(32))


class SendPublication(Base):
    __tablename__ = "send_publications"
    __table_args__ = (
        PrimaryKeyConstraint("capsule_id", name="pk_send_publications"),
        UniqueConstraint("v1_capsule_id", name="uq_send_publications_v1"),
        UniqueConstraint("pending_delivery_id", name="uq_send_publications_pending"),
        UniqueConstraint("account_id", "bucket", "free_slot", name="uq_send_publications_free_slot"),
        CheckConstraint("policy_version = 1", name="ck_send_publications_policy"),
        CheckConstraint("octet_length(publication_sha256) = 32", name="ck_send_publications_hash"),
        CheckConstraint(
            "(v1_capsule_id IS NOT NULL AND pending_delivery_id IS NULL "
            "AND v1_capsule_id = capsule_id) OR "
            "(v1_capsule_id IS NULL AND pending_delivery_id IS NOT NULL)",
            name="ck_send_publications_source",
        ),
        CheckConstraint(
            "(funding = 'FREE' AND free_slot IS NOT NULL AND free_slot IN (1,2)) OR "
            "(funding IN ('LIFETIME','BETA_EXEMPT') AND free_slot IS NULL)",
            name="ck_send_publications_funding",
        ),
        CheckConstraint(
            "(v1_capsule_id IS NOT NULL AND recipient_user_id_at_publish IS NOT NULL AND "
            "((classification = 'ACCOUNT_EQUAL' AND recipient_user_id_at_publish = account_id "
            "AND bucket = 'SELF') OR "
            "(classification = 'ACCOUNT_OTHER' AND recipient_user_id_at_publish <> account_id "
            "AND bucket = 'OTHER'))) OR "
            "(pending_delivery_id IS NOT NULL AND recipient_user_id_at_publish IS NULL "
            "AND classification = 'UNRESOLVED_AT_PUBLISH' AND bucket = 'OTHER')",
            name="ck_send_publications_classification",
        ),
        Index("ix_send_publications_account_recorded", "account_id", "recorded_at", "capsule_id"),
    )
    capsule_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    account_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("account_send_access.account_id", name="fk_send_publications_access", ondelete="CASCADE"),
        nullable=False,
    )
    v1_capsule_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("capsules.id", name="fk_send_publications_v1", ondelete="RESTRICT"),
    )
    pending_delivery_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("pending_deliveries.id", name="fk_send_publications_pending", ondelete="RESTRICT"),
    )
    bucket: Mapped[str] = mapped_column(String(5), nullable=False)
    recipient_user_id_at_publish: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    classification: Mapped[str] = mapped_column(String(21), nullable=False)
    funding: Mapped[str] = mapped_column(String(11), nullable=False)
    free_slot: Mapped[int | None] = mapped_column(SmallInteger)
    policy_version: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    publication_sha256: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
