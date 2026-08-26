import os
from collections.abc import Iterator

import pytest
from pydantic import SecretStr
from sqlalchemy import text

from remanence.db.session import (
    DatabaseConfigurationError,
    build_engine,
    build_session_factory,
)
from remanence.settings import AppMode, Settings

FIXTURE_PASSWORD = "s3cret-fixture-password"
_TEST_DATABASE_URL = os.environ.get("REMANENCE_TEST_DATABASE_URL")


@pytest.fixture(autouse=True)
def isolate_remanence_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    for key in list(os.environ):
        if key.upper().startswith("REMANENCE_"):
            monkeypatch.delenv(key, raising=False)
    yield


def test_missing_database_url_rejected() -> None:
    settings = Settings(mode=AppMode.TEST)
    with pytest.raises(DatabaseConfigurationError):
        build_engine(settings)


def test_engine_and_factory_do_not_connect_until_used() -> None:
    settings = Settings(
        mode=AppMode.TEST,
        database_url=SecretStr(
            f"postgresql+psycopg://remanence:{FIXTURE_PASSWORD}@127.0.0.1:1/remanence"
        ),
    )
    engine = build_engine(settings)
    try:
        factory = build_session_factory(engine)
        assert engine.pool.checkedout() == 0
        assert factory.kw["bind"] is engine
    finally:
        engine.dispose()


def test_select_1_through_engine_and_session() -> None:
    if not _TEST_DATABASE_URL:
        pytest.skip("REMANENCE_TEST_DATABASE_URL is not set")
    settings = Settings(mode=AppMode.TEST, database_url=SecretStr(_TEST_DATABASE_URL))
    engine = build_engine(settings)
    try:
        with engine.connect() as connection:
            assert connection.execute(text("SELECT 1")).scalar_one() == 1
        session = build_session_factory(engine)()
        try:
            assert session.scalar(text("SELECT 1")) == 1
        finally:
            session.close()
    finally:
        engine.dispose()


def test_engine_repr_does_not_expose_fixture_password() -> None:
    settings = Settings(
        mode=AppMode.TEST,
        database_url=SecretStr(
            f"postgresql+psycopg://remanence:{FIXTURE_PASSWORD}@127.0.0.1:55432/remanence"
        ),
    )
    engine = build_engine(settings)
    try:
        assert FIXTURE_PASSWORD not in repr(engine)
        assert FIXTURE_PASSWORD not in str(engine)
        assert FIXTURE_PASSWORD not in str(engine.url)
        assert FIXTURE_PASSWORD not in repr(engine.url)
    finally:
        engine.dispose()
