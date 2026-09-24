"""Meilisearch-backed search layer (ARCHITECTURE-v1 sections 10-12)."""

from remanence.music.search.document import (
    CANONICAL_RANK_DEFAULT,
    CANONICAL_RANK_VARIANT,
    MEILISEARCH_INDEX_UID,
    SEARCH_ATTRIBUTES_TO_RETRIEVE,
    build_search_document,
    canonical_rank,
    meilisearch_index_settings,
    parse_search_document_id,
)
from remanence.music.search.fixtures import FIXTURE_TRACKS
from remanence.music.search.in_memory import InMemoryMusicSearch
from remanence.music.search.meilisearch import (
    ISOLATED_MEILISEARCH_URL,
    MeilisearchConfig,
    MeilisearchMusicSearch,
    build_search_request_payload,
)

__all__ = [
    "CANONICAL_RANK_DEFAULT",
    "CANONICAL_RANK_VARIANT",
    "FIXTURE_TRACKS",
    "ISOLATED_MEILISEARCH_URL",
    "MEILISEARCH_INDEX_UID",
    "SEARCH_ATTRIBUTES_TO_RETRIEVE",
    "InMemoryMusicSearch",
    "MeilisearchConfig",
    "MeilisearchMusicSearch",
    "build_search_document",
    "build_search_request_payload",
    "canonical_rank",
    "meilisearch_index_settings",
    "parse_search_document_id",
]
