"""Music staging schema: ARCH section 7 tables in the ``music`` schema.

Code-only: these models define the DDL contract for a future Alembic
revision. Nothing here connects, migrates, or writes on import. The
models live on a dedicated ``MusicBase`` (NOT the app ``Base`` used by
``migrations/env.py``) so the existing migration chain and its tests are
unaffected until a real revision is authored and reviewed.

Duration rule (postflight decision, no rescan): ``duration_ms`` is
nullable/unknown. NULL means unknown. Zero is forbidden at both the
domain layer and by CHECK constraint — never invent a 0 duration.

Artist identity rule (homonym-safe): artist rows are keyed by the
authoritative MusicBrainz artist MBID, never by display name. The staging
id is ``UUIDv5(namespace, "musicbrainz:artist:<mbid>")`` — deterministic
across reloads, distinct per MBID, own-UUID like track identity (ARCH
section 8 pattern). Display names are attributes only: two different
artists sharing one normalized name (homonyms) are two separate rows.
The previous name-derived identity (UUIDv5 over the normalized name plus
a uniqueness constraint on it) is retired — it conflated distinct artists
and violated the STOP condition. A future multi-source catalog can add
parallel ``<source>:artist:<id>`` key forms under the same namespace.
"""

from __future__ import annotations

import enum
import uuid
from datetime import datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    PrimaryKeyConstraint,
    String,
    Text,
    Uuid,
    func,
)
from sqlalchemy import Enum as SaEnum
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# NOTE: Uuid (not UUID) deliberately. UUID renders as bare UUID on
# SQLite, which assigns NUMERIC affinity and corrupts 32-digit hex ids
# into floats. Uuid renders native UUID on Postgres and CHAR(32) hex
# elsewhere, so the same models run on both. Same rationale as
# native_enum=False for the status enum below.
MUSIC_SCHEMA = "music"


class MusicBase(DeclarativeBase):
    pass


class TrackStatus(str, enum.Enum):
    """Staging track lifecycle. Only ACTIVE is written by the loader."""

    ACTIVE = "ACTIVE"


class StagedTrack(MusicBase):
    """ARCH section 7 ``music_track``."""

    __tablename__ = "music_track"
    __table_args__ = (
        CheckConstraint(
            "duration_ms IS NULL OR duration_ms > 0",
            name="ck_music_track_duration_positive",
        ),
        CheckConstraint(
            "first_release_year IS NULL OR (first_release_year BETWEEN 1000 AND 9999)",
            name="ck_music_track_year_range",
        ),
        {"schema": MUSIC_SCHEMA},
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    normalized_title: Mapped[str] = mapped_column(Text, nullable=False)
    duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    variant: Mapped[str | None] = mapped_column(Text, nullable=True)
    first_release_year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    status: Mapped[TrackStatus] = mapped_column(
        SaEnum(TrackStatus, name="music_track_status", native_enum=False),
        nullable=False,
        default=TrackStatus.ACTIVE,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )


class StagedArtist(MusicBase):
    """ARCH section 7 ``music_artist``, MBID-keyed (homonyms preserved).

    One row per artist MBID. The index on ``normalized_name`` exists only
    for name lookups — it asserts nothing about identity, and duplicate
    normalized names across rows are legal and expected.
    """

    __tablename__ = "music_artist"
    __table_args__ = (
        Index(
            "ix_music_artist_normalized_name", "normalized_name"
        ),
        {"schema": MUSIC_SCHEMA},
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    normalized_name: Mapped[str] = mapped_column(Text, nullable=False)


class StagedTrackArtist(MusicBase):
    """ARCH section 7 ``music_track_artist`` (ordered credit membership)."""

    __tablename__ = "music_track_artist"
    __table_args__ = (
        PrimaryKeyConstraint(
            "track_id", "artist_id", "position",
            name="pk_music_track_artist",
        ),
        CheckConstraint("position >= 0", name="ck_music_track_artist_position_order"),
        {"schema": MUSIC_SCHEMA},
    )

    track_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey(f"{MUSIC_SCHEMA}.music_track.id"), nullable=False
    )
    artist_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey(f"{MUSIC_SCHEMA}.music_artist.id"), nullable=False
    )
    position: Mapped[int] = mapped_column(Integer, nullable=False)


class StagedExternalId(MusicBase):
    """ARCH section 7 ``music_external_id``.

    Composite primary key (track, namespace, external_id) makes reloads
    idempotent: the same identity reloaded is the same row. No cross-track
    uniqueness is enforced — one ISRC attached to two tracks stays two
    rows (ISRC collisions are never merged; see ARCH section 9).
    """

    __tablename__ = "music_external_id"
    __table_args__ = (
        PrimaryKeyConstraint(
            "track_id", "namespace", "external_id",
            name="pk_music_external_id",
        ),
        {"schema": MUSIC_SCHEMA},
    )

    track_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey(f"{MUSIC_SCHEMA}.music_track.id"), nullable=False
    )
    namespace: Mapped[str] = mapped_column(String(64), nullable=False)
    external_id: Mapped[str] = mapped_column(String(256), nullable=False)
    source: Mapped[str] = mapped_column(String(64), nullable=False, default="musicbrainz")
    confidence: Mapped[str] = mapped_column(String(32), nullable=False, default="exact")
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
