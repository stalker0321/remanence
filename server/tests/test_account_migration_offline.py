"""Offline assertions for the accounts schema migration. No database connection."""

import contextlib
import io
import os
from collections.abc import Iterator
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory

SERVER_ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS_DIR = SERVER_ROOT / "migrations"
FIXTURE_URL = "postgresql+psycopg://postmark:offline-fixture@127.0.0.1:1/postmark"

EXPECTED_UPGRADE_FRAGMENTS = [
    "CREATE TYPE key_bundle_status AS ENUM ('ACTIVE', 'RETIRED', 'REVOKED')",
    "CREATE TABLE users (",
    "CREATE TABLE auth_credentials (",
    "CREATE TABLE auth_sessions (",
    "CREATE TABLE user_key_bundles (",
    "CONSTRAINT pk_users PRIMARY KEY (id)",
    "CONSTRAINT uq_users_email_normalized UNIQUE (email_normalized)",
    "CONSTRAINT uq_users_handle_normalized UNIQUE (handle_normalized)",
    "CONSTRAINT pk_auth_credentials PRIMARY KEY (user_id)",
    "CONSTRAINT fk_auth_credentials_user_id_users FOREIGN KEY(user_id) "
    "REFERENCES users (id) ON DELETE CASCADE",
    "CONSTRAINT pk_auth_sessions PRIMARY KEY (id)",
    "CONSTRAINT fk_auth_sessions_user_id_users FOREIGN KEY(user_id) "
    "REFERENCES users (id) ON DELETE CASCADE",
    "CONSTRAINT fk_auth_sessions_parent_session_id_auth_sessions FOREIGN KEY(parent_session_id) "
    "REFERENCES auth_sessions (id) ON DELETE RESTRICT",
    "CONSTRAINT uq_auth_sessions_access_token_hash UNIQUE (access_token_hash)",
    "CONSTRAINT uq_auth_sessions_refresh_token_hash UNIQUE (refresh_token_hash)",
    "CONSTRAINT uq_auth_sessions_parent_session_id UNIQUE (parent_session_id)",
    "CONSTRAINT ck_auth_sessions_access_token_hash_32 CHECK (octet_length(access_token_hash) = 32)",
    "CONSTRAINT ck_auth_sessions_refresh_token_hash_32 CHECK (octet_length(refresh_token_hash) = 32)",
    "CONSTRAINT ck_auth_sessions_expiry_order CHECK (refresh_expires_at > access_expires_at)",
    "CREATE INDEX ix_auth_sessions_user_id ON auth_sessions (user_id)",
    "CREATE INDEX ix_auth_sessions_lineage_id ON auth_sessions (lineage_id)",
    "CONSTRAINT pk_user_key_bundles PRIMARY KEY (id)",
    "CONSTRAINT fk_user_key_bundles_user_id_users FOREIGN KEY(user_id) "
    "REFERENCES users (id) ON DELETE CASCADE",
    "CONSTRAINT ck_user_key_bundles_protocol_version_positive CHECK (protocol_version > 0)",
    "CREATE UNIQUE INDEX uq_user_key_bundles_one_active_per_user ON user_key_bundles (user_id) "
    "WHERE status = 'ACTIVE'",
]


@pytest.fixture()
def offline_config(monkeypatch: pytest.MonkeyPatch) -> Iterator[Config]:
    for key in list(os.environ):
        if key.upper().startswith("POSTMARK_"):
            monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("POSTMARK_MODE", "test")
    monkeypatch.setenv("POSTMARK_DATABASE_URL", FIXTURE_URL)
    config = Config()
    config.set_main_option("script_location", str(MIGRATIONS_DIR))
    yield config


def test_revision_chain_reachable() -> None:
    script = ScriptDirectory(str(MIGRATIONS_DIR))
    assert script.get_heads() == ["0002_m1_accounts"]
    assert script.get_revision("0001_m0_baseline").revision == "0001_m0_baseline"
    assert script.get_revision("0002_m1_accounts").down_revision == "0001_m0_baseline"


def test_upgrade_emits_full_schema_sql(offline_config: Config) -> None:
    sql = _normalized(_capture(lambda: command.upgrade(offline_config, "head", sql=True)))
    for fragment in EXPECTED_UPGRADE_FRAGMENTS:
        assert fragment in sql, fragment


def test_upgrade_emits_column_types_and_defaults(offline_config: Config) -> None:
    sql = _normalized(_capture(lambda: command.upgrade(offline_config, "head", sql=True)))
    assert "email_normalized VARCHAR(320) NOT NULL" in sql
    assert "password_hash VARCHAR(512) NOT NULL" in sql
    assert "access_token_hash BYTEA NOT NULL" in sql
    assert "encryption_public_keyset BYTEA NOT NULL" in sql
    assert "protocol_version SMALLINT NOT NULL" in sql
    assert "status key_bundle_status NOT NULL" in sql
    assert "created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL" in sql
    assert "disabled_at TIMESTAMP WITH TIME ZONE," in sql


def test_downgrade_drops_in_reverse_dependency_order(offline_config: Config) -> None:
    sql = _normalized(
        _capture(
            lambda: command.downgrade(
                offline_config,
                "0002_m1_accounts:0001_m0_baseline",
                sql=True,
            )
        )
    )
    ordered_fragments = [
        "DROP INDEX uq_user_key_bundles_one_active_per_user",
        "DROP INDEX ix_auth_sessions_lineage_id",
        "DROP INDEX ix_auth_sessions_user_id",
        "DROP TABLE user_key_bundles",
        "DROP TABLE auth_sessions",
        "DROP TABLE auth_credentials",
        "DROP TABLE users",
    ]
    positions = [sql.index(fragment) for fragment in ordered_fragments]
    assert positions == sorted(positions), ordered_fragments
    drop_type_position = sql.index("DROP TYPE key_bundle_status")
    assert drop_type_position > positions[-1]


def _capture(operation) -> str:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        operation()
    return buffer.getvalue()


def _normalized(sql: str) -> str:
    return " ".join(sql.split())
