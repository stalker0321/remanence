"""Metadata-only assertions for the auth credentials table model. No database connection."""

import uuid

from sqlalchemy import DateTime, ForeignKeyConstraint, String, UUID, UniqueConstraint

from remanence.auth.models import AuthCredential
from remanence.db.base import Base

EXPECTED_COLUMNS = frozenset({"user_id", "password_hash", "password_changed_at"})


def _column(name: str):
    return AuthCredential.__table__.columns[name]


def test_table_name_and_exact_column_set() -> None:
    assert AuthCredential.__tablename__ == "auth_credentials"
    assert set(AuthCredential.__table__.columns.keys()) == EXPECTED_COLUMNS
    assert len(AuthCredential.__table__.columns) == 3


def test_primary_key_is_user_id_uuid_without_default() -> None:
    pk_columns = list(AuthCredential.__table__.primary_key.columns)
    assert [column.name for column in pk_columns] == ["user_id"]
    user_id = _column("user_id")
    assert isinstance(user_id.type, UUID)
    assert user_id.type.python_type is uuid.UUID
    assert user_id.default is None
    assert user_id.server_default is None
    assert user_id.nullable is False


def test_exactly_one_named_cascade_fk_to_users_id() -> None:
    fk_constraints = [
        constraint
        for constraint in AuthCredential.__table__.constraints
        if isinstance(constraint, ForeignKeyConstraint)
    ]
    assert len(fk_constraints) == 1
    fk_constraint = fk_constraints[0]
    assert fk_constraint.name == "fk_auth_credentials_user_id_users"
    assert [column.name for column in fk_constraint.columns] == ["user_id"]
    assert fk_constraint.referred_table.name == "users"
    assert [element.column.name for element in fk_constraint.elements] == ["id"]
    assert fk_constraint.ondelete == "CASCADE"


def test_password_hash_is_string_512_non_null_without_defaults() -> None:
    password_hash = _column("password_hash")
    assert isinstance(password_hash.type, String)
    assert password_hash.type.length == 512
    assert password_hash.nullable is False
    assert password_hash.default is None
    assert password_hash.server_default is None


def test_password_changed_at_timezone_server_default_now_no_onupdate() -> None:
    changed_at = _column("password_changed_at")
    assert isinstance(changed_at.type, DateTime)
    assert changed_at.type.timezone is True
    assert changed_at.nullable is False
    assert str(changed_at.server_default.arg) == "now()"
    assert changed_at.default is None
    assert changed_at.onupdate is None
    assert changed_at.server_onupdate is None


def test_no_unique_constraints_indexes_or_relationships() -> None:
    assert not [
        constraint
        for constraint in AuthCredential.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    ]
    assert list(AuthCredential.__table__.indexes) == []
    assert list(AuthCredential.__mapper__.relationships) == []
    assert not _column("password_hash").index
    assert not _column("password_changed_at").index


def test_users_table_registered_in_shared_metadata_on_auth_models_import() -> None:
    assert "users" in Base.metadata.tables
    assert Base.metadata.tables["users"].name == "users"
