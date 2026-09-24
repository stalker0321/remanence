"""Music search port (ARCHITECTURE-v1 section 10).

``MusicSearchPort`` is the stable domain contract. Swapping Meilisearch for
Typesense/OpenSearch/Tantivy later must not change this interface, the API
schemas, or capsule code.
"""

from __future__ import annotations

from typing import Protocol

from remanence.music.domain import TrackSearchResult

# API-level bounds for the first slice. Search itself is provider-neutral;
# the bound only protects the endpoint from abuse/oversized responses.
SEARCH_QUERY_MAX_LENGTH = 100
SEARCH_LIMIT_DEFAULT = 10
SEARCH_LIMIT_MAX = 20
# Offset-after-ranking pages are O(n log n) per query: fine for the
# staging-sample scale, revisit (keyset/cursor) before 1M+ rows.
SEARCH_OFFSET_DEFAULT = 0
SEARCH_OFFSET_MAX = 200


class MusicSearchError(RuntimeError):
    """Search failed (maps to 500 INTERNAL_ERROR, fixed detail)."""


class MusicSearchUnavailableError(MusicSearchError):
    """Search backend unreachable (maps to 503 INTERNAL_UNAVAILABLE)."""


class MusicSearchPort(Protocol):
    def search(
        self, query: str, limit: int, offset: int = 0
    ) -> list[TrackSearchResult]:
        """Return one ranked page: up to ``limit`` hits past ``offset``.

        ``offset`` defaults to 0, so existing two-argument callers are
        unaffected. Ranking (and therefore page boundaries) must be
        deterministic for stable pagination.
        """

    def search_with_total(
        self, query: str, limit: int, offset: int = 0
    ) -> tuple[list[TrackSearchResult], int]:
        """Ranked page plus exact total match count (for endpoint pagination).

        Same bounds and errors as :meth:`search`. ``total`` counts every
        match, not just the returned page.
        """
        ...
