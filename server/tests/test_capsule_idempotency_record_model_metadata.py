"""Metadata-only assertions for scoped capsule idempotency records. No database connection."""

import uuid

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKeyConstraint,
    LargeBinary,
    SmallInteger,
    String,
    UUID,
)
from sqlalchemy.dialects.postgresql import JSONB, dialect
from sqlalchemy.schema import CreateIndex, CreateTable

from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.db.base import Base

EXPECTED_COLUMNS = (
    "owner_user_id",
    "method",
    "normalized_route",
    "idempotency_key",
    "request_sha256",
    "response_status",
    "response_json",
    "created_at",
    "expires_at",
)


def _table():
    return CapsuleIdempotencyRecord.__table__


def _column(name: str):
    return _table().columns[name]


def test_table_name_and_exact_scope_field_allow_list() -> None:
    assert CapsuleIdempotencyRecord.__tablename__ == "capsule_idempotency_records"
    assert tuple(_table().columns.keys()) == EXPECTED_COLUMNS
    assert len(_table().columns) == 9


def test_scope_composite_primary_key_order_and_types() -> None:
    assert [column.name for column in _table().primary_key.columns] == [
        "owner_user_id",
        "method",
        "normalized_route",
        "idempotency_key",
    ]
    for name in ("owner_user_id", "idempotency_key"):
        column = _column(name)
        assert isinstance(column.type, UUID), name
        assert column.type.python_type is uuid.UUID, name
    method = _column("method")
    assert isinstance(method.type, String)
    assert method.type.length == 8
    route = _column("normalized_route")
    assert isinstance(route.type, String)
    assert route.type.length == 512
    request_sha256 = _column("request_sha256")
    assert isinstance(request_sha256.type, LargeBinary)
    assert request_sha256.type.length == 32
    assert request_sha256.type.python_type is bytes
    response_status = _column("response_status")
    assert isinstance(response_status.type, SmallInteger)
    assert response_status.type.python_type is int
    response_json = _column("response_json")
    assert isinstance(response_json.type, JSONB)
    for name in ("created_at", "expires_at"):
        column = _column(name)
        assert isinstance(column.type, DateTime), name
        assert column.type.timezone is True, name
    for name in EXPECTED_COLUMNS:
        assert _column(name).nullable is False, name


def test_exactly_one_named_cascade_fk_to_users() -> None:
    fks = [
        constraint
        for constraint in _table().constraints
        if isinstance(constraint, ForeignKeyConstraint)
    ]
    assert len(fks) == 1
    fk = fks[0]
    assert fk.name == "fk_capsule_idempotency_records_owner_user_id_users"
    assert [column.name for column in fk.columns] == ["owner_user_id"]
    assert fk.referred_table.name == "users"
    assert [element.column.name for element in fk.elements] == ["id"]
    assert fk.ondelete == "CASCADE"


def test_exactly_four_named_checks_and_normalized_sql() -> None:
    checks = {
        constraint.name: " ".join(str(constraint.sqltext).split())
        for constraint in _table().constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert checks == {
        "ck_capsule_idempotency_records_method_uppercase": (
            "method IN ('POST', 'PUT', 'PATCH', 'DELETE') AND method = upper(method)"
        ),
        "ck_capsule_idempotency_records_request_sha256_32": (
            "octet_length(request_sha256) = 32"
        ),
        "ck_capsule_idempotency_records_response_status_range": (
            "response_status BETWEEN 200 AND 599"
        ),
        "ck_capsule_idempotency_records_expiry_order": "expires_at > created_at",
    }


def test_only_created_at_has_server_now_default() -> None:
    for name in EXPECTED_COLUMNS:
        column = _column(name)
        assert column.default is None, name
        if name != "created_at":
            assert column.server_default is None, name
    created_at = _column("created_at")
    assert created_at.server_default is not None
    assert str(created_at.server_default.arg) == "now()"


def test_expiry_index_is_the_only_secondary_index_and_compiles_for_postgresql() -> None:
    indexes = list(_table().indexes)
    assert len(indexes) == 1
    index = indexes[0]
    assert index.name == "ix_capsule_idempotency_records_expires_at"
    assert [column.name for column in index.columns] == ["expires_at"]
    assert index.unique is False
    compiled = str(CreateIndex(index).compile(dialect=dialect()))
    assert " ".join(compiled.split()) == (
        "CREATE INDEX ix_capsule_idempotency_records_expires_at "
        "ON capsule_idempotency_records (expires_at)"
    )


def test_compiled_postgresql_ddl_has_jsonb_and_scoped_constraints() -> None:
    ddl = " ".join(str(CreateTable(_table()).compile(dialect=dialect())).split())
    assert "owner_user_id UUID NOT NULL" in ddl
    assert "method VARCHAR(8) NOT NULL" in ddl
    assert "normalized_route VARCHAR(512) NOT NULL" in ddl
    assert "idempotency_key UUID NOT NULL" in ddl
    assert "PRIMARY KEY (owner_user_id, method, normalized_route, idempotency_key)" in ddl
    assert "request_sha256 BYTEA NOT NULL" in ddl
    assert "response_status SMALLINT NOT NULL" in ddl
    assert "response_json JSONB NOT NULL" in ddl
    assert "expires_at TIMESTAMP WITH TIME ZONE NOT NULL" in ddl
    assert "method IN ('POST', 'PUT', 'PATCH', 'DELETE') AND method = upper(method)" in ddl
    assert "response_status BETWEEN 200 AND 599" in ddl
    assert "expires_at > created_at" in ddl


def test_no_auth_token_or_capsule_private_data_and_no_relationships() -> None:
    forbidden_fragments = (
        "header",
        "token",
        "body",
        "email",
        "handle",
        "plaintext",
        "private",
        "secret",
        "keyset",
        "stack",
        "detail",
    )
    for name in _table().columns.keys():
        lowered = name.lower()
        assert not any(fragment in lowered for fragment in forbidden_fragments), name
    assert list(CapsuleIdempotencyRecord.__mapper__.relationships) == []
    assert "users" in Base.metadata.tables
