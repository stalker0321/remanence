"""Offline assertions for the accounts and capsule routing migrations. No database connection."""

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
FIXTURE_URL = "postgresql+psycopg://remanence:offline-fixture@127.0.0.1:1/remanence"

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
    "CREATE TYPE capsule_state AS ENUM ('DRAFT', 'READY', 'ABORTED')",
    "CREATE TYPE capsule_blob_kind AS ENUM ('RECOGNITION_MANIFEST', 'CONTENT_MANIFEST', 'PHOTO')",
    "CREATE TYPE capsule_blob_state AS ENUM ('DECLARED', 'STORED')",
    "CREATE TYPE recipient_delivery_status AS ENUM ('AVAILABLE', 'CIPHERTEXT_SYNCED')",
    "CREATE TABLE capsules (",
    "CONSTRAINT pk_capsules PRIMARY KEY (id)",
    "CONSTRAINT fk_capsules_sender_user_id_users FOREIGN KEY(sender_user_id) "
    "REFERENCES users (id) ON DELETE RESTRICT",
    "CONSTRAINT fk_capsules_recipient_user_id_users FOREIGN KEY(recipient_user_id) "
    "REFERENCES users (id) ON DELETE RESTRICT",
    "CONSTRAINT fk_capsules_sender_key_bundle_id_user_key_bundles FOREIGN KEY(sender_key_bundle_id) "
    "REFERENCES user_key_bundles (id) ON DELETE RESTRICT",
    "CONSTRAINT fk_capsules_recipient_key_bundle_id_user_key_bundles FOREIGN KEY(recipient_key_bundle_id) "
    "REFERENCES user_key_bundles (id) ON DELETE RESTRICT",
    "CONSTRAINT ck_capsules_protocol_version_positive CHECK (protocol_version > 0)",
    "CONSTRAINT ck_capsules_draft_expiry_order CHECK (draft_expires_at > created_at)",
    "CONSTRAINT ck_capsules_signed_statement_sha256_32 CHECK "
    "(signed_statement_sha256 IS NULL OR octet_length(signed_statement_sha256) = 32)",
    "CONSTRAINT ck_capsules_state_finalization_shape CHECK",
    "CREATE INDEX ix_capsules_sender_user_id ON capsules (sender_user_id)",
    "CREATE INDEX ix_capsules_recipient_user_id ON capsules (recipient_user_id)",
    "CREATE INDEX ix_capsules_draft_expires_at ON capsules (draft_expires_at)",
    "CREATE TABLE capsule_blobs (",
    "CONSTRAINT pk_capsule_blobs PRIMARY KEY (id)",
    "CONSTRAINT fk_capsule_blobs_capsule_id_capsules FOREIGN KEY(capsule_id) "
    "REFERENCES capsules (id) ON DELETE CASCADE",
    "CONSTRAINT uq_capsule_blobs_object_key UNIQUE (object_key)",
    "CONSTRAINT ck_capsule_blobs_expected_ciphertext_size_positive CHECK (expected_ciphertext_size > 0)",
    "CONSTRAINT ck_capsule_blobs_expected_ciphertext_sha256_32 CHECK "
    "(octet_length(expected_ciphertext_sha256) = 32)",
    "CONSTRAINT ck_capsule_blobs_kind_ordinal_shape CHECK",
    "CREATE INDEX ix_capsule_blobs_capsule_id ON capsule_blobs (capsule_id)",
    "CREATE UNIQUE INDEX uq_capsule_blobs_one_recognition_manifest_per_capsule "
    "ON capsule_blobs (capsule_id) WHERE kind = 'RECOGNITION_MANIFEST'",
    "CREATE UNIQUE INDEX uq_capsule_blobs_one_content_manifest_per_capsule "
    "ON capsule_blobs (capsule_id) WHERE kind = 'CONTENT_MANIFEST'",
    "CREATE UNIQUE INDEX uq_capsule_blobs_photo_ordinal_per_capsule "
    "ON capsule_blobs (capsule_id, ordinal) WHERE kind = 'PHOTO'",
    "CREATE TABLE capsule_envelopes (",
    "CONSTRAINT pk_capsule_envelopes PRIMARY KEY (capsule_id)",
    "CONSTRAINT fk_capsule_envelopes_capsule_id_capsules FOREIGN KEY(capsule_id) "
    "REFERENCES capsules (id) ON DELETE CASCADE",
    "CONSTRAINT fk_capsule_envelopes_recipient_user_id_users FOREIGN KEY(recipient_user_id) "
    "REFERENCES users (id) ON DELETE RESTRICT",
    "CONSTRAINT fk_capsule_envelopes_recipient_key_bundle_id_user_key_bundles FOREIGN KEY(recipient_key_bundle_id) "
    "REFERENCES user_key_bundles (id) ON DELETE RESTRICT",
    "CONSTRAINT ck_capsule_envelopes_ciphertext_size_bounds CHECK "
    "(ciphertext_size > 0 AND ciphertext_size <= 16384)",
    "CONSTRAINT ck_capsule_envelopes_ciphertext_size_matches CHECK "
    "(octet_length(ciphertext) = ciphertext_size)",
    "CONSTRAINT ck_capsule_envelopes_ciphertext_sha256_32 CHECK "
    "(octet_length(ciphertext_sha256) = 32)",
    "CREATE TABLE recipient_delivery_state (",
    "CONSTRAINT pk_recipient_delivery_state PRIMARY KEY (recipient_user_id, capsule_id)",
    "CONSTRAINT fk_recipient_delivery_state_recipient_user_id_users FOREIGN KEY(recipient_user_id) "
    "REFERENCES users (id) ON DELETE RESTRICT",
    "CONSTRAINT fk_recipient_delivery_state_capsule_id_capsules FOREIGN KEY(capsule_id) "
    "REFERENCES capsules (id) ON DELETE CASCADE",
    "CONSTRAINT ck_recipient_delivery_state_state_timestamp_coherence CHECK",
    "CREATE TABLE capsule_idempotency_records (",
    "CONSTRAINT pk_capsule_idempotency_records PRIMARY KEY "
    "(owner_user_id, method, normalized_route, idempotency_key)",
    "CONSTRAINT fk_capsule_idempotency_records_owner_user_id_users FOREIGN KEY(owner_user_id) "
    "REFERENCES users (id) ON DELETE CASCADE",
    "CONSTRAINT ck_capsule_idempotency_records_method_uppercase CHECK",
    "CONSTRAINT ck_capsule_idempotency_records_request_sha256_32 CHECK "
    "(octet_length(request_sha256) = 32)",
    "CONSTRAINT ck_capsule_idempotency_records_response_status_range CHECK "
    "(response_status BETWEEN 200 AND 599)",
    "CONSTRAINT ck_capsule_idempotency_records_expiry_order CHECK (expires_at > created_at)",
    "CREATE INDEX ix_capsule_idempotency_records_expires_at "
    "ON capsule_idempotency_records (expires_at)",
]


