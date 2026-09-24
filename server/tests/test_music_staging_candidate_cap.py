"""Candidate-cap gate: staging search refuses overly broad queries fail-closed.

The ranker materializes candidates in Python, so the SQL prefilter returns
at most STAGING_SEARCH_CANDIDATE_CAP IDs (fetch CAP+1 to detect overflow).
Exceeding the cap raises MusicSearchUnavailableError → endpoint 503 with
no truncated page/total. Narrow queries on the same table still succeed.
Synthetic SQLite rows only; no real sample, live DB/Meili, or deploy.
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
from remanence.music.ports import MusicSearchUnavailableError
from remanence.music.search.staging import (
    STAGING_SEARCH_CANDIDATE_CAP,
    PostgresStagingSearch,
)
from remanence.music.staging.loader import load_staged_jsonl
from remanence.music.staging.models import MusicBase
from remanence.settings import AppMode, Settings

_N_ROWS = STAGING_SEARCH_CANDIDATE_CAP + 100


def _row(index: int) -> dict:
    return {
        "track_id": str(uuid.uuid4()),
        "title": f"Aaaa song ztoken{index:05d}",
        "artists": ["Artist"],
        "artist_mbids": [str(uuid.uuid4())],
        "recording_mbid": str(uuid.uuid4()),
        "isrcs": [],
        "duration_ms": 200000 + (index % 60_000),
    }


def _loaded_search(tmp_path: Path) -> PostgresStagingSearch:
    engine = create_engine(
        "sqlite://",
        execution_options={"schema_translate_map": {"music": None}},
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    MusicBase.metadata.create_all(engine)
    fixture = tmp_path / "broad.jsonl"
    with fixture.open("w", encoding="utf-8") as handle:
        for index in range(_N_ROWS):
            handle.write(json.dumps(_row(index), ensure_ascii=False) + "\n")
    session = Session(engine)
    try:
        stats = load_staged_jsonl(session, fixture)
    finally:
        session.close()
    assert stats.rows_valid == _N_ROWS, stats
    return PostgresStagingSearch(lambda: Session(engine))


def test_cap_constants_sane() -> None:
    assert STAGING_SEARCH_CANDIDATE_CAP == 5000


def test_broad_query_fails_closed_and_specific_succeeds(tmp_path: Path) -> None:
    search = _loaded_search(tmp_path)
    with pytest.raises(MusicSearchUnavailableError):
        search.search_with_total("a", 20, 0)
    hits, total = search.search_with_total("ztoken00001", 20, 0)
    assert total == 1 and len(hits) == 1

    app = create_app(settings=Settings(mode=AppMode.TEST), music_search=search)
    app.dependency_overrides[get_authenticated_principal] = lambda: type(
        "P", (), {"user_id": uuid.uuid4(), "session_id": uuid.uuid4()}
    )()
    client = TestClient(app)
    broad = client.get("/music/v1/search", params={"q": "a"})
    assert broad.status_code == 503, broad.text
    body = broad.json()
    assert body["retryable"] is True
    assert "results" not in body and "total" not in body
    specific = client.get("/music/v1/search", params={"q": "ztoken00002"})
    assert specific.status_code == 200, specific.text
    assert specific.json()["total"] == 1
