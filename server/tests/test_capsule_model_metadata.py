"""Metadata-only assertions for the capsules table model. No database connection."""

import uuid

from sqlalchemy import CheckConstraint, DateTime, Enum, ForeignKeyConstraint, LargeBinary
from sqlalchemy import SmallInteger, UUID

from remanence.capsules.models import Capsule, CapsuleState
from remanence.db.base import Base

EXPECTED_COLUMNS = frozenset(
    {
        "id",
        "sender_user_id",
        "recipient_user_id",
        "sender_key_bundle_id",
        "recipient_key_bundle_id",
        "protocol_version",
        "state",
        "signed_statement",
        "signed_statement_sha256",
        "publish_signature",
        "created_at",
        "ready_at",
        "draft_expires_at",
    }
)
NON_NULL_COLUMNS = frozenset(
    {
        "id",
        "sender_user_id",
        "recipient_user_id",
        "sender_key_bundle_id",
        "recipient_key_bundle_id",
        "protocol_version",
        "state",
        "created_at",
        "draft_expires_at",
    }
)


def _table():
    return Capsule.__table__


def _column(name: str):
    return _table().columns[name]


def test_capsule_state_members_exact() -> None:
    assert issubclass(CapsuleState, str)
    assert [member.name for member in CapsuleState] == ["DRAFT", "READY", "ABORTED"]
    assert [member.value for member in CapsuleState] == ["DRAFT", "READY", "ABORTED"]


def test_table_name_and_exact_column_set() -> None:
    assert Capsule.__tablename__ == "capsules"
    assert set(_table().columns.keys()) == EXPECTED_COLUMNS
    assert len(_table().columns) == 13


def test_column_types_and_nullability_exact() -> None:
    for name in (
        "id",
        "sender_user_id",
        "recipient_user_id",
        "sender_key_bundle_id",
        "recipient_key_bundle_id",
    ):
        column = _column(name)
        assert isinstance(column.type, UUID), name
        assert column.type.python_type is uuid.UUID, name
    protocol_version = _column("protocol_version")
    assert isinstance(protocol_version.type, SmallInteger)
    assert protocol_version.type.python_type is int
    for name in ("signed_statement", "signed_statement_sha256", "publish_signature"):
        column = _column(name)
        assert isinstance(column.type, LargeBinary), name
        assert column.type.python_type is bytes, name
    assert _column("signed_statement").type.length is None
    assert _column("signed_statement_sha256").type.length == 32
    assert _column("publish_signature").type.length == 69
    state = _column("state")
    assert isinstance(state.type, Enum)
    assert state.type.name == "capsule_state"
    assert state.type.enum_class is CapsuleState
    assert state.type.native_enum is True
    assert list(state.type.enums) == ["DRAFT", "READY", "ABORTED"]
    for name in ("created_at", "ready_at", "draft_expires_at"):
        column = _column(name)
        assert isinstance(column.type, DateTime), name
        assert column.type.timezone is True, name
    for name in NON_NULL_COLUMNS:
        assert _column(name).nullable is False, name
    for name in EXPECTED_COLUMNS - NON_NULL_COLUMNS:
        assert _column(name).nullable is True, name


def test_client_generated_id_and_plain_field_defaults() -> None:
    id_column = _column("id")
    assert id_column.default is None
    assert id_column.server_default is None
    for name in (
        "sender_user_id",
        "recipient_user_id",
        "sender_key_bundle_id",
        "recipient_key_bundle_id",
        "protocol_version",
        "state",
        "signed_statement",
        "signed_statement_sha256",
        "publish_signature",
        "ready_at",
        "draft_expires_at",
    ):
        column = _column(name)
        assert column.default is None, name
        assert column.server_default is None, name


def test_created_at_has_only_server_now_default() -> None:
    created_at = _column("created_at")
    assert str(created_at.server_default.arg) == "now()"
    assert created_at.default is None
    assert created_at.onupdate is None
    assert created_at.server_onupdate is None


