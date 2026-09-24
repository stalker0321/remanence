"""Music staging package (ARCH sections 6-7, code-only).

``models`` defines the ``music.*`` DDL contract for a future Alembic
revision (never executed here). ``loader`` streams validated sample JSONL
into those tables idempotently. No connections are opened, no migrations
run, no search index is touched by this package.
"""

from remanence.music.staging.loader import (
    ARTIST_MBID_NAMESPACE_V1,
    LoadStats,
    LoaderError,
    RowInvalid,
    artist_id_for_mbid,
    load_staged_jsonl,
)
from remanence.music.staging.models import (
    MUSIC_SCHEMA,
    MusicBase,
    StagedArtist,
    StagedExternalId,
    StagedTrack,
    StagedTrackArtist,
    TrackStatus,
)

__all__ = [
    "ARTIST_MBID_NAMESPACE_V1",
    "MUSIC_SCHEMA",
    "LoadStats",
    "LoaderError",
    "MusicBase",
    "RowInvalid",
    "StagedArtist",
    "StagedExternalId",
    "StagedTrack",
    "StagedTrackArtist",
    "TrackStatus",
    "artist_id_for_mbid",
    "load_staged_jsonl",
]
