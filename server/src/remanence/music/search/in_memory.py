"""Fixture-backed in-memory search implementing the intent ranking.

NON-PRODUCTION TEST-ONLY reference ranker (F2 known limitation): it mirrors
the live ranking intent (section 12) for deterministic fixture tests, but it
is not the production ranking. Production ranking is defined by
``document.meilisearch_index_settings`` plus the variant-aware
``canonicalRank:asc`` sort built by the live adapter's request payload.
Do not wire this class outside tests/dev fixtures.
"""

from __future__ import annotations

from remanence.music.domain import MusicTrack, TrackSearchResult, normalize_text
from remanence.music.ports import (
    SEARCH_LIMIT_DEFAULT,
    SEARCH_LIMIT_MAX,
    MusicSearchError,
)
from remanence.music.search.document import VARIANT_TOKENS

_VARIANT_TOKENS = VARIANT_TOKENS


def _tokens(query: str) -> tuple[str, ...]:
    return tuple(t for t in normalize_text(query).split(" ") if t)


class InMemoryMusicSearch:
    """Deterministic port implementation for tests and TEST-mode fallback."""

    def __init__(self, tracks: tuple[MusicTrack, ...] | list[MusicTrack] = ()) -> None:
        cleaned: list[MusicTrack] = []
        for track in tracks:
            if not isinstance(track, MusicTrack):
                raise TypeError("InMemoryMusicSearch requires MusicTrack fixtures")
            cleaned.append(track)
        self._tracks = tuple(cleaned)

    def __len__(self) -> int:
        return len(self._tracks)

    def search(self, query: str, limit: int) -> list[TrackSearchResult]:
        if type(query) is not str or not query.strip():
            raise MusicSearchError("invalid query")
        if type(limit) is not int or not 1 <= limit <= SEARCH_LIMIT_MAX:
            raise MusicSearchError("invalid limit")
        wants_variant = any(tok in _tokens(query) for tok in _VARIANT_TOKENS)
        scored: list[tuple[tuple[int, int, int, int], MusicTrack]] = []
        for track in self._tracks:
            score = _score(track, query)
            if score is None:
                continue
            exact, prefix, canonical_boost, variant_penalty = score
            if wants_variant and track.version is not None:
                # Query names a variant: do not demote versioned recordings.
                variant_penalty = 0
            scored.append(((exact, prefix, canonical_boost, variant_penalty), track))
        # Higher is better for the first three; variant_penalty is negative.
        scored.sort(key=lambda item: item[0], reverse=True)
        return [TrackSearchResult.from_track(t) for _, t in scored[:limit]]


def _score(track: MusicTrack, query: str) -> tuple[int, int, int, int] | None:
    toks = _tokens(query)
    if not toks:
        return None
    title = track.normalized_title
    artists = [normalize_text(a) for a in track.artists]
    haystack = " ".join([title, *artists, normalize_text(track.version or "")])
    # Every query token must appear somewhere (AND semantics for fixtures).
    if any(tok not in haystack for tok in toks):
        return None
    joined = " ".join(toks)
    exact = 0
    if joined == title or any(joined == a for a in artists):
        exact = 3
    elif title == toks[0] or any(a == toks[0] for a in artists):
        exact = 2
    elif f"{artists[0]} {title}" == joined or f"{title} {artists[0]}" == joined:
        exact = 2
    prefix = 0
    if title.startswith(toks[0]) or any(a.startswith(toks[0]) for a in artists):
        prefix = 1
    canonical_boost = 1 if track.is_canonical else 0
    variant_penalty = -1 if track.version is not None else 0
    return (exact, prefix, canonical_boost, variant_penalty)


__all__ = ["SEARCH_LIMIT_DEFAULT", "InMemoryMusicSearch"]
