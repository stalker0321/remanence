"""Index revision + activation ledger: music_index_revision/_activation.

Revision ID: 0009_music_index_revisions
Revises: 0008_music_staging

D4-B2b persisted state for INDEX_REVISIONS.md (design doc only so far):
one row per built revision candidate plus one row per activation
attempt, including the post_attempted sentinel and task UID that make
crash recovery unambiguous.

Concurrency note: at most one open flight (PENDING/SWAPPED/UNKNOWN)
per stable UID is enforced by a DB-level partial unique index, so a
check-then-insert race fails closed at the database instead of
forking two flights. The session-level advisory try-lock remains
deferred to the activation slice; it is not needed for these
ledger writes.
"""

import sqlalchemy as sa
from alembic import op

revision = "0009_music_index_revisions"
down_revision = "0008_music_staging"
branch_labels = None
depends_on = None

_SCHEMA = "music"

_OPEN_STATES = ("PENDING", "SWAPPED", "UNKNOWN")


def upgrade() -> None:
    op.create_table(
        "music_index_revision",
        sa.Column("rev", sa.Integer(), nullable=False),
        sa.Column("candidate_uid", sa.String(length=64), nullable=False),
        sa.Column("doc_count", sa.Integer(), nullable=False),
        sa.Column("settings_hash", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False, server_default="READY"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("rev"),
        sa.UniqueConstraint("candidate_uid", name="uq_music_index_revision_candidate_uid"),
        sa.CheckConstraint("doc_count >= 0", name="ck_music_index_revision_doc_count"),
        sa.CheckConstraint(
            "status IN ('READY', 'ACTIVE', 'SUPERSEDED', 'FAILED')",
            name="ck_music_index_revision_status",
        ),
        schema=_SCHEMA,
    )
    op.create_table(
        "music_index_activation",
        sa.Column("id", sa.Integer(), sa.Identity(), nullable=False),
        sa.Column("stable_uid", sa.String(length=64), nullable=False),
        sa.Column("partner_uid", sa.String(length=64), nullable=False),
        sa.Column("from_rev", sa.Integer(), nullable=True),
        sa.Column("to_rev", sa.Integer(), nullable=False),
        sa.Column("post_attempted", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("task_uid", sa.BigInteger(), nullable=True),
        sa.Column("state", sa.String(length=16), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.CheckConstraint("to_rev >= 0", name="ck_music_index_activation_to_rev"),
        sa.CheckConstraint(
            "(from_rev IS NULL OR from_rev >= 0)",
            name="ck_music_index_activation_from_rev",
        ),
        sa.CheckConstraint("task_uid IS NULL OR task_uid >= 0", name="ck_music_index_activation_task"),
        sa.CheckConstraint(
            "state IN ('PENDING', 'SWAPPED', 'CONFIRMED', 'FAILED', 'UNKNOWN')",
            name="ck_music_index_activation_state",
        ),
        schema=_SCHEMA,
    )
    open_states = ", ".join(f"'{state}'" for state in _OPEN_STATES)
    op.create_index(
        "uq_music_index_activation_open_flight",
        "music_index_activation",
        ["stable_uid"],
        unique=True,
        schema=_SCHEMA,
        postgresql_where=sa.text(f"state IN ({open_states})"),
        sqlite_where=sa.text(f"state IN ({open_states})"),
    )


def downgrade() -> None:
    op.drop_index(
        "uq_music_index_activation_open_flight",
        table_name="music_index_activation",
        schema=_SCHEMA,
    )
    op.drop_table("music_index_activation", schema=_SCHEMA)
    op.drop_table("music_index_revision", schema=_SCHEMA)
