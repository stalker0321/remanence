"""Metadata-only assertions for recipient delivery state. No database connection."""

import uuid

from sqlalchemy import CheckConstraint, DateTime, Enum, ForeignKeyConstraint, UUID
from sqlalchemy.dialects import postgresql
from sqlalchemy.schema import CreateTable

from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.db.base import Base

EXPECTED_COLUMNS = (
    "recipient_user_id",
    "capsule_id",
    "state",
    "available_at",
    "ciphertext_synced_at",
)


def _table():
    return RecipientDeliveryState.__table__


def _column(name: str):
    return _table().columns[name]


def test_recipient_delivery_status_members_and_native_enum_exact() -> None:
    assert issubclass(RecipientDeliveryStatus, str)
    assert [member.name for member in RecipientDeliveryStatus] == [
        "AVAILABLE",
        "CIPHERTEXT_SYNCED",
    ]
    assert [member.value for member in RecipientDeliveryStatus] == [
        "AVAILABLE",
        "CIPHERTEXT_SYNCED",
    ]
    state_type = _column("state").type
    assert isinstance(state_type, Enum)
    assert state_type.name == "recipient_delivery_status"
    assert state_type.enum_class is RecipientDeliveryStatus
    assert state_type.native_enum is True
    assert list(state_type.enums) == ["AVAILABLE", "CIPHERTEXT_SYNCED"]


def test_table_name_and_exact_field_allow_list() -> None:
    assert RecipientDeliveryState.__tablename__ == "recipient_delivery_state"
    assert tuple(_table().columns.keys()) == EXPECTED_COLUMNS
    assert len(_table().columns) == 5


def test_composite_primary_key_order_and_column_types() -> None:
    assert [column.name for column in _table().primary_key.columns] == [
        "recipient_user_id",
        "capsule_id",
    ]
    for name in ("recipient_user_id", "capsule_id"):
        column = _column(name)
        assert isinstance(column.type, UUID), name
        assert column.type.python_type is uuid.UUID, name
    assert _column("state").nullable is False
    for name in ("available_at", "ciphertext_synced_at"):
        column = _column(name)
        assert isinstance(column.type, DateTime), name
        assert column.type.timezone is True, name
    assert _column("available_at").nullable is False
    assert _column("ciphertext_synced_at").nullable is True


def test_exactly_two_named_fks_have_required_delete_actions() -> None:
    fks = {
        constraint.name: constraint
        for constraint in _table().constraints
        if isinstance(constraint, ForeignKeyConstraint)
    }
    assert set(fks) == {
        "fk_recipient_delivery_state_recipient_user_id_users",
        "fk_recipient_delivery_state_capsule_id_capsules",
    }
    expected = {
        "fk_recipient_delivery_state_recipient_user_id_users": (
            "recipient_user_id",
            "users",
            "RESTRICT",
        ),
        "fk_recipient_delivery_state_capsule_id_capsules": (
            "capsule_id",
            "capsules",
            "CASCADE",
        ),
    }
    for name, (column_name, table_name, ondelete) in expected.items():
        fk = fks[name]
        assert [column.name for column in fk.columns] == [column_name]
        assert fk.referred_table.name == table_name
        assert [element.column.name for element in fk.elements] == ["id"]
        assert fk.ondelete == ondelete


def test_named_state_timestamp_coherence_check() -> None:
    checks = {
        constraint.name: " ".join(str(constraint.sqltext).split())
        for constraint in _table().constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert checks == {
        "ck_recipient_delivery_state_state_timestamp_coherence": (
            "((state = 'AVAILABLE' AND ciphertext_synced_at IS NULL) OR "
            "(state = 'CIPHERTEXT_SYNCED' AND ciphertext_synced_at IS NOT NULL))"
        )
    }


def test_only_available_at_has_server_now_default() -> None:
    for name in ("recipient_user_id", "capsule_id", "state", "ciphertext_synced_at"):
        column = _column(name)
        assert column.default is None, name
        assert column.server_default is None, name
    available_at = _column("available_at")
    assert available_at.default is None
    assert available_at.server_default is not None
    assert str(available_at.server_default.arg) == "now()"


def test_compiled_postgresql_ddl_has_native_state_and_no_secondary_indexes() -> None:
    ddl = " ".join(str(CreateTable(_table()).compile(dialect=postgresql.dialect())).split())
    assert "recipient_user_id UUID NOT NULL" in ddl
    assert "capsule_id UUID NOT NULL" in ddl
    assert "PRIMARY KEY (recipient_user_id, capsule_id)" in ddl
    assert "state recipient_delivery_status NOT NULL" in ddl
    assert "available_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL" in ddl
    assert "ciphertext_synced_at TIMESTAMP WITH TIME ZONE" in ddl
    assert "ON DELETE RESTRICT" in ddl
    assert "ON DELETE CASCADE" in ddl
    assert list(_table().indexes) == []


def test_no_privacy_forbidden_fields_or_orm_relationships() -> None:
    forbidden_fragments = (
        "received",
        "scanned",
        "recognized",
        "decrypted",
        "opened",
        "viewed",
        "sender",
        "private",
        "plaintext",
        "index_cached",
    )
    for name in _table().columns.keys():
        lowered = name.lower()
        assert not any(fragment in lowered for fragment in forbidden_fragments), name
    assert list(RecipientDeliveryState.__mapper__.relationships) == []
    assert "capsules" in Base.metadata.tables
    assert "users" in Base.metadata.tables