def test_exactly_four_named_restrict_fks() -> None:
    fks = {
        constraint.name: constraint
        for constraint in _table().constraints
        if isinstance(constraint, ForeignKeyConstraint)
    }
    assert set(fks) == {
        "fk_capsules_sender_user_id_users",
        "fk_capsules_recipient_user_id_users",
        "fk_capsules_sender_key_bundle_id_user_key_bundles",
        "fk_capsules_recipient_key_bundle_id_user_key_bundles",
    }
    expected = {
        "fk_capsules_sender_user_id_users": ("sender_user_id", "users"),
        "fk_capsules_recipient_user_id_users": ("recipient_user_id", "users"),
        "fk_capsules_sender_key_bundle_id_user_key_bundles": (
            "sender_key_bundle_id",
            "user_key_bundles",
        ),
        "fk_capsules_recipient_key_bundle_id_user_key_bundles": (
            "recipient_key_bundle_id",
            "user_key_bundles",
        ),
    }
    for name, (column_name, table_name) in expected.items():
        constraint = fks[name]
        assert [column.name for column in constraint.columns] == [column_name]
        assert constraint.referred_table.name == table_name
        assert [element.column.name for element in constraint.elements] == ["id"]
        assert constraint.ondelete == "RESTRICT"


def test_exactly_five_named_checks_and_normalized_sql() -> None:
    checks = {
        constraint.name: " ".join(str(constraint.sqltext).split())
        for constraint in _table().constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert checks == {
        "ck_capsules_protocol_version_positive": "protocol_version > 0",
        "ck_capsules_draft_expiry_order": "draft_expires_at > created_at",
        "ck_capsules_signed_statement_sha256_32": (
            "signed_statement_sha256 IS NULL OR octet_length(signed_statement_sha256) = 32"
        ),
        "ck_capsules_publish_signature_69": (
            "publish_signature IS NULL OR octet_length(publish_signature) = 69"
        ),
        "ck_capsules_state_finalization_shape": (
            "((state = 'READY' AND ready_at IS NOT NULL AND signed_statement IS NOT NULL "
            "AND signed_statement_sha256 IS NOT NULL AND publish_signature IS NOT NULL) OR "
            "(state IN ('DRAFT', 'ABORTED') "
            "AND ready_at IS NULL AND signed_statement IS NULL "
            "AND signed_statement_sha256 IS NULL AND publish_signature IS NULL))"
        ),
    }


def test_exactly_three_named_indexes() -> None:
    indexes = {index.name: index for index in _table().indexes}
    assert set(indexes) == {
        "ix_capsules_sender_user_id",
        "ix_capsules_recipient_user_id",
        "ix_capsules_draft_expires_at",
    }
    expected_columns = {
        "ix_capsules_sender_user_id": ["sender_user_id"],
        "ix_capsules_recipient_user_id": ["recipient_user_id"],
        "ix_capsules_draft_expires_at": ["draft_expires_at"],
    }
    for name, columns in expected_columns.items():
        assert [column.name for column in indexes[name].columns] == columns


def test_self_send_is_not_forbidden_and_no_orm_relationships() -> None:
    assert not [
        constraint
        for constraint in _table().constraints
        if isinstance(constraint, CheckConstraint)
        and "sender_user_id" in str(constraint.sqltext)
        and "recipient_user_id" in str(constraint.sqltext)
    ]
    assert list(Capsule.__mapper__.relationships) == []


def test_publish_signature_is_opaque_public_signature_bytes_only() -> None:
    assert _column("publish_signature").type.python_type is bytes
    assert "private" not in {column.name.lower() for column in _table().columns}
    assert "private_key" not in {column.name.lower() for column in _table().columns}


def test_referenced_tables_are_registered_in_shared_metadata() -> None:
    assert "users" in Base.metadata.tables
    assert "user_key_bundles" in Base.metadata.tables
