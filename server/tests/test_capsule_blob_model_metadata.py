"""Metadata-only assertions for capsule blob declarations. No database connection."""

import uuid

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Enum,
    ForeignKeyConstraint,
    LargeBinary,
    SmallInteger,
    String,
    UUID,
    UniqueConstraint,
)
from sqlalchemy.dialects import postgresql
from sqlalchemy.schema import CreateIndex

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.db.base import Base

EXPECTED_COLUMNS = frozenset(
    {
        "id",
        "capsule_id",
        "kind",
        "ordinal",
        "object_key",
        "expected_ciphertext_size",
        "expected_ciphertext_sha256",
        "state",
    }
)
NON_NULL_COLUMNS = frozenset(
    {
        "id",
        "capsule_id",
        "kind",
        "object_key",
        "expected_ciphertext_size",
        "expected_ciphertext_sha256",
        "state",
    }
)


def _table():
    return CapsuleBlob.__table__


def _column(name: str):
    return _table().columns[name]


def test_blob_enums_members_exact() -> None:
    assert issubclass(CapsuleBlobKind, str)
    assert [member.name for member in CapsuleBlobKind] == [
        "RECOGNITION_MANIFEST",
        "CONTENT_MANIFEST",
        "PHOTO",
    ]
    assert [member.value for member in CapsuleBlobKind] == [
        "RECOGNITION_MANIFEST",
        "CONTENT_MANIFEST",
        "PHOTO",
    ]
    assert issubclass(CapsuleBlobState, str)
    assert [member.name for member in CapsuleBlobState] == ["DECLARED", "STORED"]
    assert [member.value for member in CapsuleBlobState] == ["DECLARED", "STORED"]


def test_table_name_and_exact_column_set() -> None:
    assert CapsuleBlob.__tablename__ == "capsule_blobs"
    assert set(_table().columns.keys()) == EXPECTED_COLUMNS
    assert len(_table().columns) == 8


def test_column_types_and_nullability_exact() -> None:
    for name in ("id", "capsule_id"):
        column = _column(name)
        assert isinstance(column.type, UUID), name
        assert column.type.python_type is uuid.UUID, name
    kind = _column("kind")
    assert isinstance(kind.type, Enum)
    assert kind.type.name == "capsule_blob_kind"
    assert kind.type.enum_class is CapsuleBlobKind
    assert kind.type.native_enum is True
    assert list(kind.type.enums) == ["RECOGNITION_MANIFEST", "CONTENT_MANIFEST", "PHOTO"]
    ordinal = _column("ordinal")
    assert isinstance(ordinal.type, SmallInteger)
    assert ordinal.type.python_type is int
    object_key = _column("object_key")
    assert isinstance(object_key.type, String)
    assert object_key.type.length == 512
    size = _column("expected_ciphertext_size")
    assert isinstance(size.type, BigInteger)
    assert size.type.python_type is int
    digest = _column("expected_ciphertext_sha256")
    assert isinstance(digest.type, LargeBinary)
    assert digest.type.length == 32
    assert digest.type.python_type is bytes
    state = _column("state")
    assert isinstance(state.type, Enum)
    assert state.type.name == "capsule_blob_state"
    assert state.type.enum_class is CapsuleBlobState
    assert state.type.native_enum is True
    assert list(state.type.enums) == ["DECLARED", "STORED"]
    for name in NON_NULL_COLUMNS:
        assert _column(name).nullable is False, name
    assert _column("ordinal").nullable is True


def test_client_generated_id_and_all_fields_have_no_defaults() -> None:
    for name in EXPECTED_COLUMNS:
        column = _column(name)
        assert column.default is None, name
        assert column.server_default is None, name
    id_column = _column("id")
    assert id_column.primary_key is True


def test_exactly_one_named_cascade_fk_to_capsules() -> None:
    fks = [
        constraint
        for constraint in _table().constraints
        if isinstance(constraint, ForeignKeyConstraint)
    ]
    assert len(fks) == 1
    fk = fks[0]
    assert fk.name == "fk_capsule_blobs_capsule_id_capsules"
    assert [column.name for column in fk.columns] == ["capsule_id"]
    assert fk.referred_table.name == "capsules"
    assert [element.column.name for element in fk.elements] == ["id"]
    assert fk.ondelete == "CASCADE"


def test_object_key_is_globally_unique_by_named_constraint() -> None:
    uniques = {
        constraint.name: constraint
        for constraint in _table().constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert set(uniques) == {"uq_capsule_blobs_object_key"}
    assert [column.name for column in uniques["uq_capsule_blobs_object_key"].columns] == [
        "object_key"
    ]


def test_exactly_three_named_checks_and_normalized_sql() -> None:
    checks = {
        constraint.name: " ".join(str(constraint.sqltext).split())
        for constraint in _table().constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert checks == {
        "ck_capsule_blobs_expected_ciphertext_size_positive": "expected_ciphertext_size > 0",
        "ck_capsule_blobs_expected_ciphertext_sha256_32": (
            "octet_length(expected_ciphertext_sha256) = 32"
        ),
        "ck_capsule_blobs_kind_ordinal_shape": (
            "((kind = 'PHOTO' AND ordinal IS NOT NULL AND ordinal BETWEEN 0 AND 4) OR "
            "(kind IN ('RECOGNITION_MANIFEST', 'CONTENT_MANIFEST') AND ordinal IS NULL))"
        ),
    }


def test_capsule_id_index_and_partial_unique_indexes_have_postgresql_ddl() -> None:
    indexes = {index.name: index for index in _table().indexes}
    assert set(indexes) == {
        "ix_capsule_blobs_capsule_id",
        "uq_capsule_blobs_one_recognition_manifest_per_capsule",
        "uq_capsule_blobs_one_content_manifest_per_capsule",
        "uq_capsule_blobs_photo_ordinal_per_capsule",
    }
    assert [column.name for column in indexes["ix_capsule_blobs_capsule_id"].columns] == [
        "capsule_id"
    ]
    assert indexes["ix_capsule_blobs_capsule_id"].unique is False
    expected = {
        "uq_capsule_blobs_one_recognition_manifest_per_capsule": (
            ["capsule_id"],
            "kind = 'RECOGNITION_MANIFEST'",
        ),
        "uq_capsule_blobs_one_content_manifest_per_capsule": (
            ["capsule_id"],
            "kind = 'CONTENT_MANIFEST'",
        ),
        "uq_capsule_blobs_photo_ordinal_per_capsule": (
            ["capsule_id", "ordinal"],
            "kind = 'PHOTO'",
        ),
    }
    for name, (columns, predicate) in expected.items():
        index = indexes[name]
        assert [column.name for column in index.columns] == columns
        assert index.unique is True
        where = index.dialect_options["postgresql"]["where"]
        compiled_where = str(where.compile(dialect=postgresql.dialect()))
        assert " ".join(compiled_where.split()) == predicate
        compiled_index = str(CreateIndex(index).compile(dialect=postgresql.dialect()))
        assert " ".join(compiled_index.split()) == (
            f"CREATE UNIQUE INDEX {name} ON capsule_blobs ({', '.join(columns)}) WHERE {predicate}"
        )


def test_no_relationships_and_capsule_metadata_is_registered() -> None:
    assert list(CapsuleBlob.__mapper__.relationships) == []
    assert "capsules" in Base.metadata.tables
