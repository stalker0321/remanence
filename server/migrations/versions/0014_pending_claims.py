"""T185 M3: deliveries, proofs, claims, completions, retire queue."""
from alembic import op
import sqlalchemy as sa

revision = "0014_pending_claims"
down_revision = "0013_pending_foundation"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "pending_deliveries",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("sender_user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("capsule_id", sa.Uuid(), nullable=False),
        sa.Column("delivery_target_id", sa.Uuid(), nullable=False),
        sa.Column("route_id", sa.Uuid(), sa.ForeignKey("pending_send_routes.id"), nullable=True),
        sa.Column("channel", sa.String(10), nullable=False),
        sa.Column("state", sa.String(10), nullable=False),
        sa.Column("claim_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("publish_statement", sa.LargeBinary(), nullable=True),
        sa.Column("publish_statement_sha256", sa.LargeBinary(32), nullable=True),
        sa.Column("publish_signature", sa.LargeBinary(69), nullable=True),
        sa.Column("address_commitment", sa.LargeBinary(32), nullable=True),
        sa.Column("key_commitment", sa.LargeBinary(32), nullable=True),
        sa.Column("service_key_id", sa.Uuid(), nullable=True),
        sa.Column("vault_name", sa.String(40), nullable=True),
        sa.Column("vault_key_id", sa.String(128), nullable=True),
        sa.Column("vault_ciphertext", sa.LargeBinary(), nullable=True),
        sa.Column("vault_binding_hex", sa.String(64), nullable=True),
        sa.Column("locator_id", sa.Uuid(), sa.ForeignKey("gift_locators.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("channel IN ('email', 'sms')", name="ck_pending_deliveries_channel"),
        sa.CheckConstraint(
            "state IN ('DRAFT', 'PENDING', 'CLAIMED', 'CANCELLED', 'EXPIRED')",
            name="ck_pending_deliveries_state",
        ),
        sa.CheckConstraint(
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
        sa.CheckConstraint(
            "publish_signature IS NULL OR octet_length(publish_signature) = 69",
            name="ck_pending_deliveries_signature_69",
        ),
        sa.UniqueConstraint("capsule_id", name="uq_pending_deliveries_capsule"),
        sa.UniqueConstraint("delivery_target_id", name="uq_pending_deliveries_target"),
    )
    op.create_index("ix_pending_deliveries_sender", "pending_deliveries", ["sender_user_id"])
    op.create_index("ix_pending_deliveries_state", "pending_deliveries", ["state"])
    op.create_table(
        "address_proof_attempts",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("delivery_id", sa.Uuid(), sa.ForeignKey("pending_deliveries.id", ondelete="CASCADE"), nullable=False),
        sa.Column("account_user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("session_id", sa.Uuid(), nullable=False),
        sa.Column("state", sa.String(20), nullable=False),
        sa.Column("destination_tag", sa.LargeBinary(32), nullable=False),
        sa.Column("address_kind", sa.String(10), nullable=False),
        sa.Column("address_lookup_tag", sa.LargeBinary(32), nullable=False),
        sa.Column("provider_reference", sa.String(128), nullable=True),
        sa.Column("otp_hmac", sa.LargeBinary(32), nullable=False),
        sa.Column("attempt_mac", sa.LargeBinary(32), nullable=False),
        sa.Column("mac_version", sa.SmallInteger(), nullable=False, server_default="1"),
        sa.Column("guesses", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("dispatches", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("verified_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint(
            "state IN ('CHALLENGE_PENDING', 'VERIFIED', 'CONSUMED', 'EXPIRED', 'SUPERSEDED')",
            name="ck_proof_attempts_state",
        ),
        sa.CheckConstraint("octet_length(destination_tag) = 32", name="ck_proof_attempts_tag_32"),
        sa.CheckConstraint("octet_length(otp_hmac) = 32", name="ck_proof_attempts_otp_hmac_32"),
        sa.CheckConstraint("guesses >= 0", name="ck_proof_attempts_guesses_nonneg"),
        sa.CheckConstraint("dispatches >= 0", name="ck_proof_attempts_dispatches_nonneg"),
    )
    op.create_index("ix_proof_attempts_delivery", "address_proof_attempts", ["delivery_id"])
    op.create_table(
        "claim_challenges",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("delivery_id", sa.Uuid(), sa.ForeignKey("pending_deliveries.id", ondelete="CASCADE"), nullable=False),
        sa.Column("account_user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("bundle_id", sa.Uuid(), sa.ForeignKey("user_key_bundles.id"), nullable=False),
        sa.Column("session_id", sa.Uuid(), nullable=False),
        sa.Column("nonce_sha256", sa.LargeBinary(32), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("octet_length(nonce_sha256) = 32", name="ck_claim_challenges_nonce_32"),
    )
    op.create_index("ix_claim_challenges_delivery", "claim_challenges", ["delivery_id"])
    op.create_table(
        "pending_claims",
        sa.Column("delivery_target_id", sa.Uuid(), primary_key=True),
        sa.Column("delivery_id", sa.Uuid(), sa.ForeignKey("pending_deliveries.id"), nullable=False),
        sa.Column("claim_id", sa.Uuid(), nullable=False),
        sa.Column("account_user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("bundle_id", sa.Uuid(), sa.ForeignKey("user_key_bundles.id"), nullable=False),
        sa.Column("claim_binding", sa.LargeBinary(), nullable=False),
        sa.Column("claim_signature", sa.LargeBinary(69), nullable=False),
        sa.Column("envelope", sa.LargeBinary(), nullable=False),
        sa.Column("envelope_sha256", sa.LargeBinary(32), nullable=False),
        sa.Column("operation_id", sa.Uuid(), nullable=False),
        sa.Column("request_digest", sa.LargeBinary(32), nullable=False),
        sa.Column("claimed_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("octet_length(envelope_sha256) = 32", name="ck_pending_claims_envelope_sha_32"),
        sa.CheckConstraint("octet_length(claim_signature) = 69", name="ck_pending_claims_signature_69"),
        sa.CheckConstraint("octet_length(request_digest) = 32", name="ck_pending_claims_digest_32"),
        sa.UniqueConstraint("claim_id", name="uq_pending_claims_claim"),
        sa.UniqueConstraint("operation_id", name="uq_pending_claims_operation"),
    )
    op.create_table(
        "pending_incoming",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("recipient_user_id", sa.Uuid(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("capsule_id", sa.Uuid(), nullable=False),
        sa.Column("delivery_id", sa.Uuid(), sa.ForeignKey("pending_deliveries.id"), nullable=False),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("recipient_user_id", "capsule_id", name="uq_pending_incoming_recipient_capsule"),
    )
    op.create_index("ix_pending_incoming_recipient_time", "pending_incoming", ["recipient_user_id", "available_at"])
    op.create_table(
        "locator_open_completions",
        sa.Column("capsule_id", sa.Uuid(), primary_key=True),
        sa.Column("publication_sha256", sa.LargeBinary(32), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "retire_outbox",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("account_user_id", sa.Uuid(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("capsule_id", sa.Uuid(), nullable=False),
        sa.Column("publication_sha256", sa.LargeBinary(32), nullable=False),
        sa.Column("state", sa.String(10), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("state IN ('QUEUED', 'DONE')", name="ck_retire_outbox_state"),
        sa.CheckConstraint("octet_length(publication_sha256) = 32", name="ck_retire_outbox_pubhash_32"),
        sa.CheckConstraint("attempts >= 0", name="ck_retire_outbox_attempts_nonneg"),
        sa.UniqueConstraint(
            "account_user_id", "capsule_id", "publication_sha256",
            name="uq_retire_outbox_account_capsule_pubhash",
        ),
    )
    op.create_index("ix_retire_outbox_state", "retire_outbox", ["state"])


def downgrade():
    op.drop_index("ix_retire_outbox_state", table_name="retire_outbox")
    op.drop_table("retire_outbox")
    op.drop_table("locator_open_completions")
    op.drop_index("ix_pending_incoming_recipient_time", table_name="pending_incoming")
    op.drop_table("pending_incoming")
    op.drop_table("pending_claims")
    op.drop_index("ix_claim_challenges_delivery", table_name="claim_challenges")
    op.drop_table("claim_challenges")
    op.drop_index("ix_proof_attempts_delivery", table_name="address_proof_attempts")
    op.drop_table("address_proof_attempts")
    op.drop_index("ix_pending_deliveries_state", table_name="pending_deliveries")
    op.drop_index("ix_pending_deliveries_sender", table_name="pending_deliveries")
    op.drop_table("pending_deliveries")
