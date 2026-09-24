"""Per-user music search rate limit: 60/min, burst 10, 429 + Retry-After.

Fixture-backed only (no DB/Meili). The limiter runs inside the endpoint
body after auth, so unauthenticated requests fail 401 before any rate
accounting; invalid queries (422) and unwired backends (503) do not
consume budget.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

import remanence.api.music as music_api
from remanence.api.dependencies import get_authenticated_principal
from remanence.api.music import (
    _RATE_LIMIT_BURST,
    _RATE_LIMIT_MAX_USERS,
    _rate_limit_buckets,
    build_fixture_music_search,
    check_music_search_rate_limit,
    reset_music_search_rate_limiter,
)
from remanence.main import create_app
from remanence.settings import AppMode, Settings


@pytest.fixture(autouse=True)
def _clean_limiter():
    reset_music_search_rate_limiter()
    yield
    reset_music_search_rate_limiter()


def _principal(user_id: uuid.UUID):
    return type("P", (), {"user_id": user_id, "session_id": uuid.uuid4()})()


def _app_for(user_id: uuid.UUID) -> TestClient:
    app = create_app(
        settings=Settings(mode=AppMode.TEST),
        music_search=build_fixture_music_search(),
    )
    app.dependency_overrides[get_authenticated_principal] = lambda: _principal(user_id)
    return TestClient(app)


def test_burst_then_429_with_retry_after() -> None:
    client = _app_for(uuid.uuid4())
    for _ in range(_RATE_LIMIT_BURST):
        response = client.get("/music/v1/search", params={"q": "505"})
        assert response.status_code == 200, response.text
    limited = client.get("/music/v1/search", params={"q": "505"})
    assert limited.status_code == 429, limited.text
    assert limited.headers["content-type"].startswith("application/problem+json")
    body = limited.json()
    assert body["code"] == "RATE_LIMITED"
    assert body["retryable"] is True
    assert "Retry-After" in limited.headers
    assert int(limited.headers["Retry-After"]) >= 1


def test_refill_after_window(monkeypatch) -> None:
    import time as stdlib_time

    now = [1000.0]
    monkeypatch.setattr(stdlib_time, "monotonic", lambda: now[0])
    user = uuid.uuid4()
    for _ in range(_RATE_LIMIT_BURST):
        assert check_music_search_rate_limit(user)[0] is True
    assert check_music_search_rate_limit(user)[0] is False
    now[0] += 61.0
    allowed, _ = check_music_search_rate_limit(user)
    assert allowed is True


def test_per_user_separation() -> None:
    user_a, user_b = uuid.uuid4(), uuid.uuid4()
    client_a = _app_for(user_a)
    # Exhaust A's burst through HTTP (shares the process-global limiter).
    for _ in range(_RATE_LIMIT_BURST):
        assert client_a.get("/music/v1/search", params={"q": "505"}).status_code == 200
    assert client_a.get("/music/v1/search", params={"q": "505"}).status_code == 429
    # B is unaffected.
    app_b = create_app(
        settings=Settings(mode=AppMode.TEST),
        music_search=build_fixture_music_search(),
    )
    app_b.dependency_overrides[get_authenticated_principal] = lambda: _principal(user_b)
    client_b = TestClient(app_b)
    response = client_b.get("/music/v1/search", params={"q": "505"})
    assert response.status_code == 200, response.text


def test_unauthorized_precedes_rate_limit() -> None:
    app = create_app(
        settings=Settings(mode=AppMode.TEST),
        music_search=build_fixture_music_search(),
    )
    client = TestClient(app)
    for _ in range(_RATE_LIMIT_BURST + 5):
        response = client.get("/music/v1/search", params={"q": "505"})
        assert response.status_code == 401, response.text
        assert response.json()["code"] == "AUTH_INVALID"


def test_disabled_backend_stays_503_without_rate_limit() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: _principal(uuid.uuid4())
    client = TestClient(app)
    for _ in range(_RATE_LIMIT_BURST + 5):
        response = client.get("/music/v1/search", params={"q": "505"})
        assert response.status_code == 503, response.text
    assert music_api._rate_limit_buckets == {}


def test_memory_bounded_and_idle_evicted(monkeypatch) -> None:
    import time as stdlib_time

    now = [2000.0]
    monkeypatch.setattr(stdlib_time, "monotonic", lambda: now[0])
    users = [uuid.uuid4() for _ in range(_RATE_LIMIT_MAX_USERS + 500)]
    for user in users:
        check_music_search_rate_limit(user)
    assert len(_rate_limit_buckets) <= _RATE_LIMIT_MAX_USERS
    # Idle entries are evicted first: age everything out, add one more.
    now[0] += 601.0
    newcomer = uuid.uuid4()
    check_music_search_rate_limit(newcomer)
    assert len(_rate_limit_buckets) <= _RATE_LIMIT_MAX_USERS
    assert newcomer in _rate_limit_buckets
