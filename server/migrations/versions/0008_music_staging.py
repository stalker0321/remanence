"""Music staging schema: music_track, music_artist, music_track_artist, music_external_id.

Revision ID: 0008_music_staging
Revises: 0007_m2_f3_first_open_claim
Create Date: 2026-09-24

Mirrors src/remanence/music/staging/models.py exactly (ARCH section 7):
own-UUID primary keys, nullable-but-never-zero duration_ms, MBID-keyed
artists with a non-unique name lookup index (homonyms stay separate
rows), ordered credit membership, composite-key external identities with
no cross-track uniqueness (ISRC collisions unmerged). Status is a
non-native single-value enum, matching the models for SQLite/Postgres
parity. Code-only until reviewed and applied to a real database.
"""

import sqlalchemy as sa
from alembic import op

revision = "0008_music_staging"
down_revision = "0007_m2_f3_first_open_claim"
branch_labels = None
depends_on = None

_SCHEMA = "music"


def upgrade() -> None:
    op.execute("CREATE SCHEMA IF NOT EXISTS music")
    op.create_table(
        "music_track",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("normalized_title", sa.Text(), nullable=False),
        sa.Column("duration_ms", sa.Integer(), nullable=True),
        sa.Column("variant", sa.Text(), nullable=True),
        sa.Column("first_release_year", sa.Integer(), nullable=True),
        sa.Column(
            "status",
            sa.Enum("ACTIVE", name="music_track_status", native_enum=False),
            nullable=False,
        ),
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
        sa.CheckConstraint(
            "duration_ms IS NULL OR duration_ms > 0",
            name="ck_music_track_duration_positive",
        ),
        sa.CheckConstraint(
            "first_release_year IS NULL OR (first_release_year BETWEEN 1000 AND 9999)",
            name="ck_music_track_year_range",
        ),
        schema=_SCHEMA,
    )
    op.create_table(
        "music_artist",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("normalized_name", sa.Text(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        schema=_SCHEMA,
    )
    op.create_index(
        "ix_music_artist_normalized_name",
        "music_artist",
        ["normalized_name"],
        schema=_SCHEMA,
    )
    op.create_table(
        "music_track_artist",
        sa.Column("track_id", sa.Uuid(), nullable=False),
        sa.Column("artist_id", sa.Uuid(), nullable=False),
        sa.Column("position", sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint(
            "track_id", "artist_id", "position", name="pk_music_track_artist"
        ),
        sa.CheckConstraint(
            "position >= 0", name="ck_music_track_artist_position_order"
        ),
        sa.ForeignKeyConstraint(
            ["track_id"], ["music.music_track.id"]
        ),
        sa.ForeignKeyConstraint(
            ["artist_id"], ["music.music_artist.id"]
        ),
        schema=_SCHEMA,
    )
    op.create_table(
        "music_external_id",
        sa.Column("track_id", sa.Uuid(), nullable=False),
        sa.Column("namespace", sa.String(length=64), nullable=False),
        sa.Column("external_id", sa.String(length=256), nullable=False),
        sa.Column("source", sa.String(length=64), nullable=False),
        sa.Column("confidence", sa.String(length=32), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.PrimaryKeyConstraint(
            "track_id", "namespace", "external_id", name="pk_music_external_id"
        ),
        sa.ForeignKeyConstraint(
            ["track_id"], ["music.music_track.id"]
        ),
        schema=_SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("music_external_id", schema=_SCHEMA)
    op.drop_table("music_track_artist", schema=_SCHEMA)
    op.drop_index("ix_music_artist_normalized_name", "music_artist", schema=_SCHEMA)
    op.drop_table("music_artist", schema=_SCHEMA)
    op.drop_table("music_track", schema=_SCHEMA)
    op.execute("DROP SCHEMA IF EXISTS music")
