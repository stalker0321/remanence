import os
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from postmark.main import create_app
from postmark.settings import AppMode, Settings


@pytest.fixture(autouse=True)
def isolate_postmark_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    for key in list(os.environ):
        if key.upper().startswith("POSTMARK_"):
            monkeypatch.delenv(key, raising=False)
    yield


def test_explicit_test_settings_health() -> None:
    settings = Settings(mode=AppMode.TEST)
    app = create_app(settings)
    client = TestClient(app)

    response = client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert response.headers["content-type"].startswith("application/json")
    assert app.state.settings is settings
    assert settings.database_url is None
    assert settings.blob_root is None


def test_create_app_reads_test_mode_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POSTMARK_MODE", "test")
    app = create_app()
    client = TestClient(app)

    response = client.get("/healthz")

    assert app.state.settings.mode is AppMode.TEST
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert response.headers["content-type"].startswith("application/json")
