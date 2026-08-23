"""Metadata-only assertions for the users table model. No database connection."""

import uuid

from sqlalchemy import DateTime, FetchedValue, String, UUID, UniqueConstraint

from postmark.users.models import User

EXPECTED_COLUMNS = frozenset(
    {
        "id",
        "email_normalized",
        "handle_normalized",
        "handle_display",
        "created_at",
        "updated_at",
        "disabled_at",
    }
)
REQUIRED_COLUMNS = (
    "id",
    "email_normalized",
    "handle_normalized",
    "handle_display",
    "created_at",
    "updated_at",
)


def _column(name: str):
    return User.__table__.columns[name]


def test_table_name_and_exact_column_set() -> None:
    assert User.__tablename__ == "users"
    assert set(User.__table__.columns.keys()) == EXPECTED_COLUMNS
    assert len(User.__table__.columns) == 7


def test_primary_key_is_id_with_python_uuid4_default() -> None:
    pk_columns = list(User.__table__.primary_key.columns)
    assert [column.name for column in pk_columns] == ["id"]
    id_column = _column("id")
    assert id_column.default is not None
    assert id_column.default.is_callable
    assert callable(id_column.default.arg)
    assert isinstance(id_column.default.arg(None), uuid.UUID)
    assert id_column.server_default is None


def test_column_types_lengths_and_timezones() -> None:
    email = _column("email_normalized")
    assert isinstance(email.type, String)
    assert email.type.length == 320
    for name in ("handle_normalized", "handle_display"):
        column = _column(name)
        assert isinstance(column.type, String)
        assert column.type.length == 30
    for name in ("created_at", "updated_at", "disabled_at"):
        column = _column(name)
        assert isinstance(column.type, DateTime)
        assert column.type.timezone is True
    assert isinstance(_column("id").type, UUID)


def test_nullability_matches_schema() -> None:
    for name in REQUIRED_COLUMNS:
        assert _column(name).nullable is False, name
    assert _column("disabled_at").nullable is True


def test_named_unique_constraints_exactly_for_normalized_columns() -> None:
    uniques = {
        constraint.name: constraint
        for constraint in User.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert set(uniques) == {"uq_users_email_normalized", "uq_users_handle_normalized"}
    assert [column.name for column in uniques["uq_users_email_normalized"].columns] == [
        "email_normalized"
    ]
    assert [column.name for column in uniques["uq_users_handle_normalized"].columns] == [
        "handle_normalized"
    ]


def test_timestamp_server_defaults_and_only_updated_at_onupdate() -> None:
    for name in ("created_at", "updated_at"):
        column = _column(name)
        assert column.server_default is not None, name
        assert str(column.server_default.arg) == "now()"
        assert column.default is None, name
    assert isinstance(_column("updated_at").server_onupdate, FetchedValue)
    for name in ("created_at", "disabled_at"):
        assert _column(name).server_onupdate is None, name


def test_no_foreign_keys_or_indexes() -> None:
    assert list(User.__table__.foreign_keys) == []
    assert list(User.__table__.indexes) == []
    for column in User.__table__.columns:
        assert not column.foreign_keys, column.name
        assert not column.index, column.name
