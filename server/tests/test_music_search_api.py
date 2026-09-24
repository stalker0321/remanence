"""Music search API contract tests (ARCHITECTURE-v1 sections 26-27).

Fixture-backed only: no database, no live Meilisearch, no dump. Auth is
overridden for success paths; the real bearer dependency is kept for the
401 path. All error bodies follow the shared application/problem+json
contract (fixed detail, no input echo).
"""

from __future__ import annotations

import json
import uuid

from fastapi.testclient import TestClient

from remanence.api.dependencies import get_authenticated_principal
from remanence.api.music import build_fixture_music_search
from remanence.api.problems import PROBLEM_CATALOG
from remanence.main import create_app
from remanence.music.domain import MusicTrack
from remanence.music.ports import MusicSearchUnavailableError
from remanence.music.search.in_memory import InMemoryMusicSearch
from remanence.settings import AppMode, Settings


def _authed(app) -> TestClient:
    app.dependency_overrides[get_authenticated_principal] = lambda: type(
        "P", (), {"user_id": uuid.uuid4(), "session_id": uuid.uuid4()}
    )()
    return TestClient(app)


def _app_with_fixtures(search=None) -> TestClient:
    app = create_app(
        settings=Settings(mode=AppMode.TEST),
        music_search=search if search is not None else build_fixture_music_search(),
    )
    return _authed(app)


def test_unwired_search_fails_closed_with_503_not_empty_200() -> None:
    """F1: an app without a wired search backend must not return 200 []."""
    app = create_app(settings=Settings(mode=AppMode.TEST))
    client = _authed(app)
    response = client.get("/music/v1/search", params={"q": "505"})
    assert response.status_code == 503, response.text
    body = response.json()
    assert body["code"] == "INTERNAL_ERROR"
    assert body["retryable"] is True
    assert body["detail"] == PROBLEM_CATALOG["INTERNAL_UNAVAILABLE"].detail


