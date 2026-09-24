"""M-S1 composition tests: opt-in DEV/TEST staging search, default 503, PROD refusal.

Uses sample-shaped v2 rows (ordered artist_mbids, nullable durations)
loaded into SQLite via the staging loader, then exercises the endpoint
through the composition path (Settings backend + existing session
factory). No real archive/sample, live DB/Meili/container, migration,
or deploy.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.api.dependencies import get_authenticated_principal
from remanence.main import create_app
from remanence.music.search.composition import build_music_search
from remanence.music.search.staging import PostgresStagingSearch
from remanence.music.staging.loader import load_staged_jsonl
from remanence.music.staging.models import MusicBase
from remanence.settings import AppMode, MusicSearchBackend, Settings

_T1 = "11111111-1111-4111-8111-111111111111"
_T2 = "22222222-2222-4222-8222-222222222222"
_T3 = "33333333-3333-4333-8333-333333333333"
_T4 = "44444444-4444-4444-8444-444444444444"
_T5 = "55555555-5555-4555-8555-555555555555"
_R1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
_R2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
_R3 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
_R4 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
_R5 = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
_AM1 = "99999999-9999-4999-8999-999999999999"
_KINO = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
_KATE_A = "22222222-2222-4222-8222-222222222222"
_KATE_B = "33333333-3333-4333-8333-333333333333"


def _v2_row(track_id, title, artists, artist_mbids, mbid, duration_ms):
    return {
        "track_id": track_id,
        "title": title,
        "artists": artists,
        "artist_mbids": artist_mbids,
        "recording_mbid": mbid,
        "isrcs": [],
        "duration_ms": duration_ms,
    }


def _fixture_rows() -> list[dict]:
    return [
        _v2_row(_T1, "505", ["Arctic Monkeys"], [_AM1], _R1, 253000),
        _v2_row(_T2, "505", ["Arctic Monkeys"], [_AM1], _R2, 261000),
        _v2_row(_T3, "Группа крови", ["Кино"], [_KINO], _R3, None),
        _v2_row(_T4, "Song", ["Kate"], [_KATE_A], _R4, 200000),
        _v2_row(_T5, "Song", ["Kate"], [_KATE_B], _R5, 210000),
    ]


def _engine_with_rows(tmp_path: Path):
    engine = create_engine(
        "sqlite://",
        execution_options={"schema_translate_map": {"music": None}},
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    MusicBase.metadata.create_all(engine)
    fixture = tmp_path / "v2.jsonl"
    with fixture.open("w", encoding="utf-8") as handle:
        for row in _fixture_rows():
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    loader_session = Session(engine)
    try:
        stats = load_staged_jsonl(loader_session, fixture)
    finally:
        loader_session.close()
    assert stats.rows_valid == 5, stats
    return engine


def _authed_client(app) -> TestClient:
    app.dependency_overrides[get_authenticated_principal] = lambda: type(
        "P", (), {"user_id": uuid.uuid4(), "session_id": uuid.uuid4()}
    )()
    return TestClient(app)


def test_default_disabled_stays_unwired_503(tmp_path: Path) -> None:
    settings = Settings(mode=AppMode.TEST)
    assert settings.music_search_backend is MusicSearchBackend.DISABLED
    assert build_music_search(settings) is None
    app = create_app(settings=settings)
    client = _authed_client(app)
    response = client.get("/music/v1/search", params={"q": "505"})
    assert response.status_code == 503, response.text
    assert response.json()["retryable"] is True


def test_test_composition_serves_staging_ranked_hits(tmp_path: Path) -> None:
    engine = _engine_with_rows(tmp_path)
    factory = lambda: Session(engine)  # noqa: E731
    settings = Settings(
        mode=AppMode.TEST, music_search_backend=MusicSearchBackend.POSTGRES_STAGING
    )
    search = build_music_search(settings, factory)
    assert isinstance(search, PostgresStagingSearch)
    # Via create_app auto-composition (no explicit music_search arg).
    app = create_app(settings=settings, session_factory=factory)
    client = _authed_client(app)
    first = client.get("/music/v1/search", params={"q": "505", "limit": "1", "offset": "0"})
    second = client.get("/music/v1/search", params={"q": "505", "limit": "1", "offset": "1"})
    assert first.status_code == second.status_code == 200, (first.text, second.text)
    first_body, second_body = first.json(), second.json()
    assert (first_body["total"], second_body["total"]) == (2, 2)
    assert (first_body["offset"], second_body["offset"]) == (0, 1)
    assert first_body["results"][0]["durationMs"] == 253000
    assert second_body["results"][0]["durationMs"] == 261000
    assert first_body["results"][0]["id"] != second_body["results"][0]["id"]
    # Cyrillic + homonym separation through the same composed app.
    cyrillic = client.get("/music/v1/search", params={"q": "кино группа крови"})
    assert cyrillic.status_code == 200, cyrillic.text
    assert cyrillic.json()["total"] == 1
    assert cyrillic.json()["results"][0]["artists"] == ["Кино"]
    homonyms = client.get("/music/v1/search", params={"q": "song kate"})
    assert homonyms.json()["total"] == 2


def test_dev_composition_serves_hits(tmp_path: Path) -> None:
    engine = _engine_with_rows(tmp_path)
    factory = lambda: Session(engine)  # noqa: E731
    settings = Settings(
        mode=AppMode.DEV,
        database_url="postgresql+psycopg://remanence:secret@127.0.0.1:55432/remanence",
        blob_root=str(tmp_path / "blobs"),
        music_search_backend=MusicSearchBackend.POSTGRES_STAGING,
    )
    search = build_music_search(settings, factory)
    assert isinstance(search, PostgresStagingSearch)
    app = create_app(settings=settings, session_factory=factory)
    client = _authed_client(app)
    response = client.get("/music/v1/search", params={"q": "505"})
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 2


def test_search_requires_auth_on_composed_app(tmp_path: Path) -> None:
    engine = _engine_with_rows(tmp_path)
    factory = lambda: Session(engine)  # noqa: E731
    settings = Settings(
        mode=AppMode.TEST, music_search_backend=MusicSearchBackend.POSTGRES_STAGING
    )
    app = create_app(settings=settings, session_factory=factory)
    response = TestClient(app).get("/music/v1/search", params={"q": "505"})
    assert response.status_code == 401


def _prod_settings() -> Settings:
    return Settings(
        mode=AppMode.PROD,
        database_url="postgresql+psycopg://remanence:secret@127.0.0.1:55432/remanence",
        blob_root="/var/lib/remanence/blobs",
        music_search_backend=MusicSearchBackend.DISABLED,
    )


def test_prod_builder_refuses_staging() -> None:
    prod = Settings(
        mode=AppMode.PROD,
        database_url="postgresql+psycopg://remanence:secret@127.0.0.1:55432/remanence",
        blob_root="/var/lib/remanence/blobs",
        music_search_backend=MusicSearchBackend.POSTGRES_STAGING,
    )
    with pytest.raises(ValueError):
        build_music_search(prod, lambda: None)  # type: ignore[return-value]


def test_prod_factory_refuses_wired_staging() -> None:
    def _factory():
        raise AssertionError("must not be called")

    with pytest.raises(ValueError):
        create_app(
            settings=_prod_settings(),
            music_search=PostgresStagingSearch(_factory),
        )


def test_prod_factory_refuses_staging_backend_even_unwired(tmp_path: Path) -> None:
    prod = Settings(
        mode=AppMode.PROD,
        database_url="postgresql+psycopg://remanence:secret@127.0.0.1:55432/remanence",
        blob_root="/var/lib/remanence/blobs",
        music_search_backend=MusicSearchBackend.POSTGRES_STAGING,
    )
    with pytest.raises(ValueError):
        create_app(settings=prod)


def test_invalid_config_fails_closed(tmp_path: Path) -> None:
    staging_test = Settings(
        mode=AppMode.TEST, music_search_backend=MusicSearchBackend.POSTGRES_STAGING
    )
    with pytest.raises(ValueError):
        build_music_search(staging_test, None)
    with pytest.raises(ValueError):
        build_music_search(staging_test, "not-a-factory")  # type: ignore[arg-type]
    # create_app without a factory must fail closed, not silently 503.
    with pytest.raises(ValueError):
        create_app(settings=staging_test)
    # Unknown backend string is rejected by Settings validation.
    with pytest.raises(Exception):
        Settings(mode=AppMode.TEST, music_search_backend="meili")  # type: ignore[arg-type]
