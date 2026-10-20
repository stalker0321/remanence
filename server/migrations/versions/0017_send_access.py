"""T373: fresh 2+2, immutable publication receipts, no historical debit."""

from alembic import op
import sqlalchemy as sa

revision = "0017_send_access"
down_revision = "0016_finalize_binding"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "account_send_access",
        sa.Column("account_id", sa.UUID(), nullable=False),
        sa.Column("policy_version", sa.SmallInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("lifetime_granted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("lifetime_grant_key", sa.String(160), nullable=True),
        sa.Column("lifetime_grant_origin", sa.String(32), nullable=True),
        sa.PrimaryKeyConstraint("account_id", name="pk_account_send_access"),
        sa.ForeignKeyConstraint(["account_id"], ["users.id"],
                                name="fk_account_send_access_user", ondelete="CASCADE"),
        sa.UniqueConstraint("lifetime_grant_key", name="uq_account_send_access_grant_key"),
        sa.CheckConstraint("policy_version = 1", name="ck_account_send_access_policy"),
        sa.CheckConstraint(
            "(lifetime_granted_at IS NULL AND lifetime_grant_key IS NULL "
            "AND lifetime_grant_origin IS NULL) OR "
            "(lifetime_granted_at IS NOT NULL AND lifetime_grant_key IS NOT NULL "
            "AND lifetime_grant_origin IS NOT NULL AND btrim(lifetime_grant_key) <> '' "
            "AND btrim(lifetime_grant_origin) <> '')",
            name="ck_account_send_access_lifetime_shape",
        ),
    )
    op.create_table(
        "send_publications",
        sa.Column("capsule_id", sa.UUID(), nullable=False),
        sa.Column("account_id", sa.UUID(), nullable=False),
        sa.Column("v1_capsule_id", sa.UUID(), nullable=True),
        sa.Column("pending_delivery_id", sa.UUID(), nullable=True),
        sa.Column("bucket", sa.String(5), nullable=False),
        sa.Column("recipient_user_id_at_publish", sa.UUID(), nullable=True),
        sa.Column("classification", sa.String(21), nullable=False),
        sa.Column("funding", sa.String(11), nullable=False),
        sa.Column("free_slot", sa.SmallInteger(), nullable=True),
        sa.Column("policy_version", sa.SmallInteger(), nullable=False),
        sa.Column("publication_sha256", sa.LargeBinary(32), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("capsule_id", name="pk_send_publications"),
        sa.ForeignKeyConstraint(["account_id"], ["account_send_access.account_id"],
                                name="fk_send_publications_access", ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["v1_capsule_id"], ["capsules.id"],
                                name="fk_send_publications_v1", ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["pending_delivery_id"], ["pending_deliveries.id"],
                                name="fk_send_publications_pending", ondelete="RESTRICT"),
        sa.UniqueConstraint("v1_capsule_id", name="uq_send_publications_v1"),
        sa.UniqueConstraint("pending_delivery_id", name="uq_send_publications_pending"),
        sa.UniqueConstraint("account_id", "bucket", "free_slot", name="uq_send_publications_free_slot"),
        sa.CheckConstraint("policy_version = 1", name="ck_send_publications_policy"),
        sa.CheckConstraint("octet_length(publication_sha256) = 32", name="ck_send_publications_hash"),
        sa.CheckConstraint(
            "(v1_capsule_id IS NOT NULL AND pending_delivery_id IS NULL "
            "AND v1_capsule_id = capsule_id) OR "
            "(v1_capsule_id IS NULL AND pending_delivery_id IS NOT NULL)",
            name="ck_send_publications_source",
        ),
        sa.CheckConstraint(
            "(funding = 'FREE' AND free_slot IS NOT NULL AND free_slot IN (1,2)) OR "
            "(funding IN ('LIFETIME','BETA_EXEMPT') AND free_slot IS NULL)",
            name="ck_send_publications_funding",
        ),
        sa.CheckConstraint(
            "(v1_capsule_id IS NOT NULL AND recipient_user_id_at_publish IS NOT NULL AND "
            "((classification = 'ACCOUNT_EQUAL' AND recipient_user_id_at_publish = account_id "
            "AND bucket = 'SELF') OR "
            "(classification = 'ACCOUNT_OTHER' AND recipient_user_id_at_publish <> account_id "
            "AND bucket = 'OTHER'))) OR "
            "(pending_delivery_id IS NOT NULL AND recipient_user_id_at_publish IS NULL "
            "AND classification = 'UNRESOLVED_AT_PUBLISH' AND bucket = 'OTHER')",
            name="ck_send_publications_classification",
        ),
    )
    op.create_index("ix_send_publications_account_recorded", "send_publications",
                    ["account_id", "recorded_at", "capsule_id"])
    op.add_column("capsules", sa.Column("send_accounting_version", sa.SmallInteger(), nullable=True))
    op.create_check_constraint(
        "ck_capsules_send_accounting", "capsules",
        "send_accounting_version IS NULL OR "
        "(send_accounting_version = 1 AND state IN ('READY','REVOKED'))",
    )
    op.add_column("pending_deliveries", sa.Column("send_accounting_version", sa.SmallInteger(), nullable=True))
    op.create_check_constraint(
        "ck_pending_deliveries_send_accounting", "pending_deliveries",
        "send_accounting_version IS NULL OR (send_accounting_version = 1 "
        "AND state IN ('PENDING','CLAIMED','CANCELLED','EXPIRED') "
        "AND publish_statement IS NOT NULL AND publish_statement_sha256 IS NOT NULL "
        "AND publish_signature IS NOT NULL)",
    )
    # One bounded existing-user provisioning operation. No source/receipt backfill.
    op.execute(
        "INSERT INTO account_send_access(account_id, policy_version, created_at) "
        "SELECT id, 1, CURRENT_TIMESTAMP FROM users "
        "ON CONFLICT (account_id) DO NOTHING"
    )


def downgrade():
    # Allowance/lifetime evidence must not be silently erased on rollback.
    raise RuntimeError("send accounting downgrade requires a separately approved data plan")
