"""Tests for the protocol-v1 canonical publish-statement boundary."""

import copy
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID, uuid4

import pytest

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.publish_statement import (
    MAX_PUBLISH_STATEMENT_BYTES,
    PublishStatementInvalidError,
    VerifiedPublishStatement,
    verify_publish_statement,
)
from remanence.protocol.v1 import remanence_v1_pb2 as protocol_pb2


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_PATH = REPOSITORY_ROOT / "protocol" / "fixtures" / "publish-statement-v1.json"
GENERATOR_PATH = REPOSITORY_ROOT / "server" / "scripts" / "generate_protocol_pb2.py"


@pytest.fixture(scope="module")
def fixture() -> dict:
    return json.loads(FIXTURE_PATH.read_text())


def _uuid_bytes(value: str) -> bytes:
    return UUID(value).bytes


def _statement_from_fixture(fixture: dict) -> protocol_pb2.PublishStatement:
    statement = protocol_pb2.PublishStatement(
        protocol_version=fixture["schema_version"],
        capsule_id=_uuid_bytes(fixture["capsule_id"]),
        sender_user_id=_uuid_bytes(fixture["sender_user_id"]),
        recipient_user_id=_uuid_bytes(fixture["recipient_user_id"]),
        sender_key_bundle_id=_uuid_bytes(fixture["sender_key_bundle_id"]),
        recipient_key_bundle_id=_uuid_bytes(fixture["recipient_key_bundle_id"]),
        created_at_epoch_seconds=fixture["created_at_epoch_seconds"],
    )
    artifacts = {item["blob_id"]: item for item in fixture["artifacts"]}
    for blob_id in fixture["expected_sorted_blob_ids"]:
        item = artifacts[blob_id]
        statement.artifacts.add(
            blob_id=_uuid_bytes(blob_id),
            kind=getattr(protocol_pb2.ArtifactKind, item["kind"]),
            ordinal=item["ordinal"],
            ciphertext_size=item["ciphertext_size"],
            ciphertext_sha256=bytes.fromhex(item["ciphertext_sha256_hex"]),
        )
    return statement


def _authoritative_data(fixture: dict) -> tuple[Capsule, list[CapsuleBlob]]:
    capsule = Capsule(
        id=UUID(fixture["capsule_id"]),
        sender_user_id=UUID(fixture["sender_user_id"]),
        recipient_user_id=UUID(fixture["recipient_user_id"]),
        sender_key_bundle_id=UUID(fixture["sender_key_bundle_id"]),
        recipient_key_bundle_id=UUID(fixture["recipient_key_bundle_id"]),
        protocol_version=1,
        state=CapsuleState.DRAFT,
        created_at=datetime.fromtimestamp(fixture["created_at_epoch_seconds"], timezone.utc),
        draft_expires_at=datetime.fromtimestamp(fixture["created_at_epoch_seconds"], timezone.utc) + timedelta(days=7),
    )
    declarations = []
    for item in fixture["artifacts"]:
        declarations.append(
            CapsuleBlob(
                id=UUID(item["blob_id"]),
                capsule_id=capsule.id,
                kind=CapsuleBlobKind(item["kind"]),
                ordinal=None if item["ordinal"] == -1 else item["ordinal"],
                object_key=f"capsules/{capsule.id}/{item['blob_id']}.blob",
                expected_ciphertext_size=item["ciphertext_size"],
                expected_ciphertext_sha256=bytes.fromhex(item["ciphertext_sha256_hex"]),
                state=CapsuleBlobState.DECLARED,
            )
        )
    return capsule, declarations


def _valid(fixture: dict) -> tuple[bytes, Capsule, list[CapsuleBlob]]:
    raw = _statement_from_fixture(fixture).SerializeToString(deterministic=True)
    capsule, declarations = _authoritative_data(fixture)
    return raw, capsule, declarations


def _assert_invalid(call) -> None:
    with pytest.raises(PublishStatementInvalidError) as caught:
        call()
    assert str(caught.value) == "publish statement is invalid"
    assert repr(caught.value) == "PublishStatementInvalidError(code='STATEMENT_INVALID')"


def test_generated_descriptor_matches_canonical_publish_schema() -> None:
    assert protocol_pb2.DESCRIPTOR.package == "remanence.protocol.v1"
    message = protocol_pb2.DESCRIPTOR.message_types_by_name["PublishStatement"]
    assert message.full_name == "remanence.protocol.v1.PublishStatement"
    assert {
        field.name: field.number
        for field in message.fields
    } == {
        "protocol_version": 1,
        "capsule_id": 2,
        "sender_user_id": 3,
        "recipient_user_id": 4,
        "sender_key_bundle_id": 5,
        "recipient_key_bundle_id": 6,
        "created_at_epoch_seconds": 7,
        "artifacts": 8,
    }
    artifact = protocol_pb2.DESCRIPTOR.message_types_by_name["ArtifactBinding"]
    assert {field.name: field.number for field in artifact.fields} == {
        "blob_id": 1,
        "kind": 2,
        "ordinal": 3,
        "ciphertext_size": 4,
        "ciphertext_sha256": 5,
    }


