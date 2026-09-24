"""Remanence music bounded module (ARCHITECTURE-v1).

This package owns the provider-neutral music domain. Catalog sources
(MusicBrainz dump today, other feeds later), search backends (Meilisearch
today), artwork providers, and destination adapters all live behind ports.
Capsule code must only depend on the public domain/application surface here
and must never import provider identifiers (MBID, Spotify ID, ...).

Scope of the first slice is recorded in ``music/SCOPE.md``: domain/ports,
search document + Meilisearch wire adapter, and the authenticated search
API contract with fixture-backed tests. This slice is NOT a working real
catalog — real core ingestion and index-revision management are deferred.
"""

from remanence.music.domain import (
    ExternalIdentifier,
    MusicArtist,
    MusicTrack,
    TrackSearchResult,
    normalize_text,
    parse_remanence_track_id,
)

__all__ = [
    "ExternalIdentifier",
    "MusicArtist",
    "MusicTrack",
    "TrackSearchResult",
    "normalize_text",
    "parse_remanence_track_id",
]