def test_search_returns_architecture_shaped_results() -> None:
    client = _app_with_fixtures()
    response = client.get("/music/v1/search", params={"q": "arctic monkeys 505"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert set(body) == {"results", "total", "offset"}
    assert body["offset"] == 0
    assert body["total"] == len(body["results"])
    assert body["results"], "fixture query must return hits"
    first = body["results"][0]
    assert set(first) == {
        "id",
        "title",
        "artists",
        "version",
        "release",
        "year",
        "durationMs",
        "artworkAvailable",
    }
    assert first["title"] == "505"
    assert first["artists"] == ["Arctic Monkeys"]
    assert first["release"] == "Favourite Worst Nightmare"
    assert first["year"] == 2007
    assert first["durationMs"] == 253000
    assert first["artworkAvailable"] is True
    serialized = json.dumps(body).lower()
    assert "musicbrainz" not in serialized
    assert "spotify" not in serialized
    assert "mbid" not in serialized


def test_search_requires_authentication() -> None:
    app = create_app(
        settings=Settings(mode=AppMode.TEST),
        music_search=build_fixture_music_search(),
    )
    client = TestClient(app)
    response = client.get("/music/v1/search", params={"q": "505"})
    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "AUTH_INVALID"


def test_search_validation_is_fixed_and_redacted() -> None:
    client = _app_with_fixtures()
    for params in (
        {"q": ""},
        {"q": "   "},
        {"limit": "5"},
        {"q": "x" * 101},
        {"q": "505", "limit": "0"},
        {"q": "505", "limit": "999"},
        {"q": "505", "unknown": "1"},
        {"q": "505", "offset": "-1"},
        {"q": "505", "offset": "201"},
        {"q": "505", "offset": "abc"},
        {"q": "505", "offset": ""},
        {"q": "505", "offset": "9999999999999999999999"},
        {"q": "505", "offset": "1.5"},
    ):
        response = client.get("/music/v1/search", params=params)
        assert response.status_code == 422, params
        body = response.json()
        assert body["code"] == "VALIDATION_FAILED"
        assert body["detail"] == PROBLEM_CATALOG["VALIDATION_FAILED"].detail
        assert "unknown" not in response.text
        assert "x" * 50 not in response.text


def test_search_limit_is_respected() -> None:
    client = _app_with_fixtures()
    response = client.get("/music/v1/search", params={"q": "505", "limit": "1"})
    assert response.status_code == 200
    assert len(response.json()["results"]) == 1


def test_search_unavailable_maps_to_retryable_503() -> None:
    class _Down:
        def search(self, query: str, limit: int, offset: int = 0):
            raise MusicSearchUnavailableError("down")

        def search_with_total(self, query: str, limit: int, offset: int = 0):
            raise MusicSearchUnavailableError("down")

    client = _app_with_fixtures(search=_Down())
    response = client.get("/music/v1/search", params={"q": "505"})
    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "INTERNAL_ERROR"
    assert body["retryable"] is True
    assert "down" not in response.text


def test_search_unknown_route_and_method_keep_problem_contract() -> None:
    client = _app_with_fixtures()
    missing = client.get("/music/v1/tracks/does-not-exist-here")
    assert missing.status_code == 404
    assert missing.json()["code"] == "ROUTE_NOT_FOUND"
    wrong = client.post("/music/v1/search", params={"q": "505"})
    assert wrong.status_code == 405
    assert wrong.json()["code"] == "METHOD_NOT_ALLOWED"


def test_search_accepts_utf8_cyrillic_query() -> None:
    """F3: percent-encoded Cyrillic must reach the port intact."""
    track = MusicTrack.create(title="Группа крови", artists=("Кино",))
    client = _app_with_fixtures(search=InMemoryMusicSearch((track,)))
    response = client.get("/music/v1/search", params={"q": "Кино группа крови"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert len(body["results"]) == 1
    assert body["results"][0]["title"] == "Группа крови"
    assert body["results"][0]["artists"] == ["Кино"]


def test_search_accepts_utf8_accented_latin_query() -> None:
    """F3: accented Latin must not be rejected as invalid."""
    track = MusicTrack.create(title="Café del Mar", artists=("Beyoncé Tribute",))
    client = _app_with_fixtures(search=InMemoryMusicSearch((track,)))
    response = client.get("/music/v1/search", params={"q": "café beyoncé"})
    assert response.status_code == 200, response.text
    assert response.json()["results"][0]["title"] == "Café del Mar"


def test_search_default_offset_zero_preserves_legacy_results() -> None:
    client = _app_with_fixtures()
    implicit = client.get("/music/v1/search", params={"q": "505", "limit": "10"})
    explicit = client.get(
        "/music/v1/search", params={"q": "505", "limit": "10", "offset": "0"}
    )
    assert implicit.status_code == explicit.status_code == 200
    assert implicit.json() == explicit.json()
    assert implicit.json()["offset"] == 0


def test_search_total_and_deterministic_pagination() -> None:
    client = _app_with_fixtures()
    full = client.get("/music/v1/search", params={"q": "505", "limit": "10"})
    assert full.status_code == 200
    full_body = full.json()
    assert full_body["total"] == 4
    assert len(full_body["results"]) == 4
    first = client.get("/music/v1/search", params={"q": "505", "limit": "2", "offset": "0"})
    second = client.get("/music/v1/search", params={"q": "505", "limit": "2", "offset": "2"})
    assert first.status_code == second.status_code == 200
    first_body, second_body = first.json(), second.json()
    assert (first_body["total"], second_body["total"]) == (4, 4)
    assert (first_body["offset"], second_body["offset"]) == (0, 2)
    assert [item["id"] for item in first_body["results"]] + [
        item["id"] for item in second_body["results"]
    ] == [item["id"] for item in full_body["results"]]
    assert not {item["id"] for item in first_body["results"]} & {
        item["id"] for item in second_body["results"]
    }
    past_end = client.get(
        "/music/v1/search", params={"q": "505", "limit": "2", "offset": "4"}
    )
    assert past_end.status_code == 200
    assert past_end.json()["results"] == []
    assert past_end.json()["total"] == 4


def test_search_cyrillic_reports_total() -> None:
    track = MusicTrack.create(title="Группа крови", artists=("Кино",))
    client = _app_with_fixtures(search=InMemoryMusicSearch((track,)))
    response = client.get("/music/v1/search", params={"q": "кино"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total"] == 1 and body["offset"] == 0
    assert body["results"][0]["title"] == "Группа крови"


def test_search_homonyms_both_returned_with_total() -> None:
    """Same title/artist display name, distinct ids: no merging, total 2."""
    first = MusicTrack.create(title="Song", artists=("Kate",))
    second = MusicTrack.create(title="Song", artists=("Kate",))
    assert first.id != second.id
    client = _app_with_fixtures(search=InMemoryMusicSearch((first, second)))
    response = client.get("/music/v1/search", params={"q": "song kate"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total"] == 2
    assert {item["id"] for item in body["results"]} == {
        str(first.id), str(second.id),
    }
