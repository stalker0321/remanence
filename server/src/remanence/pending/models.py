"""T185 M2 persistence: address registry, frozen routes, common locator.

All states are plain String columns with CHECK constraints (SQLite- and
PostgreSQL-compatible) so logic tests run without live PG. New tables
only; users alteration lives in migration 0013. Everything gated OFF by
Settings flags; no route mounted here.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    LargeBinary,
    SmallInteger,
    String,
    Integer,
    UniqueConstraint,
    UUID,
    func,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from remanence.db.base import Base

ADDRESS_KINDS = ("email", "phone")
BINDING_STATES = ("RESERVED", "VERIFIED", "RETIRED")
ROUTE_MODES = ("EXISTING_USER_V1", "PENDING_V2")
LOCATOR_STATES = ("RESERVED", "ACTIVE", "RETIRED_OPEN", "RETIRED_TERMINAL")


class DeliveryAddressBinding(Base):
    """Verified-address registry row. Reservation constrains claim; only a
    VERIFIED row with proven evidence routes new sends to v1."""

    __tablename__ = "delivery_address_bindings"
    __table_args__ = (
        CheckConstraint(
            "kind IN ('email', 'phone')",
            name="ck_address_bindings_kind",
        ),
        CheckConstraint(
            "state IN ('RESERVED', 'VERIFIED', 'RETIRED')",
            name="ck_address_bindings_state",
        ),
        CheckConstraint(
            "binding_revision > 0",
            name="ck_address_bindings_revision_positive",
        ),
        CheckConstraint(
            "octet_length(lookup_tag) = 32",
            name="ck_address_bindings_lookup_tag_32",
        ),
        CheckConstraint(
            "(state <> 'VERIFIED' OR verified_at IS NOT NULL)",
            name="ck_address_bindings_verified_needs_time",
        ),
        Index(
            "uq_address_bindings_live_owner",
            "kind",
            "lookup_tag",
            unique=True,
            postgresql_where=text("state <> 'RETIRED'"),
        ),
        Index("ix_address_bindings_owner", "owner_user_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    kind: Mapped[str] = mapped_column(String(10), nullable=False)
    normalization_version: Mapped[int] = mapped_column(SmallInteger, nullable=False, default=1)
    lookup_tag: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    owner_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_address_bindings_owner_users", ondelete="RESTRICT"),
        nullable=False,
    )
    state: Mapped[str] = mapped_column(String(10), nullable=False)
    binding_revision: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    evidence_method: Mapped[str | None] = mapped_column(String(20), nullable=True)
    evidence_ref: Mapped[bytes | None] = mapped_column(LargeBinary(32), nullable=True)
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class PendingSendRoute(Base):
    """Frozen route: linearization point for binding-vs-freeze races."""

    __tablename__ = "pending_send_routes"
    __table_args__ = (
        CheckConstraint(
            "mode IN ('EXISTING_USER_V1', 'PENDING_V2')",
            name="ck_send_routes_mode",
        ),
        CheckConstraint(
            "((mode = 'EXISTING_USER_V1' AND target_user_id IS NOT NULL "
            "AND target_bundle_id IS NOT NULL AND delivery_target_id IS NULL) OR "
            "(mode = 'PENDING_V2' AND delivery_target_id IS NOT NULL "
            "AND target_user_id IS NULL AND target_bundle_id IS NULL))",
            name="ck_send_routes_arm_coherence",
        ),
        Index("ix_send_routes_sender", "sender_user_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    sender_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_send_routes_sender_users", ondelete="RESTRICT"),
        nullable=False,
    )
    capsule_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    mode: Mapped[str] = mapped_column(String(20), nullable=False)
    destination_kind: Mapped[str] = mapped_column(String(10), nullable=False)
    # AEAD-sealed normalized destination (T183 encrypted-at-rest contract).
    # Raw recipient destinations never reach SQL; lookup uses the
    # server-secret HMAC tag in delivery_address_bindings, never this blob.
    sealed_destination: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    target_user_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    target_bundle_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    delivery_target_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    binding_revision: Mapped[int | None] = mapped_column(Integer, nullable=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class GiftLocator(Base):
    """Common 5-char public locator, version-independent. Code carries no
    authorization; only ACTIVE codes resolve in lookup."""

    __tablename__ = "gift_locators"
    __table_args__ = (
        CheckConstraint(
            "state IN ('RESERVED', 'ACTIVE', 'RETIRED_OPEN', 'RETIRED_TERMINAL')",
            name="ck_gift_locators_state",
        ),
        CheckConstraint(
            "octet_length(code_hmac) = 32",
            name="ck_gift_locators_hmac_32",
        ),
        UniqueConstraint("capsule_id", name="uq_gift_locators_capsule"),
        UniqueConstraint("code_hmac", name="uq_gift_locators_code_hmac"),
        Index("ix_gift_locators_state", "state"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    capsule_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("capsules.id", name="fk_gift_locators_capsule", ondelete="CASCADE"),
        nullable=False,
    )
    code_hmac: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    code_ciphertext: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    state: Mapped[str] = mapped_column(String(20), nullable=False)
    hint_mask: Mapped[str] = mapped_column(String(64), nullable=False)
    policy_version: Mapped[int] = mapped_column(SmallInteger, nullable=False, default=1)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    retired_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class UsedLocatorTombstone(Base):
    """HMAC-only tombstone: exposed codes are never reused, even after
    retirement/erasure. Minimal storage cost, no personal metadata."""

    __tablename__ = "used_locator_tombstones"
    __table_args__ = (
        CheckConstraint(
            "octet_length(code_hmac) = 32",
            name="ck_locator_tombstones_hmac_32",
        ),
    )

    code_hmac: Mapped[bytes] = mapped_column(LargeBinary(32), primary_key=True, nullable=False)
    retired_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
