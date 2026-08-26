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
_HEAD = "0002_m1_accounts"
_HEAD_TABLES = {
    "alembic_version",
    "users",
    "auth_credentials",
    "auth_sessions",
    "user_key_bundles",
}
_REQUIRED_FKS = {
    "fk_auth_credentials_user_id_users": "c",
    "fk_auth_sessions_user_id_users": "c",
    "fk_auth_sessions_parent_session_id_auth_sessions": "r",
    "fk_user_key_bundles_user_id_users": "c",
}
_REQUIRED_NAMED_CONSTRAINTS = {
    "pk_users",
    "pk_auth_credentials",
    "pk_auth_sessions",
    "pk_user_key_bundles",
    "fk_auth_credentials_user_id_users",
    "fk_auth_sessions_user_id_users",
    "fk_auth_sessions_parent_session_id_auth_sessions",
    "fk_user_key_bundles_user_id_users",
    "uq_users_email_normalized",
    "uq_users_handle_normalized",
    "uq_auth_sessions_access_token_hash",
    "uq_auth_sessions_refresh_token_hash",
    "uq_auth_sessions_parent_session_id",
    "ck_auth_sessions_access_token_hash_32",
    "ck_auth_sessions_refresh_token_hash_32",
    "ck_auth_sessions_expiry_order",
    "ck_user_key_bundles_protocol_version_positive",
}


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


def _public_tables(conn: psycopg.Connection) -> set[str]:
    rows = conn.execute(
        """
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
        """
    ).fetchall()
    return {row[0] for row in rows}


def _alembic_version(conn: psycopg.Connection) -> list[str]:
    rows = conn.execute("SELECT version_num FROM alembic_version").fetchall()
    return [row[0] for row in rows]


def _enum_labels(conn: psycopg.Connection) -> list[str]:
    rows = conn.execute(
        """
        SELECT e.enumlabel
        FROM pg_enum AS e
        JOIN pg_type AS t ON t.oid = e.enumtypid
        JOIN pg_namespace AS n ON n.oid = t.typnamespace
        WHERE t.typname = 'key_bundle_status' AND n.nspname = 'public'
        ORDER BY e.enumsortorder
        """
    ).fetchall()
    return [row[0] for row in rows]


def _enum_exists(conn: psycopg.Connection) -> bool:
    row = conn.execute(
        """
        SELECT EXISTS (
            SELECT 1
            FROM pg_type AS t
            JOIN pg_namespace AS n ON n.oid = t.typnamespace
            WHERE t.typname = 'key_bundle_status' AND n.nspname = 'public'
        )
        """
    ).fetchone()
    assert row is not None
    return bool(row[0])


def _constraint_names_by_table(conn: psycopg.Connection, table: str) -> dict[str, str]:
    rows = conn.execute(
        """
        SELECT conname, contype
        FROM pg_constraint
        WHERE conrelid = %s::regclass
        """,
        (table,),
    ).fetchall()
    return {row[0]: row[1] for row in rows}


def _assert_baseline(conn: psycopg.Connection) -> None:
    assert _alembic_version(conn) == [_BASELINE]
    assert _public_tables(conn) == {"alembic_version"}
    assert not _enum_exists(conn)


def _assert_head_schema(conn: psycopg.Connection) -> None:
    assert _alembic_version(conn) == [_HEAD]
    assert _public_tables(conn) == _HEAD_TABLES
    assert _enum_labels(conn) == ["ACTIVE", "RETIRED", "REVOKED"]

    primary_keys = {
        "users": "pk_users",
        "auth_credentials": "pk_auth_credentials",
        "auth_sessions": "pk_auth_sessions",
        "user_key_bundles": "pk_user_key_bundles",
    }
    for table, name in primary_keys.items():
        constraints = _constraint_names_by_table(conn, table)
        assert constraints.get(name) == "p", table
        assert sum(1 for kind in constraints.values() if kind == "p") == 1, table

    foreign_keys = {
        row[0]: row[1]
        for row in conn.execute(
            """
            SELECT conname, confdeltype
            FROM pg_constraint
            WHERE contype = 'f' AND connamespace = 'public'::regnamespace
            """
        ).fetchall()
    }
    assert foreign_keys == _REQUIRED_FKS

    present = {
        row[0]
        for row in conn.execute(
            """
            SELECT conname
            FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND conname = ANY(%s)
            """,
            (sorted(_REQUIRED_NAMED_CONSTRAINTS),),
        ).fetchall()
    }
    assert _REQUIRED_NAMED_CONSTRAINTS <= present

    session_indexes = {
        row[0]
        for row in conn.execute(
            """
            SELECT indexname
            FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'auth_sessions'
            """
        ).fetchall()
    }
    assert {
        "ix_auth_sessions_user_id",
        "ix_auth_sessions_lineage_id",
    } <= session_indexes

    partial = conn.execute(
        """
        SELECT indexdef
        FROM pg_indexes
        WHERE schemaname = 'public' AND indexname = 'uq_user_key_bundles_one_active_per_user'
        """
    ).fetchall()
    assert len(partial) == 1
    definition = partial[0][0]
    assert definition.startswith("CREATE UNIQUE INDEX")
    compact = definition.replace(" ", "")
    assert "user_key_bundlesUSINGbtree(user_id)WHERE(status='ACTIVE'::key_bundle_status)" in compact


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


def test_account_migration_lifecycle(monkeypatch: pytest.MonkeyPatch) -> None:
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
        with _connect_db(url, database) as conn:
            _assert_head_schema(conn)
        command.check(config)

        command.downgrade(config, _BASELINE)
        with _connect_db(url, database) as conn:
            _assert_baseline(conn)

        command.upgrade(config, "head")
        with _connect_db(url, database) as conn:
            _assert_head_schema(conn)
        command.check(config)

        command.upgrade(config, "head")
        with _connect_db(url, database) as conn:
            _assert_head_schema(conn)
    finally:
        if admin is not None:
            try:
                if created:
                    _drop_database(admin, database)
            finally:
                admin.close()
