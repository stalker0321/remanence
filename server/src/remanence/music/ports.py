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


class MusicSearchError(RuntimeError):
    """Search failed (maps to 500 INTERNAL_ERROR, fixed detail)."""


class MusicSearchUnavailableError(MusicSearchError):
    """Search backend unreachable (maps to 503 INTERNAL_UNAVAILABLE)."""


class MusicSearchPort(Protocol):
    def search(self, query: str, limit: int) -> list[TrackSearchResult]:
        """Return up to ``limit`` hits for ``query`` (sync, thread-safe)."""
        ...
