"""Full catalog composition/HTTP contract; no live account DB or user secrets."""
import hashlib
import json
import sqlite3
import uuid

import pytest
from fastapi.testclient import TestClient

from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal
from remanence.main import create_app
from remanence.music.catalog_build import DDL
from remanence.music.ingestion.identity import track_id_for_recording
from remanence.music.ports import MusicSearchUnavailableError
from remanence.music.search.composition import build_music_search
from remanence.music.search.full_catalog import FullCatalogSearch
from remanence.settings import AppMode, Settings


@pytest.fixture
def catalog(tmp_path):
    path = tmp_path / "catalog?#.sqlite3"
    with sqlite3.connect(path) as db:
        db.executescript(DDL)
        for table in ("raw_artist", "raw_credit", "raw_recording", "raw_isrc", "credit"):
            db.execute(f"DROP TABLE {table}")
        for row_id, title, rank in ((1, "Song", 0), (2, "Song live", 1)):
            mbid = str(uuid.UUID(int=row_id))
            db.execute("INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?,?,?)", (
                row_id, str(track_id_for_recording(mbid)), mbid, title, title.lower(),
                json.dumps(["An Artist"]), "[]", 123000, "live" if rank else None,
                rank, "an artist",
            ))
        db.execute("INSERT INTO track_fts(track_fts) VALUES('rebuild')")
        db.execute("INSERT INTO build_metadata VALUES('state','READY')")
    return path


def settings(catalog, mode=AppMode.TEST):
    args = dict(mode=mode, music_search_backend="full_catalog", music_catalog_path=catalog)
    if mode is not AppMode.TEST:
        args.update(database_url="postgresql+psycopg://unused:unused@localhost/unused",
                    blob_root="/var/lib/remanence/blobs")
    return Settings(**args)


@pytest.mark.parametrize("mode", [AppMode.TEST, AppMode.DEV, AppMode.PROD])
def test_full_composes_without_account_factory(catalog, mode):
    search = build_music_search(settings(catalog, mode))
    assert isinstance(search, FullCatalogSearch)
    assert search.search_with_total("song artist", 1)[1] == 2


def test_composed_http_pagination_ids_and_readonly(catalog):
    before = hashlib.sha256(catalog.read_bytes()).digest()
    app = create_app(settings=settings(catalog))
    principal = AuthenticatedPrincipal(uuid.uuid4(), uuid.uuid4())
    app.dependency_overrides[get_authenticated_principal] = lambda: principal
    with TestClient(app) as client:
        first = client.get("/music/v1/search", params={"q": "song artist", "limit": 1})
        second = client.get("/music/v1/search", params={"q": "song artist", "limit": 1, "offset": 1})
        assert first.status_code == second.status_code == 200
        a, b = first.json(), second.json()
        assert a["total"] == b["total"] == 2
        assert a["offset"] == 0 and b["offset"] == 1
        assert a["results"][0]["id"] != b["results"][0]["id"]
        for body in (a, b):
            hit = body["results"][0]
            assert hit["id"] == str(track_id_for_recording(hit["recordingMbid"]))
        assert client.get("/music/v1/search", params={"q": "absent"}).json()["total"] == 0
        assert client.get("/music/v1/search", params={"q": "song", "limit": 21}).status_code == 422
    assert hashlib.sha256(catalog.read_bytes()).digest() == before
    assert not catalog.with_name(catalog.name + "-journal").exists()


def test_full_route_still_requires_auth(catalog):
    with TestClient(create_app(settings=settings(catalog))) as client:
        assert client.get("/music/v1/search", params={"q": "song"}).status_code == 401


@pytest.mark.parametrize("path", [None, "relative.sqlite3"])
def test_full_requires_explicit_absolute_path(path):
    with pytest.raises(ValueError):
        Settings(mode=AppMode.TEST, music_search_backend="full_catalog", music_catalog_path=path)


def test_missing_catalog_fails_startup_without_creating_file(tmp_path):
    path = tmp_path / "missing.sqlite3"
    with pytest.raises(MusicSearchUnavailableError):
        create_app(settings=settings(path))
    assert not path.exists()


def test_nonready_or_wrong_schema_fails_startup(catalog):
    with sqlite3.connect(catalog) as db:
        db.execute("UPDATE build_metadata SET value='BUILDING' WHERE key='state'")
    with pytest.raises(MusicSearchUnavailableError):
        create_app(settings=settings(catalog))
    with sqlite3.connect(catalog) as db:
        db.execute("UPDATE build_metadata SET value='READY' WHERE key='state'")
        db.execute("DROP TABLE track_fts")
    with pytest.raises(MusicSearchUnavailableError):
        create_app(settings=settings(catalog))


@pytest.mark.parametrize("timeout", [0, -1, 11, float("inf"), float("nan")])
def test_timeout_is_bounded(timeout):
    with pytest.raises(ValueError):
        Settings(mode=AppMode.TEST, music_search_timeout_seconds=timeout)
