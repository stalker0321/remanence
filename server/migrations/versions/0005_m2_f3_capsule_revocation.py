"""Add terminal capsule revocation state for M2-F3."""

import sqlalchemy as sa
from alembic import op


revision = "0005_m2_f3_capsule_revocation"
down_revision = "0004_r1_publication_order"
branch_labels = None
depends_on = None


_STATE_CHECK = "ck_capsules_state_finalization_shape"
_OLD_STATE_CHECK_SQL = (
    "((state = 'READY' AND ready_at IS NOT NULL AND signed_statement IS NOT NULL "
    "AND signed_statement_sha256 IS NOT NULL AND publish_signature IS NOT NULL) OR "
    "(state IN ('DRAFT', 'ABORTED') AND ready_at IS NULL "
    "AND signed_statement IS NULL AND signed_statement_sha256 IS NULL "
    "AND publish_signature IS NULL))"
)
_NEW_STATE_CHECK_SQL = (
    "((state IN ('READY', 'REVOKED') AND ready_at IS NOT NULL "
    "AND signed_statement IS NOT NULL AND signed_statement_sha256 IS NOT NULL "
    "AND publish_signature IS NOT NULL) OR "
    "(state IN ('DRAFT', 'ABORTED') AND ready_at IS NULL "
    "AND signed_statement IS NULL AND signed_statement_sha256 IS NULL "
    "AND publish_signature IS NULL))"
)


def upgrade() -> None:
    op.execute(sa.text("ALTER TYPE capsule_state ADD VALUE 'REVOKED'"))
    op.drop_constraint(_STATE_CHECK, "capsules", type_="check")
    op.create_check_constraint(_STATE_CHECK, "capsules", _NEW_STATE_CHECK_SQL)


def downgrade() -> None:
    bind = op.get_bind()
    has_revoked = bind.scalar(
        sa.text("SELECT EXISTS (SELECT 1 FROM capsules WHERE state = 'REVOKED')")
    )
    if has_revoked:
        raise RuntimeError("cannot downgrade capsule revocation while revoked rows exist")

    op.drop_constraint(_STATE_CHECK, "capsules", type_="check")
    op.execute(sa.text("ALTER TYPE capsule_state RENAME TO capsule_state_revoked"))
    op.execute(sa.text("CREATE TYPE capsule_state AS ENUM ('DRAFT', 'READY', 'ABORTED')"))
    op.execute(
        sa.text(
            "ALTER TABLE capsules ALTER COLUMN state TYPE capsule_state "
            "USING state::text::capsule_state"
        )
    )
    op.execute(sa.text("DROP TYPE capsule_state_revoked"))
    op.create_check_constraint(_STATE_CHECK, "capsules", _OLD_STATE_CHECK_SQL)
