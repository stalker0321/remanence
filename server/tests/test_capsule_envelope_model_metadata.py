"""Metadata-only assertions for recipient capsule envelopes. No database connection."""

import uuid

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKeyConstraint,
    Integer,
    LargeBinary,
    UUID,
)
from sqlalchemy.schema import CreateTable
from sqlalchemy.dialects import postgresql

from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.db.base import Base

EXPECTED_COLUMNS = frozenset(
    {
        "capsule_id",
        "recipient_user_id",
        "recipient_key_bundle_id",
        "ciphertext",
        "ciphertext_size",
        "ciphertext_sha256",
        "created_at",
    }
)


def _table():
    return CapsuleEnvelope.__table__


def _column(name: str):
    return _table().columns[name]


def test_table_name_and_exact_column_set() -> None:
    assert CapsuleEnvelope.__tablename__ == "capsule_envelopes"
    assert set(_table().columns.keys()) == EXPECTED_COLUMNS
    assert len(_table().columns) == 7


def test_column_types_and_nullability_exact() -> None:
    for name in ("capsule_id", "recipient_user_id", "recipient_key_bundle_id"):
        column = _column(name)
        assert isinstance(column.type, UUID), name
        assert column.type.python_type is uuid.UUID, name
    ciphertext = _column("ciphertext")
    assert isinstance(ciphertext.type, LargeBinary)
    assert ciphertext.type.length is None
    ciphertext_size = _column("ciphertext_size")
    assert isinstance(ciphertext_size.type, Integer)
    assert ciphertext_size.type.python_type is int
    ciphertext_sha256 = _column("ciphertext_sha256")
    assert isinstance(ciphertext_sha256.type, LargeBinary)
    assert ciphertext_sha256.type.length == 32
    assert ciphertext_sha256.type.python_type is bytes
    created_at = _column("created_at")
    assert isinstance(created_at.type, DateTime)
    assert created_at.type.timezone is True
    for name in EXPECTED_COLUMNS:
        assert _column(name).nullable is False, name


def test_capsule_id_is_the_only_primary_key_without_a_default() -> None:
    assert [column.name for column in _table().primary_key.columns] == ["capsule_id"]
    for name in EXPECTED_COLUMNS - {"created_at"}:
        column = _column(name)
        assert column.default is None, name
        assert column.server_default is None, name
    assert _column("created_at").server_default is not None
    assert str(_column("created_at").server_default.arg) == "now()"


def test_exactly_three_named_fks_with_required_delete_actions() -> None:
    fks = {
        constraint.name: constraint
        for constraint in _table().constraints
        if isinstance(constraint, ForeignKeyConstraint)
    }
    assert set(fks) == {
        "fk_capsule_envelopes_capsule_id_capsules",
        "fk_capsule_envelopes_recipient_user_id_users",
        "fk_capsule_envelopes_recipient_key_bundle_id_user_key_bundles",
    }
    expected = {
        "fk_capsule_envelopes_capsule_id_capsules": (
            "capsule_id",
            "capsules",
            "CASCADE",
        ),
        "fk_capsule_envelopes_recipient_user_id_users": (
            "recipient_user_id",
            "users",
            "RESTRICT",
        ),
        "fk_capsule_envelopes_recipient_key_bundle_id_user_key_bundles": (
            "recipient_key_bundle_id",
            "user_key_bundles",
            "RESTRICT",
        ),
    }
    for name, (column_name, table_name, ondelete) in expected.items():
        fk = fks[name]
        assert [column.name for column in fk.columns] == [column_name]
        assert fk.referred_table.name == table_name
        assert [element.column.name for element in fk.elements] == ["id"]
        assert fk.ondelete == ondelete


def test_exactly_three_named_checks_and_normalized_sql() -> None:
    checks = {
        constraint.name: " ".join(str(constraint.sqltext).split())
        for constraint in _table().constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert checks == {
        "ck_capsule_envelopes_ciphertext_size_bounds": (
            "ciphertext_size > 0 AND ciphertext_size <= 16384"
        ),
        "ck_capsule_envelopes_ciphertext_size_matches": (
            "octet_length(ciphertext) = ciphertext_size"
        ),
        "ck_capsule_envelopes_ciphertext_sha256_32": "octet_length(ciphertext_sha256) = 32",
    }


def test_compiled_postgresql_ddl_contains_16_kib_cap_and_no_secondary_indexes() -> None:
    ddl = " ".join(str(CreateTable(_table()).compile(dialect=postgresql.dialect())).split())
    assert "capsule_id UUID NOT NULL" in ddl
    assert "ciphertext BYTEA NOT NULL" in ddl
    assert "ciphertext_size INTEGER NOT NULL" in ddl
    assert "ciphertext_size > 0 AND ciphertext_size <= 16384" in ddl
    assert "octet_length(ciphertext) = ciphertext_size" in ddl
    assert "octet_length(ciphertext_sha256) = 32" in ddl
    assert "ON DELETE CASCADE" in ddl
    assert ddl.count("ON DELETE RESTRICT") == 2
    assert list(_table().indexes) == []


def test_no_relationships_private_fields_or_extra_metadata() -> None:
    assert list(CapsuleEnvelope.__mapper__.relationships) == []
    assert "capsules" in Base.metadata.tables
    assert "users" in Base.metadata.tables
    assert "user_key_bundles" in Base.metadata.tables
    for name in _table().columns.keys():
        lowered = name.lower()
        assert "plaintext" not in lowered, name
        assert "private" not in lowered, name
