"""Own Meilisearch document mapping (ARCHITECTURE-v1 section 11).

We never index raw MusicBrainz structures. The indexed document is built
from our own :class:`MusicTrack`; its ``id`` is the own ``RemanenceTrackId``
(UUID text). The search engine knows nothing about capsules or users.
"""

from __future__ import annotations

from typing import Any

from remanence.music.domain import MusicTrack, normalize_text, parse_remanence_track_id

# Versioned index UID so a staging revision (music_rev_N+1) can be built
# beside the active one and switched atomically (section 6).
MEILISEARCH_INDEX_UID = "remanence_tracks_v1"

# Variant tokens shared by the TEST-ONLY in-memory ranker and the live
# Meilisearch request builder. A query containing one of these names an
# explicit recording variant, so canonical-first ordering must be lifted.
VARIANT_TOKENS = ("live", "remix", "cover", "remaster", "acoustic", "demo")

# Numeric canonical rank (R1): Meilisearch cannot sort on a boolean field,
# so canonical-first ordering is expressed as an integer. 0 marks a
# canonical/default recording, 1 marks a variant (live/remix/cover/...).
# Lower sorts first via ``canonicalRank:asc``. Derived purely from
# ``MusicTrack.is_canonical`` — a search-index concern, never domain identity.
CANONICAL_RANK_DEFAULT = 0
CANONICAL_RANK_VARIANT = 1

# Explicit allow-list of fields the live adapter requests back. The index
# must never leak provider-internal fields to the API layer (D12).
SEARCH_ATTRIBUTES_TO_RETRIEVE = (
    "id",
    "title",
    "artists",
    "version",
    "release",
    "year",
    "durationMs",
    "isCanonical",
    "canonicalRank",
    "hasArtwork",
)

DOCUMENT_FIELDS = frozenset(
    {
        "id",
        "title",
        "normalizedTitle",
        "artists",
        "normalizedArtists",
        "version",
        "release",
        "year",
        "durationMs",
        "isCanonical",
        "canonicalRank",
        "hasArtwork",
    }
)


def canonical_rank(track: MusicTrack) -> int:
    """Numeric canonical rank for the live index (R1, ARCH section 12)."""
    if not isinstance(track, MusicTrack):
        raise TypeError("canonical_rank requires MusicTrack")
    if type(track.is_canonical) is not bool:
        raise TypeError("canonical_rank requires bool is_canonical")
    return CANONICAL_RANK_DEFAULT if track.is_canonical else CANONICAL_RANK_VARIANT


def build_search_document(track: MusicTrack) -> dict[str, Any]:
    """Build the own search document for ``track``."""
    if not isinstance(track, MusicTrack):
        raise TypeError("build_search_document requires MusicTrack")
    return {
        "id": str(parse_remanence_track_id(track.id)),
        "title": track.title,
        "normalizedTitle": track.normalized_title,
        "artists": list(track.artists),
        "normalizedArtists": [normalize_text(a) for a in track.artists],
        "version": track.version,
        "release": track.release,
        "year": track.year,
        "durationMs": track.duration_ms,
        "isCanonical": bool(track.is_canonical),
        "canonicalRank": canonical_rank(track),
        "hasArtwork": bool(track.has_artwork),
    }


def parse_search_document_id(document: dict[str, Any]):
    """Extract and validate the own track id from a stored document/hit."""
    if type(document) is not dict:
        raise ValueError("invalid search document")
    raw = document.get("id")
    # Fail closed: only a canonical own UUID is accepted here. An MBID or a
    # Spotify ID in this position is a provider leak and must be rejected.
    return parse_remanence_track_id(raw)


def meilisearch_index_settings() -> dict[str, Any]:
    """Index settings for intent-oriented ranking (section 12).

    Ranking intent: exact title + exact artist first, then prefix, then
    typo/fuzzy; canonical/default recordings rank above live/remix/cover
    variants unless the query names the variant.

    Live ranking contract (R1, ARCH section 12): ``version`` is searchable
    so variant queries (e.g. "505 live") match the variant; ``canonicalRank``
    is the numeric sortable behind canonical-first ordering because
    Meilisearch cannot sort on the boolean ``isCanonical`` field.
    ``isCanonical`` stays filterable/stored but is deliberately NOT
    sortable — sorting on it is the revoked F2 contract. The TEST-ONLY
    in-memory ranker mirrors this intent for fixtures and is explicitly
    non-production (see its docstring).
    """
    return {
        "searchableAttributes": ["title", "artists", "version", "release"],
        "filterableAttributes": ["isCanonical", "hasArtwork", "year"],
        "sortableAttributes": ["canonicalRank", "year"],
        "rankingRules": [
            "words",
            "typo",
            "proximity",
            "attribute",
            "sort",
            "exactness",
        ],
        "typoTolerance": {"enabled": True, "minWordSizeForTypos": {"oneTypo": 4, "twoTypos": 8}},
    }
