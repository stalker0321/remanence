"""T185 M3 persistence: deliveries, proofs, claims, completions, retire queue.

String states + CHECKs (PG/SQLite compatible). Raw K/destinations/OTP
never stored: sealed vault blobs, HMAC tags, and OTP HMACs only.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    SmallInteger,
    String,
    UniqueConstraint,
    UUID,
    func,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from remanence.db.base import Base

DELIVERY_STATES = ("DRAFT", "PENDING", "CLAIMED", "CANCELLED", "EXPIRED")
ATTEMPT_STATES = ("CHALLENGE_PENDING", "VERIFIED", "CONSUMED", "EXPIRED", "SUPERSEDED")
OUTBOX_STATES = ("QUEUED", "DONE")


class PendingDelivery(Base):
    __tablename__ = "pending_deliveries"
    __table_args__ = (
        CheckConstraint(
            "channel IN ('email', 'sms')",
            name="ck_pending_deliveries_channel",
        ),
        CheckConstraint(
            "state IN ('DRAFT', 'PENDING', 'CLAIMED', 'CANCELLED', 'EXPIRED')",
            name="ck_pending_deliveries_state",
        ),
        CheckConstraint(
            "((state = 'PENDING' AND publish_statement IS NOT NULL "
            "AND publish_statement_sha256 IS NOT NULL AND publish_signature IS NOT NULL "
            "AND address_commitment IS NOT NULL AND key_commitment IS NOT NULL "
            "AND vault_ciphertext IS NOT NULL) OR "
            "(state = 'CLAIMED' AND publish_statement IS NOT NULL "
            "AND publish_statement_sha256 IS NOT NULL AND publish_signature IS NOT NULL "
            "AND address_commitment IS NOT NULL AND key_commitment IS NOT NULL "
            "AND vault_ciphertext IS NULL) OR "
            "(state IN ('DRAFT', 'CANCELLED', 'EXPIRED')))",
            name="ck_pending_deliveries_publication_shape",
        ),
        CheckConstraint(
            "publish_signature IS NULL OR octet_length(publish_signature) = 69",
            name="ck_pending_deliveries_signature_69",
        ),
        UniqueConstraint("capsule_id", name="uq_pending_deliveries_capsule"),
        UniqueConstraint("delivery_target_id", name="uq_pending_deliveries_target"),
        Index("ix_pending_deliveries_sender", "sender_user_id"),
        Index("ix_pending_deliveries_state", "state"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    sender_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_pending_deliveries_sender", ondelete="RESTRICT"),
        nullable=False,
    )
    capsule_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    delivery_target_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    route_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("pending_send_routes.id", name="fk_pending_deliveries_route"),
        nullable=True,
    )
    channel: Mapped[str] = mapped_column(String(10), nullable=False)
    state: Mapped[str] = mapped_column(String(10), nullable=False)
    claim_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    publish_statement: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    publish_statement_sha256: Mapped[bytes | None] = mapped_column(LargeBinary(32), nullable=True)
    publish_signature: Mapped[bytes | None] = mapped_column(LargeBinary(69), nullable=True)
    address_commitment: Mapped[bytes | None] = mapped_column(LargeBinary(32), nullable=True)
    key_commitment: Mapped[bytes | None] = mapped_column(LargeBinary(32), nullable=True)
    service_key_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    vault_name: Mapped[str | None] = mapped_column(String(40), nullable=True)
    vault_key_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    vault_ciphertext: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    vault_binding_hex: Mapped[str | None] = mapped_column(String(64), nullable=True)
    locator_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("gift_locators.id", name="fk_pending_deliveries_locator"),
        nullable=True,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class AddressProofAttempt(Base):
    __tablename__ = "address_proof_attempts"
    __table_args__ = (
        CheckConstraint(
            "state IN ('CHALLENGE_PENDING', 'VERIFIED', 'CONSUMED', 'EXPIRED', 'SUPERSEDED')",
            name="ck_proof_attempts_state",
        ),
        CheckConstraint(
            "octet_length(destination_tag) = 32",
            name="ck_proof_attempts_tag_32",
        ),
        CheckConstraint(
            "octet_length(otp_hmac) = 32",
            name="ck_proof_attempts_otp_hmac_32",
        ),
        CheckConstraint("guesses >= 0", name="ck_proof_attempts_guesses_nonneg"),
        CheckConstraint("dispatches >= 0", name="ck_proof_attempts_dispatches_nonneg"),
        Index("ix_proof_attempts_delivery", "delivery_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    delivery_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("pending_deliveries.id", name="fk_proof_attempts_delivery", ondelete="CASCADE"),
        nullable=False,
    )
    account_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_proof_attempts_account", ondelete="RESTRICT"),
        nullable=False,
    )
    session_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    state: Mapped[str] = mapped_column(String(20), nullable=False)
    destination_tag: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    # Registry coordinates of the proven address (kind + server-secret
    # lookup tag). Set at start from the committed stored destination;
    # consumed by bind_alias at claim under the address lock.
    address_kind: Mapped[str] = mapped_column(String(10), nullable=False)
    address_lookup_tag: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    provider_reference: Mapped[str | None] = mapped_column(String(128), nullable=True)
    otp_hmac: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    # Outside-DB evidence MAC binding gift/account/session/address/
    # expiry/channel/state (D14): a forged VERIFIED row alone cannot
    # release escrow — the MAC is rechecked at claim before vault open.
    attempt_mac: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    mac_version: Mapped[int] = mapped_column(SmallInteger, nullable=False, default=1)
    guesses: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    dispatches: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class ClaimChallenge(Base):
    __tablename__ = "claim_challenges"
    __table_args__ = (
        CheckConstraint(
            "octet_length(nonce_sha256) = 32",
            name="ck_claim_challenges_nonce_32",
        ),
        Index("ix_claim_challenges_delivery", "delivery_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    delivery_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("pending_deliveries.id", name="fk_claim_challenges_delivery", ondelete="CASCADE"),
        nullable=False,
    )
    account_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_claim_challenges_account", ondelete="RESTRICT"),
        nullable=False,
    )
    bundle_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("user_key_bundles.id", name="fk_claim_challenges_bundle", ondelete="RESTRICT"),
        nullable=False,
    )
    session_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    nonce_sha256: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class PendingClaim(Base):
    """One row per claimed target (target PK = second concurrency guard).
    Terminal claim result retained with the capsule for idempotent replay."""

    __tablename__ = "pending_claims"
    __table_args__ = (
        CheckConstraint(
            "octet_length(envelope_sha256) = 32",
            name="ck_pending_claims_envelope_sha_32",
        ),
        CheckConstraint(
            "octet_length(claim_signature) = 69",
            name="ck_pending_claims_signature_69",
        ),
        CheckConstraint(
            "octet_length(request_digest) = 32",
            name="ck_pending_claims_digest_32",
        ),
        UniqueConstraint("claim_id", name="uq_pending_claims_claim"),
        UniqueConstraint("operation_id", name="uq_pending_claims_operation"),
    )

    delivery_target_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    delivery_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("pending_deliveries.id", name="fk_pending_claims_delivery", ondelete="RESTRICT"),
        nullable=False,
    )
    claim_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    account_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_pending_claims_account", ondelete="RESTRICT"),
        nullable=False,
    )
    bundle_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("user_key_bundles.id", name="fk_pending_claims_bundle", ondelete="RESTRICT"),
        nullable=False,
    )
    claim_binding: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    claim_signature: Mapped[bytes] = mapped_column(LargeBinary(69), nullable=False)
    envelope: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    envelope_sha256: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    operation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    request_digest: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    claimed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class PendingIncoming(Base):
    """Durable v2 account incoming availability, inserted in the SAME
    commit as the claim. Separate feed from v1 (ordered by CLAIM commit
    time, not sender finalize time; protocol-feed-specific cursor), so no
    FK to the v1 capsules table: pending gifts have no v1 row."""

    __tablename__ = "pending_incoming"
    __table_args__ = (
        UniqueConstraint(
            "recipient_user_id", "capsule_id",
            name="uq_pending_incoming_recipient_capsule",
        ),
        Index("ix_pending_incoming_recipient_time", "recipient_user_id", "available_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    recipient_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_pending_incoming_recipient", ondelete="CASCADE"),
        nullable=False,
    )
    capsule_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    delivery_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("pending_deliveries.id", name="fk_pending_incoming_delivery", ondelete="RESTRICT"),
        nullable=False,
    )
    available_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class LocatorOpenCompletion(Base):
    """Minimal operational completion bit. No view metrics, no analytics."""

    __tablename__ = "locator_open_completions"

    capsule_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    publication_sha256: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    completed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class RetireOutbox(Base):
    """Durable background-retirement queue. Opaque IDs only: account,
    capsule, publication hash. Never plaintext/keys/destination/OTP."""

    __tablename__ = "retire_outbox"
    __table_args__ = (
        CheckConstraint(
            "state IN ('QUEUED', 'DONE')",
            name="ck_retire_outbox_state",
        ),
        CheckConstraint(
            "octet_length(publication_sha256) = 32",
            name="ck_retire_outbox_pubhash_32",
        ),
        CheckConstraint("attempts >= 0", name="ck_retire_outbox_attempts_nonneg"),
        UniqueConstraint(
            "account_user_id", "capsule_id", "publication_sha256",
            name="uq_retire_outbox_account_capsule_pubhash",
        ),
        Index("ix_retire_outbox_state", "state"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, nullable=False)
    account_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", name="fk_retire_outbox_account", ondelete="CASCADE"),
        nullable=False,
    )
    capsule_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    publication_sha256: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    state: Mapped[str] = mapped_column(String(10), nullable=False)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
