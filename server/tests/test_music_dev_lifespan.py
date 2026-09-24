"""DEV lifespan composition: live create_app() with no factory param.

The public API runs `uvicorn remanence.main:create_app --factory`
(`REMANENCE_MODE=dev`), so lifespan must wire PostgresStagingSearch from
its newly built app.state.session_factory only when
`music_search_backend=postgres_staging`. PROD refusal and the fixture
guard stay absolute; TEST without a factory still fails closed at
factory time. No live DB touch: wiring-only asserts, dummy URL never
queried (engine creation connects lazily).
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from remanence.api.music import build_fixture_music_search
from remanence.main import create_app
from remanence.music.search.in_memory import InMemoryMusicSearch
from remanence.music.search.staging import PostgresStagingSearch
from remanence.settings import AppMode, MusicSearchBackend, Settings


def _dev_staging_settings(tmp_path) -> Settings:
    return Settings(
        mode=AppMode.DEV,
        database_url="postgresql+psycopg://remanence:secret@127.0.0.1:55432/remanence",
        blob_root=str(tmp_path / "blobs"),
        music_search_backend=MusicSearchBackend.POSTGRES_STAGING,
    )


def test_dev_factory_does_not_raise_synchronously(tmp_path) -> None:
    create_app(settings=_dev_staging_settings(tmp_path))


def test_dev_lifespan_wires_staging_from_built_factory(tmp_path) -> None:
    app = create_app(settings=_dev_staging_settings(tmp_path))
    assert getattr(app.state, "music_search", None) is None
    with TestClient(app):
        assert isinstance(app.state.music_search, PostgresStagingSearch)


def test_dev_disabled_lifespan_stays_unwired(tmp_path) -> None:
    settings = Settings(
        mode=AppMode.DEV,
        database_url="postgresql+psycopg://remanence:secret@127.0.0.1:55432/remanence",
        blob_root=str(tmp_path / "blobs"),
    )
    assert settings.music_search_backend is MusicSearchBackend.DISABLED
    app = create_app(settings=settings)
    with TestClient(app):
        assert getattr(app.state, "music_search", None) is None


def _prod_settings(**overrides) -> Settings:
    base = {
        "mode": AppMode.PROD,
        "database_url": "postgresql+psycopg://remanence:secret@127.0.0.1:55432/remanence",
        "blob_root": "/var/lib/remanence/blobs",
    }
    base.update(overrides)
    return Settings(**base)


def test_prod_refusal_intact() -> None:
    with pytest.raises(ValueError):
        create_app(settings=_prod_settings(music_search_backend="postgres_staging"))
    with pytest.raises(ValueError):
        create_app(
            settings=_prod_settings(),
            music_search=PostgresStagingSearch(lambda: None),  # type: ignore[return-value]
        )
    with pytest.raises(ValueError):
        create_app(
            settings=_prod_settings(),
            music_search=InMemoryMusicSearch(()),
        )
    with pytest.raises(ValueError):
        build_fixture_music_search(mode=AppMode.PROD)


def test_test_staging_without_factory_fails_closed() -> None:
    settings = Settings(
        mode=AppMode.TEST, music_search_backend=MusicSearchBackend.POSTGRES_STAGING
    )
    with pytest.raises(ValueError):
        create_app(settings=settings)
