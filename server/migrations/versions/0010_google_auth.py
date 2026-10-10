"""Independent Google identities and bounded registration ceremonies; additive."""
import sqlalchemy as sa
from alembic import op

revision = "0010_google_auth"
down_revision = "0009_passkey_recovery"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("google_identities",
        sa.Column("subject", sa.String(255), primary_key=True),
        sa.Column("user_id", sa.Uuid(), sa.ForeignKey("users.id", ondelete="CASCADE"), unique=True, nullable=False))
    op.create_table("google_flows",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("purpose", sa.String(16), nullable=False),
        sa.Column("secret_hash", sa.LargeBinary(32), nullable=False),
        sa.Column("subject", sa.String(255)), sa.Column("email", sa.String(320)),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True)))


def downgrade():
    op.drop_table("google_flows")
    op.drop_table("google_identities")