def test_generation_is_reproducible_and_golden_is_byte_exact(fixture: dict) -> None:
    generated = REPOSITORY_ROOT / "server" / "src" / "remanence" / "protocol" / "v1" / "remanence_v1_pb2.py"
    before = generated.read_bytes()
    subprocess.run(
        [sys.executable, str(GENERATOR_PATH)],
        cwd=REPOSITORY_ROOT / "server",
        check=True,
    )
    assert generated.read_bytes() == before
    raw = _statement_from_fixture(fixture).SerializeToString(deterministic=True)
    assert raw.hex() == fixture["expected_deterministic_hex"]


def test_golden_verifies_and_returns_immutable_redacted_result(fixture: dict) -> None:
    raw, capsule, declarations = _valid(fixture)
    verified = verify_publish_statement(raw, capsule, declarations)
    assert isinstance(verified, VerifiedPublishStatement)
    assert verified.canonical_bytes == raw
    assert verified.sha256 == hashlib.sha256(raw).digest()
    assert tuple(item.blob_id for item in verified.artifacts) == tuple(
        sorted((item.id for item in declarations), key=lambda value: value.bytes)
    )
    with pytest.raises(AttributeError):
        verified.canonical_bytes = b"x"
    assert repr(verified) == "VerifiedPublishStatement(<redacted>)"
    assert raw.hex() not in repr(verified)


@pytest.mark.parametrize(
    "raw_factory",
    [
        lambda raw: b"",
        lambda raw: raw[:-1],
        lambda raw: b"\x0a\x05abc",
        lambda raw: raw + (b"\x00" * (MAX_PUBLISH_STATEMENT_BYTES + 1 - len(raw))),
    ],
)
def test_malformed_truncated_and_oversized_are_invalid(
    fixture: dict, raw_factory
) -> None:
    raw, capsule, declarations = _valid(fixture)
    _assert_invalid(lambda: verify_publish_statement(raw_factory(raw), capsule, declarations))


def test_unknown_fields_duplicate_singular_nonminimal_varint_and_field_order_fail(
    fixture: dict,
) -> None:
    raw, capsule, declarations = _valid(fixture)
    _assert_invalid(lambda: verify_publish_statement(raw + b"\x48\x01", capsule, declarations))
    _assert_invalid(lambda: verify_publish_statement(raw + b"\x08\x01", capsule, declarations))
    assert raw[:2] == b"\x08\x01"
    _assert_invalid(lambda: verify_publish_statement(b"\x08\x81\x00" + raw[2:], capsule, declarations))

    statement = _statement_from_fixture(fixture)
    field_values = {
        "protocol_version": statement.protocol_version,
        "capsule_id": statement.capsule_id,
        "sender_user_id": statement.sender_user_id,
        "recipient_user_id": statement.recipient_user_id,
        "sender_key_bundle_id": statement.sender_key_bundle_id,
        "recipient_key_bundle_id": statement.recipient_key_bundle_id,
        "created_at_epoch_seconds": statement.created_at_epoch_seconds,
    }
    field_wires = []
    for field_name, value in field_values.items():
        field_statement = protocol_pb2.PublishStatement()
        setattr(field_statement, field_name, value)
        field_wires.append(field_statement.SerializeToString(deterministic=True))
    artifact_wires = []
    for artifact in statement.artifacts:
        artifact_statement = protocol_pb2.PublishStatement()
        artifact_statement.artifacts.add().CopyFrom(artifact)
        artifact_wires.append(artifact_statement.SerializeToString(deterministic=True))
    alternate_order = (
        field_wires[0]
        + field_wires[2]
        + field_wires[1]
        + b"".join(field_wires[3:])
        + b"".join(artifact_wires)
    )
    _assert_invalid(lambda: verify_publish_statement(alternate_order, capsule, declarations))


@pytest.mark.parametrize("field", ["capsule_id", "sender_user_id", "recipient_user_id", "sender_key_bundle_id", "recipient_key_bundle_id"])
def test_wrong_and_short_statement_ids_fail(fixture: dict, field: str) -> None:
    raw, capsule, declarations = _valid(fixture)
    statement = _statement_from_fixture(fixture)
    setattr(statement, field, b"x" * 15)
    _assert_invalid(lambda: verify_publish_statement(statement.SerializeToString(deterministic=True), capsule, declarations))

    statement = _statement_from_fixture(fixture)
    setattr(statement, field, uuid4().bytes)
    _assert_invalid(lambda: verify_publish_statement(statement.SerializeToString(deterministic=True), capsule, declarations))


