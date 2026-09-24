"""Meilisearch adapter contract tests (no live server required).

The real HTTP adapter is exercised with a stubbed ``urlopen`` transport so
these tests verify the wire contract (URL, payload, header, response
mapping, fail-closed identity) without Docker, production containers, or
the MusicBrainz dump. Live integration tests are opt-in via
``REMANENCE_MUSIC_MEILI_URL`` and use only the isolated port.
"""

import json
import os
import time
import urllib.error

import pytest

from remanence.music.ports import MusicSearchError, MusicSearchUnavailableError
from remanence.music.search.document import (
    MEILISEARCH_INDEX_UID,
    SEARCH_ATTRIBUTES_TO_RETRIEVE,
)
from remanence.music.search.meilisearch import (
    ISOLATED_MEILISEARCH_URL,
    MEILI_MAX_RESPONSE_BYTES,
    MeilisearchConfig,
    MeilisearchMusicSearch,
    build_search_request_payload,
)


def test_isolated_default_port_avoids_production_and_remanence_ports() -> None:
    assert "17770" in ISOLATED_MEILISEARCH_URL
    assert "7700" not in ISOLATED_MEILISEARCH_URL
    assert ":8000" not in ISOLATED_MEILISEARCH_URL
    assert ":55432" not in ISOLATED_MEILISEARCH_URL
    with pytest.raises(ValueError):
        MeilisearchConfig(base_url="http://127.0.0.1:7700")
    assert MeilisearchConfig().index_uid == MEILISEARCH_INDEX_UID


def test_port_guard_rejects_non_integer_missing_and_out_of_range() -> None:
    with pytest.raises(ValueError):
        MeilisearchConfig(base_url="http://127.0.0.1:notaport")
    with pytest.raises(ValueError):
        MeilisearchConfig(base_url="http://127.0.0.1")
    with pytest.raises(ValueError):
        MeilisearchConfig(base_url="http://127.0.0.1:0")
    with pytest.raises(ValueError):
        MeilisearchConfig(base_url="http://127.0.0.1:99999")
    with pytest.raises(ValueError):
        MeilisearchConfig(base_url="not-a-url")
    assert MeilisearchConfig(base_url="http://127.0.0.1:17770").base_url.endswith(":17770")


def test_config_guards_api_key_and_timeout() -> None:
    with pytest.raises(ValueError):
        MeilisearchConfig(api_key=123)
    with pytest.raises(ValueError):
        MeilisearchConfig(timeout_s=True)
    with pytest.raises(ValueError):
        MeilisearchConfig(timeout_s=0)
    with pytest.raises(ValueError):
        MeilisearchConfig(timeout_s=-1.0)
    assert MeilisearchConfig(timeout_s=2).timeout_s == 2


def test_search_payload_contract() -> None:
    payload = build_search_request_payload(" 505 ", 5)
    assert payload["q"] == "505"
    assert payload["limit"] == 5
    assert payload["attributesToRetrieve"] == list(SEARCH_ATTRIBUTES_TO_RETRIEVE)
    with pytest.raises(MusicSearchError):
        build_search_request_payload("   ", 5)
    with pytest.raises(MusicSearchError):
        build_search_request_payload("505", 0)


def test_search_payload_sorts_canonical_first_unless_variant_named() -> None:
    bare = build_search_request_payload("505 arctic monkeys", 10)
    assert bare["sort"] == ["canonicalRank:asc"]
    variant = build_search_request_payload("505 live", 10)
    assert "sort" not in variant
    remix = build_search_request_payload("505 REMIX", 10)
    assert "sort" not in remix


class _FakeResponse:
    def __init__(self, payload: dict) -> None:
        self._raw = json.dumps(payload).encode("utf-8")

    def read(self, size: int = -1) -> bytes:
        if size is not None and size >= 0:
            return self._raw[:size]
        return self._raw

    def __enter__(self):
        return self

    def __exit__(self, *args) -> bool:
        return False


def _stub_urlopen(monkeypatch, payload: dict, captured: dict):
    def fake_urlopen(request, timeout=None):
        captured["url"] = request.full_url
        captured["method"] = request.get_method()
        if request.data:
            captured["payload"] = json.loads(request.data.decode("utf-8"))
        captured["auth"] = request.get_header("Authorization")
        return _FakeResponse(payload)

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)


