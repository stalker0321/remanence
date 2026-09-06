"""Add recipient tombstone feed metadata and transactional counters."""

import sqlalchemy as sa
from alembic import op


revision = "0006_m2_f3_tombstone_feed"
down_revision = "0005_m2_f3_capsule_revocation"
branch_labels = None
depends_on = None

_TOMBSTONE_FIELDS_CHECK = "ck_capsules_tombstone_fields_shape"
_TOMBSTONE_SEQUENCE_CHECK = "ck_capsules_tombstone_sequence_positive"
_TOMBSTONE_SEQUENCE_UNIQUE = "uq_capsules_recipient_tombstone_sequence"
_COUNTER_TABLE = "recipient_tombstone_counters"
_COUNTER_CHECK = "ck_recipient_tombstone_counters_last_sequence_nonnegative"
_COUNTER_FK = "fk_recipient_tombstone_counters_recipient_user_id_users"

_TOMBSTONE_FIELDS_CHECK_SQL = (
    "((state = 'REVOKED' AND tombstone_sequence IS NOT NULL AND revoked_at IS NOT NULL) OR "
    "(state IN ('DRAFT', 'READY', 'ABORTED') "
    "AND tombstone_sequence IS NULL AND revoked_at IS NULL))"
)
_TOMBSTONE_SEQUENCE_CHECK_SQL = "tombstone_sequence IS NULL OR tombstone_sequence > 0"


def upgrade() -> None:
    op.add_column("capsules", sa.Column("tombstone_sequence", sa.BigInteger(), nullable=True))
    op.add_column("capsules", sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True))
    op.create_table(
        _COUNTER_TABLE,
        sa.Column("recipient_user_id", sa.Uuid(), nullable=False),
        sa.Column("last_sequence", sa.BigInteger(), nullable=False),
        sa.PrimaryKeyConstraint("recipient_user_id", name="pk_recipient_tombstone_counters"),
        sa.ForeignKeyConstraint(
            ["recipient_user_id"],
            ["users.id"],
            name=_COUNTER_FK,
            ondelete="CASCADE",
        ),
        sa.CheckConstraint(
            "last_sequence >= 0",
            name=_COUNTER_CHECK,
        ),
    )

    # CURRENT_TIMESTAMP is a deterministic migration backfill marker, not the
    # true revocation instant, which is unknowable for pre-existing rows.
    op.execute(
        sa.text(
            """
            WITH ranked AS (
                SELECT
                    id,
                    row_number() OVER (
                        PARTITION BY recipient_user_id
                        ORDER BY ready_at ASC, id ASC
                    ) AS tombstone_sequence
                FROM capsules
                WHERE state = 'REVOKED'
            )
            UPDATE capsules AS capsule
            SET tombstone_sequence = ranked.tombstone_sequence,
                revoked_at = CURRENT_TIMESTAMP
            FROM ranked
            WHERE capsule.id = ranked.id
            """
        )
    )
    op.execute(
        sa.text(
            """
            INSERT INTO recipient_tombstone_counters (recipient_user_id, last_sequence)
            SELECT recipient_user_id, max(tombstone_sequence)
            FROM capsules
            WHERE state = 'REVOKED'
            GROUP BY recipient_user_id
            """
        )
    )
    op.create_check_constraint(
        _TOMBSTONE_FIELDS_CHECK,
        "capsules",
        _TOMBSTONE_FIELDS_CHECK_SQL,
    )
    op.create_check_constraint(
        _TOMBSTONE_SEQUENCE_CHECK,
        "capsules",
        _TOMBSTONE_SEQUENCE_CHECK_SQL,
    )
    op.create_unique_constraint(
        _TOMBSTONE_SEQUENCE_UNIQUE,
        "capsules",
        ["recipient_user_id", "tombstone_sequence"],
    )


def downgrade() -> None:
    op.drop_constraint(_TOMBSTONE_SEQUENCE_UNIQUE, "capsules", type_="unique")
    op.drop_constraint(_TOMBSTONE_SEQUENCE_CHECK, "capsules", type_="check")
    op.drop_constraint(_TOMBSTONE_FIELDS_CHECK, "capsules", type_="check")
    op.drop_column("capsules", "revoked_at")
    op.drop_column("capsules", "tombstone_sequence")
    op.drop_table(_COUNTER_TABLE)
