"""Metadata-only assertions for the user key bundles table model. No database connection."""

import uuid

from sqlalchemy import CheckConstraint, DateTime, Enum, ForeignKeyConstraint, LargeBinary
from sqlalchemy import SmallInteger, String, UUID, UniqueConstraint
from sqlalchemy.dialects import postgresql

from remanence.db.base import Base
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle

EXPECTED_COLUMNS = frozenset(
    {
        "id",
        "user_id",
        "encryption_public_keyset",
        "signing_public_keyset",
        "suite",
        "protocol_version",
        "status",
        "created_at",
        "retired_at",
    }
)
NON_NULL_COLUMNS = frozenset(
    {
        "id",
        "user_id",
        "encryption_public_keyset",
        "signing_public_keyset",
        "suite",
        "protocol_version",
        "status",
        "created_at",
    }
)


def _table():
    return UserKeyBundle.__table__


def _column(name: str):
    return _table().columns[name]


def test_key_bundle_status_members_exact() -> None:
    assert issubclass(KeyBundleStatus, str)
    assert [member.name for member in KeyBundleStatus] == ["ACTIVE", "RETIRED", "REVOKED"]
    assert [member.value for member in KeyBundleStatus] == ["ACTIVE", "RETIRED", "REVOKED"]


def test_table_name_and_exact_column_set() -> None:
    assert UserKeyBundle.__tablename__ == "user_key_bundles"
    assert set(_table().columns.keys()) == EXPECTED_COLUMNS
    assert len(_table().columns) == 9


def test_column_types_and_nullability_exact() -> None:
    for name in ("id", "user_id"):
        column = _column(name)
        assert isinstance(column.type, UUID), name
        assert column.type.python_type is uuid.UUID, name
    for name in ("encryption_public_keyset", "signing_public_keyset"):
        column = _column(name)
        assert isinstance(column.type, LargeBinary), name
        assert column.type.python_type is bytes, name
    suite = _column("suite")
    assert isinstance(suite.type, String)
    assert suite.type.length == 80
    protocol_version = _column("protocol_version")
    assert isinstance(protocol_version.type, SmallInteger)
    assert protocol_version.type.python_type is int
    status = _column("status")
    assert isinstance(status.type, Enum)
    created_at = _column("created_at")
    retired_at = _column("retired_at")
    assert isinstance(created_at.type, DateTime)
    assert created_at.type.timezone is True
    assert isinstance(retired_at.type, DateTime)
    assert retired_at.type.timezone is True
    for name in NON_NULL_COLUMNS:
        assert _column(name).nullable is False, name
    assert _column("retired_at").nullable is True


def test_id_has_no_default_and_no_server_default() -> None:
    id_column = _column("id")
    assert id_column.default is None
    assert id_column.server_default is None


def test_public_key_columns_have_no_defaults_or_server_defaults() -> None:
    for name in ("encryption_public_keyset", "signing_public_keyset"):
        column = _column(name)
        assert column.default is None, name
        assert column.server_default is None, name


def test_exactly_one_named_cascade_fk_to_users_id() -> None:
    fk_constraints = [
        constraint
        for constraint in _table().constraints
        if isinstance(constraint, ForeignKeyConstraint)
    ]
    assert len(fk_constraints) == 1
    fk_constraint = fk_constraints[0]
    assert fk_constraint.name == "fk_user_key_bundles_user_id_users"
    assert [column.name for column in fk_constraint.columns] == ["user_id"]
    assert fk_constraint.referred_table.name == "users"
    assert [element.column.name for element in fk_constraint.elements] == ["id"]
    assert fk_constraint.ondelete == "CASCADE"


def test_status_enum_sql_name_native_and_values() -> None:
    status_type = _column("status").type
    assert isinstance(status_type, Enum)
    assert status_type.name == "key_bundle_status"
    assert status_type.enum_class is KeyBundleStatus
    assert status_type.native_enum is True
    assert list(status_type.enums) == ["ACTIVE", "RETIRED", "REVOKED"]


def test_exactly_one_positive_version_check() -> None:
    checks = {
        constraint.name: constraint
        for constraint in _table().constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert set(checks) == {"ck_user_key_bundles_protocol_version_positive"}
    sqltext = " ".join(str(checks["ck_user_key_bundles_protocol_version_positive"].sqltext).split())
    assert sqltext == "protocol_version > 0"


def test_single_partial_unique_index_with_compiled_postgres_predicate() -> None:
    indexes = list(_table().indexes)
    assert len(indexes) == 1
    index = indexes[0]
    assert index.name == "uq_user_key_bundles_one_active_per_user"
    assert [column.name for column in index.columns] == ["user_id"]
    assert index.unique is True
    predicate = index.dialect_options["postgresql"]["where"]
    compiled = str(predicate.compile(dialect=postgresql.dialect()))
    assert " ".join(compiled.split()) == "status = 'ACTIVE'"


def test_created_at_server_default_now_only_and_retired_at_plain() -> None:
    created_at = _column("created_at")
    assert str(created_at.server_default.arg) == "now()"
    assert created_at.default is None
    assert created_at.onupdate is None
    assert created_at.server_onupdate is None
    retired_at = _column("retired_at")
    assert retired_at.default is None
    assert retired_at.server_default is None


def test_no_unique_constraints_relationships_private_or_secret_columns() -> None:
    assert not [
        constraint
        for constraint in _table().constraints
        if isinstance(constraint, UniqueConstraint)
    ]
    assert list(UserKeyBundle.__mapper__.relationships) == []
    for name in _table().columns.keys():
        lowered = name.lower()
        assert "private" not in lowered, name
        assert "secret" not in lowered, name


def test_users_table_registered_in_shared_metadata_on_key_models_import() -> None:
    assert "users" in Base.metadata.tables
    assert Base.metadata.tables["users"].name == "users"