def test_search_maps_hits_to_own_ids(monkeypatch) -> None:
    captured: dict = {}
    payload = {
        "hits": [
            {
                "id": "be30e36b-1111-4111-8111-000000000001",
                "title": "505",
                "artists": ["Arctic Monkeys"],
                "version": None,
                "release": "Favourite Worst Nightmare",
                "year": 2007,
                "durationMs": 253000,
                "hasArtwork": True,
            }
        ]
    }
    _stub_urlopen(monkeypatch, payload, captured)
    adapter = MeilisearchMusicSearch(MeilisearchConfig(api_key="test-key"))
    hits = adapter.search("505 arctic monkeys", 10)
    assert captured["url"] == f"{ISOLATED_MEILISEARCH_URL}/indexes/{MEILISEARCH_INDEX_UID}/search"
    assert captured["method"] == "POST"
    assert captured["payload"]["q"] == "505 arctic monkeys"
    assert captured["payload"]["limit"] == 10
    assert captured["payload"]["sort"] == ["canonicalRank:asc"]
    assert captured["payload"]["attributesToRetrieve"] == list(SEARCH_ATTRIBUTES_TO_RETRIEVE)
    assert captured["auth"] == "Bearer test-key"
    assert len(hits) == 1
    assert hits[0].title == "505"
    assert str(hits[0].id) == "be30e36b-1111-4111-8111-000000000001"


def test_search_rejects_provider_id_in_id_slot(monkeypatch) -> None:
    captured: dict = {}
    _stub_urlopen(
        monkeypatch,
        {"hits": [{"id": "not-a-uuid", "title": "505", "artists": ["Arctic Monkeys"]}]},
        captured,
    )
    adapter = MeilisearchMusicSearch()
    with pytest.raises(MusicSearchError):
        adapter.search("505", 10)


def test_response_size_cap_fails_closed(monkeypatch) -> None:
    big_hits = {
        "hits": [
            {
                "id": "be30e36b-1111-4111-8111-000000000001",
                "title": "x" * 5000,
                "artists": ["Arctic Monkeys"],
            }
            for _ in range(200)
        ]
    }

    class _BigResponse(_FakeResponse):
        def __init__(self) -> None:
            self._raw = json.dumps(big_hits).encode("utf-8")
            assert len(self._raw) > MEILI_MAX_RESPONSE_BYTES

    def fake_urlopen(request, timeout=None):
        return _BigResponse()

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    with pytest.raises(MusicSearchError):
        MeilisearchMusicSearch().search("505", 10)


def test_connection_failure_maps_to_unavailable(monkeypatch) -> None:
    def failing(request, timeout=None):
        raise urllib.error.URLError("refused")

    monkeypatch.setattr("urllib.request.urlopen", failing)
    with pytest.raises(MusicSearchUnavailableError):
        MeilisearchMusicSearch().search("505", 10)


def test_index_helpers_hit_expected_urls_and_methods(monkeypatch) -> None:
    captured: dict = {}
    _stub_urlopen(monkeypatch, {"taskUid": 7}, captured)
    adapter = MeilisearchMusicSearch()
    assert adapter.update_settings({"searchableAttributes": ["title"]}) == 7
    assert captured["url"].endswith(f"/indexes/{MEILISEARCH_INDEX_UID}/settings")
    assert captured["method"] == "PATCH"
    assert adapter.put_documents([{"id": "be30e36b-1111-4111-8111-000000000001"}]) == 7
    assert captured["url"].endswith(f"/indexes/{MEILISEARCH_INDEX_UID}/documents")
    assert captured["method"] == "POST"


def test_live_ranking_canonical_first_and_variant_query() -> None:
    """Opt-in live proof (isolated port only): seed fixtures, assert order."""
    from remanence.music.search.document import (
        build_search_document,
        meilisearch_index_settings,
    )
    from remanence.music.search.fixtures import FIXTURE_TRACKS

    url = os.environ.get("REMANENCE_MUSIC_MEILI_URL")
    if not url:
        pytest.skip("REMANENCE_MUSIC_MEILI_URL is not set (isolated live test opt-in)")
    assert "7700" not in url, "live music tests must not target the production-default port"
    adapter = MeilisearchMusicSearch(MeilisearchConfig(base_url=url))
    adapter.delete_index()
    try:
        adapter.wait_for_task(adapter.update_settings(meilisearch_index_settings()))
        adapter.wait_for_task(
            adapter.put_documents([build_search_document(t) for t in FIXTURE_TRACKS])
        )
        deadline = time.monotonic() + 10.0
        bare: list = []
        while True:
            bare = adapter.search("505", 5)
            if len(bare) >= 2 or time.monotonic() > deadline:
                break
            time.sleep(0.2)
        assert len(bare) >= 2, "seeded live index must return the fixture variants"
        assert bare[0].title == "505"
        assert bare[0].version is None, "bare query must rank the canonical studio recording first"
        live = adapter.search("505 live", 5)
        assert live, "variant query must return hits"
        assert live[0].version == "Live at the Apollo"
    finally:
        adapter.delete_index()
