"""ADR-011 additive DEV/TEST tables; no live migration performed."""
import sqlalchemy as sa
from alembic import op

revision = "0009_passkey_recovery"
down_revision = "0008_music_staging"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("recovery_credentials",
        sa.Column("id", sa.String(1366), primary_key=True),
        sa.Column("user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("public_key", sa.LargeBinary(), nullable=False),
        sa.Column("counter", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False))
    op.create_index("ix_recovery_credentials_user_id", "recovery_credentials", ["user_id"])
    op.create_table("recovery_records",
        sa.Column("user_id", sa.Uuid(), sa.ForeignKey("users.id"), primary_key=True),
        sa.Column("credential_id", sa.String(1366), sa.ForeignKey("recovery_credentials.id"), unique=True, nullable=False),
        sa.Column("bundle_id", sa.Uuid(), sa.ForeignKey("user_key_bundles.id"), nullable=False),
        sa.Column("generation", sa.Integer(), nullable=False),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.CheckConstraint("generation = 1", name="ck_recovery_generation_v1"))
    op.create_table("recovery_challenges",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("user_id", sa.Uuid(), sa.ForeignKey("users.id")),
        sa.Column("session_id", sa.Uuid()),
        sa.Column("purpose", sa.String(16), nullable=False),
        sa.Column("credential_id", sa.String(1366)),
        sa.Column("challenge", sa.LargeBinary(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True)))


def downgrade():
    op.drop_table("recovery_challenges")
    op.drop_table("recovery_records")
    op.drop_index("ix_recovery_credentials_user_id", table_name="recovery_credentials")
    op.drop_table("recovery_credentials")
