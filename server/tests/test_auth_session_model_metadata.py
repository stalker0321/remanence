"""Metadata-only assertions for the auth sessions table model. No database connection."""

import uuid

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKeyConstraint,
    LargeBinary,
    UUID,
    UniqueConstraint,
)

from postmark.auth.models import AuthCredential, AuthSession
from postmark.db.base import Base

EXPECTED_COLUMNS = frozenset(
    {
        "id",
        "user_id",
        "lineage_id",
        "parent_session_id",
        "access_token_hash",
        "refresh_token_hash",
        "access_expires_at",
        "refresh_expires_at",
        "created_at",
        "last_used_at",
        "rotated_at",
        "revoked_at",
    }
)
NON_NULL_COLUMNS = frozenset(
    {
        "id",
        "user_id",
        "lineage_id",
        "access_token_hash",
        "refresh_token_hash",
        "access_expires_at",
        "refresh_expires_at",
        "created_at",
    }
)
NULLABLE_COLUMNS = frozenset(
    {"parent_session_id", "last_used_at", "rotated_at", "revoked_at"}
)


def _table():
    return AuthSession.__table__


def _column(name: str):
    return _table().columns[name]


def test_table_name_and_exact_column_set() -> None:
    assert AuthSession.__tablename__ == "auth_sessions"
    assert set(_table().columns.keys()) == EXPECTED_COLUMNS
    assert len(_table().columns) == 12


def test_column_types_and_nullability_exact() -> None:
    uuid_columns = ("id", "user_id", "lineage_id", "parent_session_id")
    for name in uuid_columns:
        column = _column(name)
        assert isinstance(column.type, UUID), name
        assert column.type.python_type is uuid.UUID, name
    for name in ("access_token_hash", "refresh_token_hash"):
        column = _column(name)
        assert isinstance(column.type, LargeBinary), name
        assert column.type.length == 32, name
        assert column.type.python_type is bytes, name
    datetime_columns = (
        "access_expires_at",
        "refresh_expires_at",
        "created_at",
        "last_used_at",
        "rotated_at",
        "revoked_at",
    )
    for name in datetime_columns:
        column = _column(name)
        assert isinstance(column.type, DateTime), name
        assert column.type.timezone is True, name
    assert NON_NULL_COLUMNS | NULLABLE_COLUMNS == EXPECTED_COLUMNS
    for name in NON_NULL_COLUMNS:
        assert _column(name).nullable is False, name
    for name in NULLABLE_COLUMNS:
        assert _column(name).nullable is True, name


def test_primary_key_is_id_with_python_uuid4_default() -> None:
    pk_columns = list(_table().primary_key.columns)
    assert [column.name for column in pk_columns] == ["id"]
    id_column = _column("id")
    assert id_column.default is not None
    assert id_column.default.is_callable
    assert callable(id_column.default.arg)
    assert isinstance(id_column.default.arg(None), uuid.UUID)
    assert id_column.server_default is None


def test_exactly_two_named_fks_targets_and_ondelete() -> None:
    fk_constraints = {
        constraint.name: constraint
        for constraint in _table().constraints
        if isinstance(constraint, ForeignKeyConstraint)
    }
    assert set(fk_constraints) == {
        "fk_auth_sessions_user_id_users",
        "fk_auth_sessions_parent_session_id_auth_sessions",
    }
    user_fk = fk_constraints["fk_auth_sessions_user_id_users"]
    assert [column.name for column in user_fk.columns] == ["user_id"]
    assert user_fk.referred_table.name == "users"
    assert [element.column.name for element in user_fk.elements] == ["id"]
    assert user_fk.ondelete == "CASCADE"
    parent_fk = fk_constraints["fk_auth_sessions_parent_session_id_auth_sessions"]
    assert [column.name for column in parent_fk.columns] == ["parent_session_id"]
    assert parent_fk.referred_table.name == "auth_sessions"
    assert [element.column.name for element in parent_fk.elements] == ["id"]
    assert parent_fk.ondelete == "RESTRICT"


def test_defaults_on_uuid_and_hash_columns() -> None:
    for name in ("user_id", "lineage_id", "parent_session_id"):
        assert _column(name).default is None, name
        assert _column(name).server_default is None, name
    for name in ("access_token_hash", "refresh_token_hash"):
        assert _column(name).default is None, name
        assert _column(name).server_default is None, name


def test_created_at_server_default_now_only() -> None:
    created_at = _column("created_at")
    assert str(created_at.server_default.arg) == "now()"
    assert created_at.default is None
    assert created_at.onupdate is None
    assert created_at.server_onupdate is None
    for name in ("last_used_at", "rotated_at", "revoked_at"):
        assert _column(name).default is None, name
        assert _column(name).server_default is None, name


def test_exactly_three_unique_constraints_single_column_each() -> None:
    uniques = {
        constraint.name: constraint
        for constraint in _table().constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert set(uniques) == {
        "uq_auth_sessions_access_token_hash",
        "uq_auth_sessions_refresh_token_hash",
        "uq_auth_sessions_parent_session_id",
    }
    expected_columns = {
        "uq_auth_sessions_access_token_hash": "access_token_hash",
        "uq_auth_sessions_refresh_token_hash": "refresh_token_hash",
        "uq_auth_sessions_parent_session_id": "parent_session_id",
    }
    for name, column_name in expected_columns.items():
        assert [column.name for column in uniques[name].columns] == [column_name]


def test_exactly_three_checks_by_name_and_normalized_sql() -> None:
    checks = {
        constraint.name: " ".join(str(constraint.sqltext).split())
        for constraint in _table().constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert checks == {
        "ck_auth_sessions_access_token_hash_32": "octet_length(access_token_hash) = 32",
        "ck_auth_sessions_refresh_token_hash_32": "octet_length(refresh_token_hash) = 32",
        "ck_auth_sessions_expiry_order": "refresh_expires_at > access_expires_at",
    }


def test_exactly_two_indexes_by_name_and_columns() -> None:
    indexes = {index.name: index for index in _table().indexes}
    assert set(indexes) == {"ix_auth_sessions_user_id", "ix_auth_sessions_lineage_id"}
    assert [column.name for column in indexes["ix_auth_sessions_user_id"].columns] == ["user_id"]
    assert [column.name for column in indexes["ix_auth_sessions_lineage_id"].columns] == [
        "lineage_id"
    ]


def test_no_relationships_on_auth_models() -> None:
    assert list(AuthSession.__mapper__.relationships) == []
    assert list(AuthCredential.__mapper__.relationships) == []


def test_auth_credential_metadata_unchanged_three_columns() -> None:
    credential_table = AuthCredential.__table__
    assert credential_table.name == "auth_credentials"
    assert set(credential_table.columns.keys()) == {"user_id", "password_hash", "password_changed_at"}
    assert len(credential_table.columns) == 3
    assert "auth_sessions" in Base.metadata.tables