def test_wrong_version_time_and_nonrepresentable_authoritative_time_fail(fixture: dict) -> None:
    raw, capsule, declarations = _valid(fixture)
    statement = _statement_from_fixture(fixture)
    statement.protocol_version = 2
    _assert_invalid(lambda: verify_publish_statement(statement.SerializeToString(deterministic=True), capsule, declarations))
    statement = _statement_from_fixture(fixture)
    statement.created_at_epoch_seconds += 1
    _assert_invalid(lambda: verify_publish_statement(statement.SerializeToString(deterministic=True), capsule, declarations))

    capsule.created_at = capsule.created_at.replace(microsecond=1)
    _assert_invalid(lambda: verify_publish_statement(raw, capsule, declarations))
    capsule.created_at = capsule.created_at.replace(microsecond=0, tzinfo=None)
    _assert_invalid(lambda: verify_publish_statement(raw, capsule, declarations))


def test_artifact_order_duplicate_missing_extra_kind_ordinal_size_and_hash_fail(
    fixture: dict,
) -> None:
    raw, capsule, declarations = _valid(fixture)
    statement = _statement_from_fixture(fixture)

    artifacts = list(statement.artifacts)
    artifacts[0], artifacts[1] = artifacts[1], artifacts[0]
    statement.ClearField("artifacts")
    statement.artifacts.extend(artifacts)
    _assert_invalid(lambda: verify_publish_statement(statement.SerializeToString(deterministic=True), capsule, declarations))

    for mutation in (
        lambda item: setattr(item, "kind", protocol_pb2.ArtifactKind.PHOTO),
        lambda item: setattr(item, "ordinal", 4),
        lambda item: setattr(item, "ciphertext_size", item.ciphertext_size + 1),
        lambda item: setattr(item, "ciphertext_sha256", b"z" * 32),
    ):
        mutated = _statement_from_fixture(fixture)
        mutation(mutated.artifacts[0])
        _assert_invalid(lambda: verify_publish_statement(mutated.SerializeToString(deterministic=True), capsule, declarations))

    duplicate = _statement_from_fixture(fixture)
    duplicate.artifacts.add().CopyFrom(duplicate.artifacts[0])
    _assert_invalid(lambda: verify_publish_statement(duplicate.SerializeToString(deterministic=True), capsule, declarations))

    missing = _statement_from_fixture(fixture)
    del missing.artifacts[-1]
    _assert_invalid(lambda: verify_publish_statement(missing.SerializeToString(deterministic=True), capsule, declarations))

    extra = _statement_from_fixture(fixture)
    extra.artifacts.add(
        blob_id=uuid4().bytes,
        kind=protocol_pb2.ArtifactKind.PHOTO,
        ordinal=0,
        ciphertext_size=1,
        ciphertext_sha256=b"x" * 32,
    )
    _assert_invalid(lambda: verify_publish_statement(extra.SerializeToString(deterministic=True), capsule, declarations))


def test_declaration_substitution_and_cardinality_total_limit_fail(fixture: dict) -> None:
    raw, capsule, declarations = _valid(fixture)
    swapped = copy.copy(declarations[0])
    swapped.id = uuid4()
    _assert_invalid(lambda: verify_publish_statement(raw, capsule, [swapped, *declarations[1:]]))
    wrong_capsule = copy.copy(declarations[0])
    wrong_capsule.capsule_id = uuid4()
    _assert_invalid(lambda: verify_publish_statement(raw, capsule, [wrong_capsule, *declarations[1:]]))
    _assert_invalid(lambda: verify_publish_statement(raw, capsule, declarations[:-1]))

    oversized = copy.copy(declarations[0])
    oversized.expected_ciphertext_size = 44_040_192
    oversized.expected_ciphertext_sha256 = declarations[0].expected_ciphertext_sha256
    oversized.id = declarations[0].id
    _assert_invalid(lambda: verify_publish_statement(raw, capsule, [oversized, *declarations[1:]]))


@pytest.mark.parametrize("state", [CapsuleBlobState.DECLARED, CapsuleBlobState.STORED])
def test_declared_and_stored_declarations_are_accepted(fixture: dict, state: CapsuleBlobState) -> None:
    raw, capsule, declarations = _valid(fixture)
    for declaration in declarations:
        declaration.state = state
    assert verify_publish_statement(raw, capsule, declarations).artifacts


def test_bad_authoritative_inputs_and_fuzzed_bytes_never_leak_details(fixture: dict) -> None:
    raw, capsule, declarations = _valid(fixture)
    _assert_invalid(lambda: verify_publish_statement(raw, object(), declarations))
    _assert_invalid(lambda: verify_publish_statement(raw, capsule, [object(), *declarations[1:]]))
    _assert_invalid(lambda: verify_publish_statement(raw, capsule, [*declarations, declarations[0], declarations[1], declarations[2]]))
    for seed in range(100):
        fuzzed = hashlib.sha256(seed.to_bytes(2, "big")).digest() * 128
        with pytest.raises(PublishStatementInvalidError) as caught:
            verify_publish_statement(fuzzed[:MAX_PUBLISH_STATEMENT_BYTES], capsule, declarations)
        assert str(caught.value) == "publish statement is invalid"
        assert repr(caught.value) == "PublishStatementInvalidError(code='STATEMENT_INVALID')"
