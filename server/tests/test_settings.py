import os
from collections.abc import Iterator
from pathlib import Path

import pytest
from pydantic import ValidationError

from postmark.settings import AppMode, Settings

FIXTURE_PASSWORD = "s3cret-fixture-password"
DEV_DATABASE_URL = (
    f"postgresql+psycopg://postmark:{FIXTURE_PASSWORD}@localhost:5432/postmark"
)


@pytest.fixture(autouse=True)
def isolate_postmark_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    for key in list(os.environ):
        if key.upper().startswith("POSTMARK_"):
            monkeypatch.delenv(key, raising=False)
    yield


def test_minimal_test_mode_accepted_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POSTMARK_MODE", "test")
    settings = Settings()
    assert settings.mode is AppMode.TEST
    assert settings.host == "127.0.0.1"
    assert settings.port == 8000
    assert settings.database_url is None
    assert settings.blob_root is None


def test_missing_mode_rejected() -> None:
    with pytest.raises(ValidationError):
        Settings()


def test_dev_missing_required_values_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POSTMARK_MODE", "dev")
    with pytest.raises(ValidationError):
        Settings()


def test_dev_valid_postgres_url_and_relative_blob_root_accepted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("POSTMARK_MODE", "dev")
    monkeypatch.setenv("POSTMARK_DATABASE_URL", DEV_DATABASE_URL)
    monkeypatch.setenv("POSTMARK_BLOB_ROOT", "var/blobs")
    settings = Settings()
    assert settings.mode is AppMode.DEV
    assert settings.blob_root == Path("var/blobs")
    assert not settings.blob_root.is_absolute()
    assert settings.database_url is not None
    assert settings.database_url.get_secret_value() == DEV_DATABASE_URL


def test_prod_relative_root_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POSTMARK_MODE", "prod")
    monkeypatch.setenv("POSTMARK_DATABASE_URL", DEV_DATABASE_URL)
    monkeypatch.setenv("POSTMARK_BLOB_ROOT", "var/blobs")
    with pytest.raises(ValidationError):
        Settings()


def test_prod_complete_absolute_root_accepted(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POSTMARK_MODE", "prod")
    monkeypatch.setenv("POSTMARK_DATABASE_URL", DEV_DATABASE_URL)
    monkeypatch.setenv("POSTMARK_BLOB_ROOT", "/var/postmark/blobs")
    settings = Settings()
    assert settings.mode is AppMode.PROD
    assert settings.blob_root == Path("/var/postmark/blobs")
    assert settings.blob_root.is_absolute()


def test_port_bounds_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POSTMARK_MODE", "test")
    monkeypatch.setenv("POSTMARK_PORT", "0")
    with pytest.raises(ValidationError):
        Settings()
    monkeypatch.setenv("POSTMARK_PORT", "65536")
    with pytest.raises(ValidationError):
        Settings()


def test_secretstr_repr_does_not_expose_fixture_password(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("POSTMARK_MODE", "dev")
    monkeypatch.setenv("POSTMARK_DATABASE_URL", DEV_DATABASE_URL)
    monkeypatch.setenv("POSTMARK_BLOB_ROOT", "var/blobs")
    settings = Settings()
    assert FIXTURE_PASSWORD not in repr(settings)
    assert FIXTURE_PASSWORD not in repr(settings.database_url)
    assert FIXTURE_PASSWORD not in str(settings.database_url)
