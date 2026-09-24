"""Music slice scope record tests (F5 + F4 mode guard + R1 contract + R2).

Guards that the slice never silently claims a working real catalog:
the SCOPE.md deferral record exists, fixtures stay test/dev-only, the
live ranking contract (version searchable, NUMERIC canonicalRank sortable,
variant-aware payload) holds at unit level, and production can never be
wired to fixture search. The live proof itself is opt-in in
``test_music_meilisearch_adapter.py``.
"""

from pathlib import Path

import pytest

from remanence.api.music import build_fixture_music_search
from remanence.main import create_app
from remanence.music.search.document import meilisearch_index_settings
from remanence.music.search.in_memory import InMemoryMusicSearch
from remanence.music.search.meilisearch import (
    MeilisearchMusicSearch,
    build_search_request_payload,
)
from remanence.settings import AppMode, Settings

_SCOPE = Path(__file__).resolve().parents[1] / "src" / "remanence" / "music" / "SCOPE.md"


def test_scope_record_exists_and_defers_ingestion() -> None:
    assert _SCOPE.is_file(), "music/SCOPE.md must record the slice scope"
    text = _SCOPE.read_text(encoding="utf-8")
    for phrase in (
        "ARCHITECTURE-v1",
        "NOT a working real catalog",
        "MusicBrainz",
        "revision",
        "TrackSnapshot",
    ):
        assert phrase in text, phrase
    assert "D4" in text and "D11" in text


def test_fixture_builder_is_test_dev_only() -> None:
    assert build_fixture_music_search().search("505", 5)
    assert build_fixture_music_search(mode=AppMode.DEV).search("505", 5)
    with pytest.raises(ValueError):
        build_fixture_music_search(mode=AppMode.PROD)


def test_live_ranking_contract_at_unit_level() -> None:
    settings = meilisearch_index_settings()
    assert "version" in settings["searchableAttributes"]
    # R1: numeric rank only — boolean isCanonical must not be sortable.
    assert "canonicalRank" in settings["sortableAttributes"]
    assert "isCanonical" not in settings["sortableAttributes"]
    assert build_search_request_payload("505", 5)["sort"] == ["canonicalRank:asc"]
    assert "sort" not in build_search_request_payload("505 live", 5)


def _prod_settings() -> Settings:
    return Settings(
        mode=AppMode.PROD,
        database_url="postgresql+psycopg://remanence:secret@127.0.0.1:55432/remanence",
        blob_root="/var/lib/remanence/blobs",
    )


def test_prod_factory_refuses_fixture_search_directly_constructed() -> None:
    """R2: wiring a hand-built InMemoryMusicSearch into PROD must fail."""
    with pytest.raises(ValueError):
        create_app(
            settings=_prod_settings(),
            music_search=InMemoryMusicSearch(()),
        )


def test_prod_factory_refuses_fixture_search_via_builder() -> None:
    """R2: the builder bypass (forgotten PROD mode) is closed at the factory."""
    with pytest.raises(ValueError):
        create_app(
            settings=_prod_settings(),
            music_search=build_fixture_music_search(),
        )


def test_prod_factory_allows_unwired_and_real_adapter() -> None:
    """R2 guard is narrow: unwired (503 at endpoint) and real ports pass."""
    create_app(settings=_prod_settings(), music_search=None)
    create_app(settings=_prod_settings(), music_search=MeilisearchMusicSearch())
    create_app(
        settings=Settings(mode=AppMode.TEST),
        music_search=build_fixture_music_search(),
    )
