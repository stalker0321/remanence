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

_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"
_BASELINE = "0001_m0_baseline"
_HEAD = "0006_m2_f3_tombstone_feed"
_HEAD_TABLES = {
    "alembic_version",
    "users",
    "auth_credentials",
    "auth_sessions",
    "user_key_bundles",
    "capsules",
    "capsule_blobs",
    "capsule_envelopes",
    "recipient_delivery_state",
    "capsule_idempotency_records",
    "recipient_tombstone_counters",
}
_REQUIRED_FKS = {
    "fk_auth_credentials_user_id_users": "c",
    "fk_auth_sessions_user_id_users": "c",
    "fk_auth_sessions_parent_session_id_auth_sessions": "r",
    "fk_user_key_bundles_user_id_users": "c",
    "fk_capsules_sender_user_id_users": "r",
    "fk_capsules_recipient_user_id_users": "r",
    "fk_capsules_sender_key_bundle_id_user_key_bundles": "r",
    "fk_capsules_recipient_key_bundle_id_user_key_bundles": "r",
    "fk_capsule_blobs_capsule_id_capsules": "c",
    "fk_capsule_envelopes_capsule_id_capsules": "c",
    "fk_capsule_envelopes_recipient_user_id_users": "r",
    "fk_capsule_envelopes_recipient_key_bundle_id_user_key_bundles": "r",
    "fk_recipient_delivery_state_recipient_user_id_users": "r",
    "fk_recipient_delivery_state_capsule_id_capsules": "c",
    "fk_capsule_idempotency_records_owner_user_id_users": "c",
    "fk_recipient_tombstone_counters_recipient_user_id_users": "c",
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
    "pk_capsules",
    "pk_capsule_blobs",
    "pk_capsule_envelopes",
    "pk_recipient_delivery_state",
    "pk_capsule_idempotency_records",
    "fk_capsules_sender_user_id_users",
    "fk_capsules_recipient_user_id_users",
    "fk_capsules_sender_key_bundle_id_user_key_bundles",
    "fk_capsules_recipient_key_bundle_id_user_key_bundles",
    "fk_capsule_blobs_capsule_id_capsules",
    "fk_capsule_envelopes_capsule_id_capsules",
    "fk_capsule_envelopes_recipient_user_id_users",
    "fk_capsule_envelopes_recipient_key_bundle_id_user_key_bundles",
    "fk_recipient_delivery_state_recipient_user_id_users",
    "fk_recipient_delivery_state_capsule_id_capsules",
    "fk_capsule_idempotency_records_owner_user_id_users",
    "uq_capsule_blobs_object_key",
    "ck_capsules_protocol_version_positive",
    "ck_capsules_draft_expiry_order",
    "ck_capsules_signed_statement_sha256_32",
    "ck_capsules_publish_signature_69",
    "ck_capsules_state_finalization_shape",
    "ck_recipient_delivery_state_publication_sequence_positive",
    "ck_capsule_blobs_expected_ciphertext_size_positive",
    "ck_capsule_blobs_expected_ciphertext_sha256_32",
    "ck_capsule_blobs_kind_ordinal_shape",
    "ck_capsule_envelopes_ciphertext_size_bounds",
    "ck_capsule_envelopes_ciphertext_size_matches",
    "ck_capsule_envelopes_ciphertext_sha256_32",
    "ck_recipient_delivery_state_state_timestamp_coherence",
    "uq_recipient_delivery_state_recipient_publication_sequence",
    "ck_capsule_idempotency_records_method_uppercase",
    "ck_capsule_idempotency_records_request_sha256_32",
    "ck_capsule_idempotency_records_response_status_range",
    "ck_capsule_idempotency_records_expiry_order",
    "pk_recipient_tombstone_counters",
    "fk_recipient_tombstone_counters_recipient_user_id_users",
    "ck_recipient_tombstone_counters_last_sequence_nonnegative",
    "ck_capsules_tombstone_fields_shape",
    "ck_capsules_tombstone_sequence_positive",
    "uq_capsules_recipient_tombstone_sequence",
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


def _enum_labels(conn: psycopg.Connection, enum_name: str = "key_bundle_status") -> list[str]:
    rows = conn.execute(
        """
        SELECT e.enumlabel
        FROM pg_enum AS e
        JOIN pg_type AS t ON t.oid = e.enumtypid
        JOIN pg_namespace AS n ON n.oid = t.typnamespace
        WHERE t.typname = %s AND n.nspname = 'public'
        ORDER BY e.enumsortorder
        """,
        (enum_name,),
    ).fetchall()
    return [row[0] for row in rows]


def _enum_exists(conn: psycopg.Connection, enum_name: str = "key_bundle_status") -> bool:
    row = conn.execute(
        """
        SELECT EXISTS (
            SELECT 1
            FROM pg_type AS t
            JOIN pg_namespace AS n ON n.oid = t.typnamespace
            WHERE t.typname = %s AND n.nspname = 'public'
        )
        """,
        (enum_name,),
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
    for enum_name in (
        "key_bundle_status",
        "capsule_state",
        "capsule_blob_kind",
        "capsule_blob_state",
        "recipient_delivery_status",
    ):
        assert not _enum_exists(conn, enum_name)


def _assert_m1_schema(conn: psycopg.Connection) -> None:
    assert _alembic_version(conn) == ["0002_m1_accounts"]
    assert _public_tables(conn) == {
        "alembic_version",
        "users",
        "auth_credentials",
        "auth_sessions",
        "user_key_bundles",
    }
    assert _enum_labels(conn) == ["ACTIVE", "RETIRED", "REVOKED"]
    for enum_name in (
        "capsule_state",
        "capsule_blob_kind",
        "capsule_blob_state",
        "recipient_delivery_status",
    ):
        assert not _enum_exists(conn, enum_name)


def _assert_head_schema(conn: psycopg.Connection) -> None:
    assert _alembic_version(conn) == [_HEAD]
    assert _public_tables(conn) == _HEAD_TABLES
    assert _enum_labels(conn) == ["ACTIVE", "RETIRED", "REVOKED"]
    assert _enum_labels(conn, "capsule_state") == ["DRAFT", "READY", "ABORTED", "REVOKED"]
    assert _enum_labels(conn, "capsule_blob_kind") == [
        "RECOGNITION_MANIFEST",
        "CONTENT_MANIFEST",
        "PHOTO",
    ]
    assert _enum_labels(conn, "capsule_blob_state") == ["DECLARED", "STORED"]
    assert _enum_labels(conn, "recipient_delivery_status") == [
        "AVAILABLE",
        "CIPHERTEXT_SYNCED",
    ]
    publish_signature = conn.execute(
        """
        SELECT data_type, is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'capsules'
          AND column_name = 'publish_signature'
        """
    ).fetchone()
    assert publish_signature == ("bytea", "YES")
    tombstone_sequence = conn.execute(
        """
        SELECT data_type, is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'capsules'
          AND column_name = 'tombstone_sequence'
        """
    ).fetchone()
    assert tombstone_sequence == ("bigint", "YES")
    revoked_at = conn.execute(
        """
        SELECT data_type, is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'capsules'
          AND column_name = 'revoked_at'
        """
    ).fetchone()
    assert revoked_at == ("timestamp with time zone", "YES")
    counter_columns = conn.execute(
        """
        SELECT column_name, data_type, is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'recipient_tombstone_counters'
        ORDER BY ordinal_position
        """
    ).fetchall()
    assert counter_columns == [
        ("recipient_user_id", "uuid", "NO"),
        ("last_sequence", "bigint", "NO"),
    ]
    publication_sequence = conn.execute(
        """
        SELECT data_type, is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'recipient_delivery_state'
          AND column_name = 'publication_sequence'
        """
    ).fetchone()
    assert publication_sequence == ("bigint", "NO")

    primary_keys = {
        "users": "pk_users",
        "auth_credentials": "pk_auth_credentials",
        "auth_sessions": "pk_auth_sessions",
        "user_key_bundles": "pk_user_key_bundles",
        "capsules": "pk_capsules",
        "capsule_blobs": "pk_capsule_blobs",
        "capsule_envelopes": "pk_capsule_envelopes",
        "recipient_delivery_state": "pk_recipient_delivery_state",
        "capsule_idempotency_records": "pk_capsule_idempotency_records",
        "recipient_tombstone_counters": "pk_recipient_tombstone_counters",
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

    expected_indexes = {
        "ix_capsules_sender_user_id",
        "ix_capsules_recipient_user_id",
        "ix_capsules_draft_expires_at",
        "ix_capsule_blobs_capsule_id",
        "uq_capsule_blobs_one_recognition_manifest_per_capsule",
        "uq_capsule_blobs_one_content_manifest_per_capsule",
        "uq_capsule_blobs_photo_ordinal_per_capsule",
        "ix_capsule_idempotency_records_expires_at",
    }
    actual_indexes = {
        row[0]
        for row in conn.execute(
            """
            SELECT indexname
            FROM pg_indexes
            WHERE schemaname = 'public'
            """
        ).fetchall()
    }
    assert expected_indexes <= actual_indexes

    expected_partial_indexes = {
        "uq_capsule_blobs_one_recognition_manifest_per_capsule": "kind = 'RECOGNITION_MANIFEST'",
        "uq_capsule_blobs_one_content_manifest_per_capsule": "kind = 'CONTENT_MANIFEST'",
        "uq_capsule_blobs_photo_ordinal_per_capsule": "kind = 'PHOTO'",
    }
    for index_name, predicate in expected_partial_indexes.items():
        row = conn.execute(
            """
            SELECT indexdef
            FROM pg_indexes
            WHERE schemaname = 'public' AND indexname = %s
            """,
            (index_name,),
        ).fetchone()
        assert row is not None
        definition = row[0]
        assert definition.startswith("CREATE UNIQUE INDEX")
        assert predicate in definition


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

        revoked_user_id = uuid4()
        revoked_bundle_id = uuid4()
        revoked_capsule_ids = [uuid4(), uuid4()]
        ready_ats = [
            datetime.now(timezone.utc) - timedelta(days=2),
            datetime.now(timezone.utc) - timedelta(days=1),
        ]
        command.upgrade(config, "0005_m2_f3_capsule_revocation")
        with _connect_db(url, database) as conn:
            conn.execute(
                """
                INSERT INTO users (id, email_normalized, handle_normalized, handle_display)
                VALUES (%s, %s, %s, %s)
                """,
                (
                    revoked_user_id,
                    f"migration-{revoked_user_id}@example.com",
                    f"migration{revoked_user_id.hex[:20]}",
                    f"migration{revoked_user_id.hex[:20]}",
                ),
            )
            conn.execute(
                """
                INSERT INTO user_key_bundles (
                    id, user_id, encryption_public_keyset, signing_public_keyset,
                    suite, protocol_version, status
                ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                """,
                (
                    revoked_bundle_id,
                    revoked_user_id,
                    b"encryption-public",
                    b"signing-public",
                    "test-suite",
                    1,
                    "ACTIVE",
                ),
            )
            for index, capsule_id in enumerate(revoked_capsule_ids):
                conn.execute(
                    """
                    INSERT INTO capsules (
                        id, sender_user_id, recipient_user_id, sender_key_bundle_id,
                        recipient_key_bundle_id, protocol_version, state,
                        signed_statement, signed_statement_sha256, publish_signature,
                        ready_at, draft_expires_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        capsule_id,
                        revoked_user_id,
                        revoked_user_id,
                        revoked_bundle_id,
                        revoked_bundle_id,
                        1,
                        "REVOKED",
                        f"signed-statement-{index}".encode(),
                        bytes(range(32)),
                        bytes((index + 1,)) * 69,
                        ready_ats[index],
                        ready_ats[index] + timedelta(days=7),
                    ),
                )
        command.upgrade(config, "head")
        with _connect_db(url, database) as conn:
            _assert_head_schema(conn)
            backfilled = conn.execute(
                """
                SELECT id, tombstone_sequence, revoked_at
                FROM capsules
                WHERE recipient_user_id = %s
                ORDER BY tombstone_sequence
                """,
                (revoked_user_id,),
            ).fetchall()
            assert [row[0] for row in backfilled] == revoked_capsule_ids
            assert [row[1] for row in backfilled] == [1, 2]
            assert backfilled[0][2] is not None
            assert backfilled[0][2] == backfilled[1][2]
            assert conn.execute(
                "SELECT last_sequence FROM recipient_tombstone_counters WHERE recipient_user_id = %s",
                (revoked_user_id,),
            ).fetchone() == (2,)
        command.check(config)
        with pytest.raises(RuntimeError, match="revoked rows exist"):
            command.downgrade(config, "0004_r1_publication_order")
        with _connect_db(url, database) as conn:
            assert _alembic_version(conn) == [_HEAD]
            conn.execute("DELETE FROM capsules WHERE id = ANY(%s)", (revoked_capsule_ids,))
            conn.execute("DELETE FROM user_key_bundles WHERE id = %s", (revoked_bundle_id,))
            conn.execute("DELETE FROM users WHERE id = %s", (revoked_user_id,))

        command.downgrade(config, "0002_m1_accounts")
        with _connect_db(url, database) as conn:
            _assert_m1_schema(conn)

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
