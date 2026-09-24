"""Staging search API integration: v2 JSONL -> SQLite -> endpoint.

Synthetic fixture only: v2-shaped rows (ordered artist_mbids, nullable
durations) loaded via the staging loader into SQLite, then queried
through BOTH the staging adapter directly and the real endpoint with an
injected PostgresStagingSearch (dependency override pattern — no
production wiring). Proves Cyrillic/Latin matching, homonym separation,
pagination totals, ISRC-collision nonmerge, and v1-row rejection.
No real archive/sample, live DB/Meili/container, migration, or deploy.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.api.dependencies import get_authenticated_principal
from remanence.main import create_app
from remanence.music.search.staging import PostgresStagingSearch
from remanence.music.staging.loader import load_staged_jsonl
from remanence.music.staging.models import MusicBase
from remanence.settings import AppMode, Settings

_T1 = "11111111-1111-4111-8111-111111111111"
_T2 = "22222222-2222-4222-8222-222222222222"
_T3 = "33333333-3333-4333-8333-333333333333"
_T4 = "44444444-4444-4444-8444-444444444444"
_T5 = "55555555-5555-4555-8555-555555555555"
_T6 = "66666666-6666-4666-8666-666666666666"
_R1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
_R2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
_R3 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
_R4 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
_R5 = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
_R6 = "ffffffff-ffff-4fff-8fff-ffffffffffff"
_AM1 = "99999999-9999-4999-8999-999999999999"
_KINO = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
_KATE_A = "22222222-2222-4222-8222-222222222222"
_KATE_B = "33333333-3333-4333-8333-333333333333"
_BEY = "12345678-1234-4123-8123-123456789012"


def _v2_row(
    track_id: str,
    title: str,
    artists: list[str],
    artist_mbids: list[str],
    mbid: str,
    isrcs: list[str],
    duration_ms: int | None,
) -> dict:
    return {
        "track_id": track_id,
        "title": title,
        "artists": artists,
        "artist_mbids": artist_mbids,
        "recording_mbid": mbid,
        "isrcs": isrcs,
        "duration_ms": duration_ms,
    }


def _fixture_rows() -> list[dict]:
    return [
        _v2_row(_T1, "505", ["Arctic Monkeys"], [_AM1], _R1, ["GBAYE0700505"], 253000),
        _v2_row(_T2, "505", ["Arctic Monkeys"], [_AM1], _R2, ["GBAYE0700505"], 261000),
        _v2_row(_T3, "Группа крови", ["Кино"], [_KINO], _R3, [], None),
        _v2_row(_T4, "Song", ["Kate"], [_KATE_A], _R4, [], 200000),
        _v2_row(_T5, "Song", ["Kate"], [_KATE_B], _R5, [], 210000),
        _v2_row(_T6, "Café del Mar", ["Beyoncé"], [_BEY], _R6, [], None),
        {
            "track_id": "77777777-7777-4777-8777-777777777777",
            "title": "V1 Ghost",
            "artists": ["Nobody"],
            "recording_mbid": "88888888-8888-4888-8888-888888888888",
            "isrcs": [],
        },
    ]


def _loaded_client(tmp_path: Path) -> tuple[TestClient, PostgresStagingSearch]:
    # StaticPool + check_same_thread=False: TestClient serves requests on
    # portal threads, so :memory: SQLite must share one connection.
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
    stats = load_staged_jsonl(loader_session, fixture)
    assert stats.rows_valid == 6, stats
    assert stats.invalid_reasons == {"missing_artist_mbids": 1}, stats.invalid_reasons
    loader_session.close()
    search = PostgresStagingSearch(lambda: Session(engine))
    app = create_app(settings=Settings(mode=AppMode.TEST), music_search=search)
    app.dependency_overrides[get_authenticated_principal] = lambda: type(
        "P", (), {"user_id": uuid.uuid4(), "session_id": uuid.uuid4()}
    )()
    return TestClient(app), search


def test_loader_rejects_v1_and_counts_mbids(tmp_path: Path) -> None:
    client, _ = _loaded_client(tmp_path)
    response = client.get("/music/v1/search", params={"q": "ghost nobody"})
    assert response.status_code == 200
    assert response.json()["total"] == 0


def test_endpoint_cyrillic_match(tmp_path: Path) -> None:
    client, _ = _loaded_client(tmp_path)
    response = client.get("/music/v1/search", params={"q": "кино группа крови"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total"] == 1 and body["offset"] == 0
    assert body["results"][0]["title"] == "Группа крови"
    assert body["results"][0]["artists"] == ["Кино"]
    assert body["results"][0]["durationMs"] is None


def test_endpoint_latin_pagination_total(tmp_path: Path) -> None:
    client, search = _loaded_client(tmp_path)
    first = client.get("/music/v1/search", params={"q": "505", "limit": "1", "offset": "0"})
    second = client.get("/music/v1/search", params={"q": "505", "limit": "1", "offset": "1"})
    assert first.status_code == second.status_code == 200
    first_body, second_body = first.json(), second.json()
    assert (first_body["total"], second_body["total"]) == (2, 2)
    assert (first_body["offset"], second_body["offset"]) == (0, 1)
    assert first_body["results"][0]["durationMs"] == 253000
    assert second_body["results"][0]["durationMs"] == 261000
    assert first_body["results"][0]["id"] != second_body["results"][0]["id"]
    direct, total = search.search_with_total("505", 1, 0)
    assert total == 2
    assert str(direct[0].id) == first_body["results"][0]["id"]


def test_endpoint_homonyms_separate_and_isrc_unmerged(tmp_path: Path) -> None:
    client, _ = _loaded_client(tmp_path)
    response = client.get("/music/v1/search", params={"q": "song kate"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total"] == 2
    ids = {item["id"] for item in body["results"]}
    assert ids == {_T4, _T5}
    # ISRC-collision pair stays two distinct tracks with correct durations.
    collision = client.get("/music/v1/search", params={"q": "505", "limit": "10"})
    assert collision.json()["total"] == 2
    durations = sorted(
        item["durationMs"] for item in collision.json()["results"]
    )
    assert durations == [253000, 261000]


def test_endpoint_past_end_empty_with_total(tmp_path: Path) -> None:
    client, _ = _loaded_client(tmp_path)
    response = client.get("/music/v1/search", params={"q": "café", "limit": "5", "offset": "10"})
    assert response.status_code == 200
    body = response.json()
    assert body["results"] == [] and body["total"] == 1 and body["offset"] == 10
