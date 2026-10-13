"""Add anonymous reports without touching account data."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = "0012_anonymous_telemetry"
down_revision = "0011_beta_safety"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("anonymous_telemetry_reports",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("payload", sa.JSON().with_variant(JSONB(), "postgresql"), nullable=False))


def downgrade():
    op.drop_table("anonymous_telemetry_reports")
