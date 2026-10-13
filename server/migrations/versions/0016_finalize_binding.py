"""T185: exact finalize idempotency binding (operation + full digest)."""
from alembic import op
import sqlalchemy as sa

revision = "0016_finalize_binding"
down_revision = "0015_pending_declarations"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        "pending_deliveries",
        sa.Column("finalize_operation_id", sa.Uuid(), nullable=True),
    )
    op.add_column(
        "pending_deliveries",
        sa.Column("finalize_request_digest", sa.LargeBinary(32), nullable=True),
    )


def downgrade():
    op.drop_column("pending_deliveries", "finalize_request_digest")
    op.drop_column("pending_deliveries", "finalize_operation_id")