@pytest.fixture()
def offline_config(monkeypatch: pytest.MonkeyPatch) -> Iterator[Config]:
    for key in list(os.environ):
        if key.upper().startswith("REMANENCE_"):
            monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("REMANENCE_MODE", "test")
    monkeypatch.setenv("REMANENCE_DATABASE_URL", FIXTURE_URL)
    config = Config()
    config.set_main_option("script_location", str(MIGRATIONS_DIR))
    yield config


def test_revision_chain_reachable() -> None:
    script = ScriptDirectory(str(MIGRATIONS_DIR))
    assert script.get_heads() == ["0003_m2_capsule_routing"]
    assert script.get_revision("0001_m0_baseline").revision == "0001_m0_baseline"
    assert script.get_revision("0002_m1_accounts").down_revision == "0001_m0_baseline"
    assert script.get_revision("0003_m2_capsule_routing").down_revision == "0002_m1_accounts"


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


def test_capsule_upgrade_is_in_dependency_order(offline_config: Config) -> None:
    sql = _normalized(_capture(lambda: command.upgrade(offline_config, "head", sql=True)))
    ordered_fragments = [
        "CREATE TYPE capsule_state AS ENUM",
        "CREATE TYPE capsule_blob_kind AS ENUM",
        "CREATE TYPE capsule_blob_state AS ENUM",
        "CREATE TYPE recipient_delivery_status AS ENUM",
        "CREATE TABLE capsules (",
        "CREATE TABLE capsule_blobs (",
        "CREATE TABLE capsule_envelopes (",
        "CREATE TABLE recipient_delivery_state (",
        "CREATE TABLE capsule_idempotency_records (",
    ]
    positions = [sql.index(fragment) for fragment in ordered_fragments]
    assert positions == sorted(positions), ordered_fragments
    assert "response_json JSONB NOT NULL" in sql
    assert "created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL" in sql


def test_capsule_downgrade_stops_at_m1_and_drops_new_types_last(offline_config: Config) -> None:
    sql = _normalized(
        _capture(
            lambda: command.downgrade(
                offline_config,
                "0003_m2_capsule_routing:0002_m1_accounts",
                sql=True,
            )
        )
    )
    ordered_fragments = [
        "DROP INDEX ix_capsule_idempotency_records_expires_at",
        "DROP TABLE capsule_idempotency_records",
        "DROP TABLE recipient_delivery_state",
        "DROP TABLE capsule_envelopes",
        "DROP INDEX uq_capsule_blobs_photo_ordinal_per_capsule",
        "DROP INDEX uq_capsule_blobs_one_content_manifest_per_capsule",
        "DROP INDEX uq_capsule_blobs_one_recognition_manifest_per_capsule",
        "DROP INDEX ix_capsule_blobs_capsule_id",
        "DROP TABLE capsule_blobs",
        "DROP INDEX ix_capsules_draft_expires_at",
        "DROP INDEX ix_capsules_recipient_user_id",
        "DROP INDEX ix_capsules_sender_user_id",
        "DROP TABLE capsules",
        "DROP TYPE recipient_delivery_status",
        "DROP TYPE capsule_blob_state",
        "DROP TYPE capsule_blob_kind",
        "DROP TYPE capsule_state",
    ]
    positions = [sql.index(fragment) for fragment in ordered_fragments]
    assert positions == sorted(positions), ordered_fragments
    assert "DROP TYPE key_bundle_status" not in sql


def _capture(operation) -> str:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        operation()
    return buffer.getvalue()


def _normalized(sql: str) -> str:
    return " ".join(sql.split())
