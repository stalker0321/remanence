"""T185: validated R3 blob declarations bound at finalize."""
from alembic import op
import sqlalchemy as sa

revision = "0015_pending_declarations"
down_revision = "0014_pending_claims"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        "pending_deliveries",
        sa.Column("blob_declarations", sa.JSON(), nullable=True),
    )


def downgrade():
    op.drop_column("pending_deliveries", "blob_declarations")
