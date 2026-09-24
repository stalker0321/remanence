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
    assert set(body) == {"results"}
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
    for params in ({"q": ""}, {"q": "   "}, {"limit": "5"}, {"q": "x" * 101}, {"q": "505", "limit": "0"}, {"q": "505", "limit": "999"}, {"q": "505", "unknown": "1"}):
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
        def search(self, query: str, limit: int):
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
