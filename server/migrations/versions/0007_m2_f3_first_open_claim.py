"""Add the durable recipient first-open claim timestamp."""

import sqlalchemy as sa
from alembic import op


revision = "0007_m2_f3_first_open_claim"
down_revision = "0006_m2_f3_tombstone_feed"
branch_labels = None
depends_on = None

_CHECK = "ck_capsules_first_opened_state_shape"


def upgrade() -> None:
    op.add_column("capsules", sa.Column("first_opened_at", sa.DateTime(timezone=True), nullable=True))
    op.create_check_constraint(
        _CHECK,
        "capsules",
        "first_opened_at IS NULL OR state = 'READY'",
    )


def downgrade() -> None:
    op.drop_constraint(_CHECK, "capsules", type_="check")
    op.drop_column("capsules", "first_opened_at")
