"""Sample-DB evidence: migration head, counts, API search/pagination/MBID/ISRC.

Runs ONLY against an explicitly provided test database
(REMANENCE_TEST_DATABASE_URL); skips otherwise. Read-only except for
the tables the reviewed loader already filled — this file asserts state,
it never writes. Scoped to the isolated sample instance.
"""

from __future__ import annotations

import os
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session

from remanence.api.dependencies import get_authenticated_principal
from remanence.main import create_app
from remanence.music.ingestion.identity import (
    NAMESPACE_REMANENCE_MUSICBRAINZ_V1,
    _RECORDING_KEY_PREFIX,
)
from remanence.music.search.staging import PostgresStagingSearch
from remanence.settings import AppMode, Settings


def _db_url() -> str:
    url = os.environ.get("REMANENCE_TEST_DATABASE_URL")
    if not url:
        pytest.skip("REMANENCE_TEST_DATABASE_URL is not set")
    return url


def _engine():
    return create_engine(_db_url(), poolclass=None)


def _factory():
    engine = _engine()
    return PostgresStagingSearch(lambda: Session(engine)), engine


def _authed_client(search) -> TestClient:
    app = create_app(settings=Settings(mode=AppMode.TEST), music_search=search)
    app.dependency_overrides[get_authenticated_principal] = lambda: type(
        "P", (), {"user_id": uuid.uuid4(), "session_id": uuid.uuid4()}
    )()
    return TestClient(app)


def test_migration_head_0008() -> None:
    engine = _engine()
    with engine.connect() as connection:
        rows = connection.execute(text("select version_num from alembic_version")).fetchall()
    assert [row[0] for row in rows] == ["0008_music_staging"]
    engine.dispose()


def test_row_counts() -> None:
    engine = _engine()
    with engine.connect() as connection:
        tracks = connection.execute(text("select count(*) from music.music_track")).scalar()
        artists = connection.execute(text("select count(*) from music.music_artist")).scalar()
        external = connection.execute(text("select count(*) from music.music_external_id")).scalar()
        null_durations = connection.execute(
            text("select count(*) from music.music_track where duration_ms is null")
        ).scalar()
        zero_durations = connection.execute(
            text("select count(*) from music.music_track where duration_ms = 0")
        ).scalar()
    engine.dispose()
    assert tracks == 10712
    assert artists == 498
    assert external == 11645
    assert null_durations == 709
    assert zero_durations == 0


def _all_pages(client: TestClient, query: str, limit: int) -> tuple[list[dict], int]:
    seen: list[dict] = []
    offset = 0
    total: int | None = None
    while True:
        response = client.get(
            "/music/v1/search", params={"q": query, "limit": str(limit), "offset": str(offset)}
        )
        assert response.status_code == 200, response.text
        body = response.json()
        assert set(body) == {"results", "total", "offset"}
        assert body["offset"] == offset
        if total is None:
            total = body["total"]
        assert body["total"] == total
        if not body["results"]:
            break
        seen.extend(body["results"])
        offset += limit
        assert offset <= total + limit
    return seen, total if total is not None else 0


def test_api_search_deterministic_and_paginated() -> None:
    search, engine = _factory()
    try:
        with Session(engine) as session:
            title = session.execute(
                text(
                    "select title from music.music_track "
                    "group by title having count(*) >= 3 "
                    "order by title limit 1"
                )
            ).scalar()
        assert title, "sample should contain a repeated title"
        client = _authed_client(search)
        first, total = _all_pages(client, str(title), 1)
        second, _ = _all_pages(client, str(title), 1)
        assert total >= 3
        assert [hit["id"] for hit in first] == [hit["id"] for hit in second]
        assert len({hit["id"] for hit in first}) == len(first) == total
        direct, direct_total = search.search_with_total(str(title), 5, 0)
        assert direct_total == total
        assert [str(hit.id) for hit in direct] == [hit["id"] for hit in first[:5]]
    finally:
        engine.dispose()


def test_mbid_resolves_to_deterministic_track() -> None:
    search, engine = _factory()
    try:
        with Session(engine) as session:
            row = session.execute(
                text(
                    "select track_id, external_id from music.music_external_id "
                    "where namespace = 'musicbrainz_recording' "
                    "order by external_id limit 1"
                )
            ).fetchone()
        assert row is not None
        track_id, mbid = uuid.UUID(str(row[0])), str(row[1])
        expected = uuid.uuid5(NAMESPACE_REMANENCE_MUSICBRAINZ_V1, f"{_RECORDING_KEY_PREFIX}{mbid}")
        assert track_id == expected
        client = _authed_client(search)
        with Session(engine) as session:
            title = session.execute(
                text("select title from music.music_track where id = :id"),
                {"id": str(track_id)},
            ).scalar()
        assert title
        seen, _ = _all_pages(client, str(title), 20)
        assert str(track_id) in {hit["id"] for hit in seen}
    finally:
        engine.dispose()


def test_isrc_collision_unmerged() -> None:
    search, engine = _factory()
    try:
        with Session(engine) as session:
            codes = [
                row[0]
                for row in session.execute(
                    text(
                        "select external_id from music.music_external_id "
                        "where namespace = 'isrc' "
                        "group by external_id having count(*) > 1 "
                        "order by external_id limit 1"
                    )
                ).fetchall()
            ]
        assert codes, "expected at least one shared ISRC in the sample"
        code = str(codes[0])
        with Session(engine) as session:
            track_ids = sorted(
                str(row[0])
                for row in session.execute(
                    text(
                        "select track_id from music.music_external_id "
                        "where namespace = 'isrc' and external_id = :code "
                        "order by track_id"
                    ),
                    {"code": code},
                ).fetchall()
            )
        assert len(track_ids) >= 2
        client = _authed_client(search)
        found: set[str] = set()
        with Session(engine) as session:
            titles = {
                str(track_id): session.execute(
                    text("select title from music.music_track where id = :id"),
                    {"id": track_id},
                ).scalar()
                for track_id in track_ids
            }
        for track_id, title in titles.items():
            assert title
            seen, _ = _all_pages(client, str(title), 20)
            assert track_id in {hit["id"] for hit in seen}, track_id
            found.add(track_id)
        assert found == set(track_ids)
    finally:
        engine.dispose()
