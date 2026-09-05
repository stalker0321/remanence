"""Serialize recipient publication order for cursor pagination.

Revision ID: 0004_r1_publication_order
Revises: 0003_m2_capsule_routing
Create Date: 2026-09-05

"""

import sqlalchemy as sa
from alembic import op

revision = "0004_r1_publication_order"
down_revision = "0003_m2_capsule_routing"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "recipient_delivery_state",
        sa.Column("publication_sequence", sa.BigInteger(), nullable=True),
    )
    op.execute(
        sa.text(
            """
            WITH ranked AS (
                SELECT
                    recipient_user_id,
                    capsule_id,
                    row_number() OVER (
                        PARTITION BY recipient_user_id
                        ORDER BY available_at ASC, capsule_id ASC
                    ) AS publication_sequence
                FROM recipient_delivery_state
            )
            UPDATE recipient_delivery_state AS delivery
            SET publication_sequence = ranked.publication_sequence
            FROM ranked
            WHERE delivery.recipient_user_id = ranked.recipient_user_id
              AND delivery.capsule_id = ranked.capsule_id
            """
        )
    )
    op.alter_column(
        "recipient_delivery_state",
        "publication_sequence",
        existing_type=sa.BigInteger(),
        nullable=False,
    )
    op.create_check_constraint(
        "ck_recipient_delivery_state_publication_sequence_positive",
        "recipient_delivery_state",
        "publication_sequence > 0",
    )
    op.create_unique_constraint(
        "uq_recipient_delivery_state_recipient_publication_sequence",
        "recipient_delivery_state",
        ["recipient_user_id", "publication_sequence"],
    )


def downgrade() -> None:
    op.drop_constraint(
        "uq_recipient_delivery_state_recipient_publication_sequence",
        "recipient_delivery_state",
        type_="unique",
    )
    op.drop_constraint(
        "ck_recipient_delivery_state_publication_sequence_positive",
        "recipient_delivery_state",
        type_="check",
    )
    op.drop_column("recipient_delivery_state", "publication_sequence")
