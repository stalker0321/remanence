"""Integration tests for auth session creation against a temporary PostgreSQL database."""

import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from psycopg import sql
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session

from remanence.auth.models import AuthSession
from remanence.auth.session_repository import AuthSessionRepository
from remanence.db.session import build_engine, build_session_factory
from remanence.settings import AppMode, Settings
from remanence.users.models import User

_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"
_ACCESS_HASH = bytes(range(32))
_REFRESH_HASH = bytes(range(32, 64))
_ACCESS_EXPIRES = datetime(2030, 1, 1, tzinfo=timezone.utc)
_REFRESH_EXPIRES = datetime(2030, 2, 1, tzinfo=timezone.utc)


def _admin_connect(url) -> psycopg.Connection:
    return psycopg.connect(
        host=url.host,
        port=url.port,
        user=url.username,
        password=url.password,
        dbname="postgres",
        autocommit=True,
    )


def _drop_database(admin: psycopg.Connection, database: str) -> None:
    admin.execute(
        """
        SELECT pg_terminate_backend(pid)
        FROM pg_stat_activity
        WHERE datname = %s AND pid <> pg_backend_pid()
        """,
        (database,),
    )
    admin.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(database)))


def _create_user(session: Session) -> User:
    user = User(
        id=uuid4(),
        email_normalized=f"user-{uuid4().hex}@example.com",
        handle_normalized=f"handle{uuid4().hex[:20]}",
        handle_display=f"handle{uuid4().hex[:20]}",
    )
    session.add(user)
    session.flush()
    return user


@pytest.fixture()
def session_factory(monkeypatch: pytest.MonkeyPatch):
    source = os.environ.get("REMANENCE_TEST_DATABASE_URL")
    if not source:
        pytest.skip("REMANENCE_TEST_DATABASE_URL is not set")

    url = make_url(source)
    database = f"remanence_tmp_{uuid4().hex}"
    admin: psycopg.Connection | None = None
    created = False
    try:
        admin = _admin_connect(url)
        admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
        created = True

        for key in list(os.environ):
            if key.upper().startswith("REMANENCE_"):
                monkeypatch.delenv(key, raising=False)
        monkeypatch.setenv("REMANENCE_MODE", "dev")
        monkeypatch.setenv(
            "REMANENCE_DATABASE_URL",
            url.set(database=database).render_as_string(hide_password=False),
        )
        monkeypatch.setenv("REMANENCE_BLOB_ROOT", "var/test-blobs")

        config = Config(str(_ALEMBIC_INI))
        config.set_main_option("path_separator", "os")
        command.upgrade(config, "head")

        engine = build_engine(Settings())
        factory = build_session_factory(engine)
        try:
            yield factory
        finally:
            engine.dispose()
    finally:
        if admin is not None:
            try:
                if created:
                    _drop_database(admin, database)
            finally:
                admin.close()


def test_root_session_persisted_with_id_equal_lineage_and_null_parent(
    session_factory,
) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        created = repo.create(
            user_id=user.id,
            access_token_hash=_ACCESS_HASH,
            refresh_token_hash=_REFRESH_HASH,
            access_expires_at=_ACCESS_EXPIRES,
            refresh_expires_at=_REFRESH_EXPIRES,
        )
        assert created.id == created.lineage_id
        assert created.parent_session_id is None
        assert created.user_id == user.id
        assert created.access_token_hash == _ACCESS_HASH
        assert created.refresh_token_hash == _REFRESH_HASH
        assert created.access_expires_at == _ACCESS_EXPIRES
        assert created.refresh_expires_at == _REFRESH_EXPIRES
        session.expunge_all()
        persisted = session.get(AuthSession, created.id)
        assert persisted is not None
        assert persisted.id == persisted.lineage_id
        assert persisted.parent_session_id is None
        assert persisted.user_id == user.id
        assert persisted.access_token_hash == _ACCESS_HASH
        assert persisted.refresh_token_hash == _REFRESH_HASH
        assert persisted.access_expires_at == _ACCESS_EXPIRES
        assert persisted.refresh_expires_at == _REFRESH_EXPIRES


def test_child_session_persisted_with_shared_lineage_and_parent(
    session_factory,
) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = repo.create(
            user_id=user.id,
            access_token_hash=_ACCESS_HASH,
            refresh_token_hash=_REFRESH_HASH,
            access_expires_at=_ACCESS_EXPIRES,
            refresh_expires_at=_REFRESH_EXPIRES,
        )
        child = repo.create(
            user_id=user.id,
            access_token_hash=bytes(range(64, 96)),
            refresh_token_hash=bytes(range(96, 128)),
            access_expires_at=_ACCESS_EXPIRES,
            refresh_expires_at=_REFRESH_EXPIRES,
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        assert child.lineage_id == root.lineage_id
        assert child.parent_session_id == root.id
        session.commit()
        session.expunge_all()
        persisted_root = session.get(AuthSession, root.id)
        persisted_child = session.get(AuthSession, child.id)
        assert persisted_child.lineage_id == persisted_root.lineage_id
        assert persisted_child.parent_session_id == persisted_root.id


def test_external_rollback_removes_created_session(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        created = repo.create(
            user_id=user.id,
            access_token_hash=_ACCESS_HASH,
            refresh_token_hash=_REFRESH_HASH,
            access_expires_at=_ACCESS_EXPIRES,
            refresh_expires_at=_REFRESH_EXPIRES,
        )
        session.rollback()
        assert session.get(AuthSession, created.id) is None