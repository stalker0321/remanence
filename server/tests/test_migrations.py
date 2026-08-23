import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from psycopg import sql
from sqlalchemy.engine import make_url

_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"
_BASELINE = "0001_m0_baseline"


def _admin_connect(url) -> psycopg.Connection:
    return psycopg.connect(
        host=url.host,
        port=url.port,
        user=url.username,
        password=url.password,
        dbname="postgres",
        autocommit=True,
    )


def _connect_db(url, database: str) -> psycopg.Connection:
    return psycopg.connect(
        host=url.host,
        port=url.port,
        user=url.username,
        password=url.password,
        dbname=database,
        autocommit=True,
    )


def _assert_baseline(url, database: str) -> None:
    with _connect_db(url, database) as conn:
        versions = conn.execute("SELECT version_num FROM alembic_version").fetchall()
        assert [row[0] for row in versions] == [_BASELINE]
        tables = conn.execute(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
            """
        ).fetchall()
        assert {row[0] for row in tables} == {"alembic_version"}


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


def test_alembic_upgrade_twice_keeps_empty_baseline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = os.environ.get("POSTMARK_TEST_DATABASE_URL")
    if not source:
        pytest.skip("POSTMARK_TEST_DATABASE_URL is not set")

    url = make_url(source)
    database = f"postmark_tmp_{uuid4().hex}"
    admin: psycopg.Connection | None = None
    created = False
    try:
        admin = _admin_connect(url)
        admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
        created = True

        for key in list(os.environ):
            if key.upper().startswith("POSTMARK_"):
                monkeypatch.delenv(key, raising=False)
        monkeypatch.setenv("POSTMARK_MODE", "dev")
        monkeypatch.setenv(
            "POSTMARK_DATABASE_URL",
            url.set(database=database).render_as_string(hide_password=False),
        )
        monkeypatch.setenv("POSTMARK_BLOB_ROOT", "var/test-blobs")

        config = Config(str(_ALEMBIC_INI))
        config.set_main_option("path_separator", "os")
        command.upgrade(config, "head")
        _assert_baseline(url, database)
        command.upgrade(config, "head")
        _assert_baseline(url, database)
    finally:
        if admin is not None:
            try:
                if created:
                    _drop_database(admin, database)
            finally:
                admin.close()
