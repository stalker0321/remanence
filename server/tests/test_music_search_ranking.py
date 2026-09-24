"""Fixture ranking tests (ARCHITECTURE-v1 section 12)."""

import pytest

from remanence.music.ports import MusicSearchError
from remanence.music.search.fixtures import FIXTURE_TRACKS
from remanence.music.search.in_memory import InMemoryMusicSearch


def _search() -> InMemoryMusicSearch:
    return InMemoryMusicSearch(FIXTURE_TRACKS)


def test_exact_artist_title_prefers_canonical_studio() -> None:
    hits = _search().search("arctic monkeys 505", 10)
    assert hits
    first = hits[0]
    assert first.title == "505"
    assert first.artists == ("Arctic Monkeys",)
    assert first.version is None
    assert first.release == "Favourite Worst Nightmare"


def test_bare_title_demotes_live_remix_variants() -> None:
    hits = _search().search("505", 10)
    titles = [(h.artists[0], h.version) for h in hits if h.title == "505"]
    assert titles[0] == ("Arctic Monkeys", None)
    versions = [v for _, v in titles[1:]]
    assert "Live at the Apollo" in versions or "Remix" in versions


def test_variant_query_surfaces_named_variant() -> None:
    hits = _search().search("505 live", 10)
    assert hits
    assert hits[0].version == "Live at the Apollo"


def test_unrelated_query_returns_empty_without_fake_hits() -> None:
    hits = _search().search("nonexistent song xyz", 10)
    assert hits == []


def test_invalid_query_and_limit_fail_closed() -> None:
    port = _search()
    with pytest.raises(MusicSearchError):
        port.search("   ", 10)
    with pytest.raises(MusicSearchError):
        port.search("505", 0)
    with pytest.raises(MusicSearchError):
        port.search("505", 999)
