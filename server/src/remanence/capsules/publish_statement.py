"""Bounded protocol-v1 publish-statement parsing and draft comparison."""

from __future__ import annotations

import calendar
import hashlib
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Final, Iterable

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.models import Capsule
from remanence.protocol.v1.remanence_v1_pb2 import (
    ArtifactKind,
    PublishStatement,
)


MAX_PUBLISH_STATEMENT_BYTES: Final = 4096
_MAX_ARTIFACTS: Final = 7
_MIN_ARTIFACTS: Final = 5
_GENERIC_ERROR = "publish statement is invalid"

_PROTO_KIND_TO_MODEL: Final = {
    ArtifactKind.RECOGNITION_MANIFEST: CapsuleBlobKind.RECOGNITION_MANIFEST,
    ArtifactKind.CONTENT_MANIFEST: CapsuleBlobKind.CONTENT_MANIFEST,
    ArtifactKind.PHOTO: CapsuleBlobKind.PHOTO,
}
_MODEL_KIND_TO_PROTO_NUMBER: Final = {
    CapsuleBlobKind.RECOGNITION_MANIFEST: int(ArtifactKind.RECOGNITION_MANIFEST),
    CapsuleBlobKind.CONTENT_MANIFEST: int(ArtifactKind.CONTENT_MANIFEST),
    CapsuleBlobKind.PHOTO: int(ArtifactKind.PHOTO),
}
_MODEL_KIND_MAX_SIZE: Final = {
    CapsuleBlobKind.RECOGNITION_MANIFEST: LIMITS_V1.recognition_manifest_max_ciphertext_bytes,
    CapsuleBlobKind.CONTENT_MANIFEST: LIMITS_V1.content_manifest_max_ciphertext_bytes,
    CapsuleBlobKind.PHOTO: LIMITS_V1.encrypted_photo_max_ciphertext_bytes,
}


class PublishStatementInvalidError(Exception):
    """The untrusted statement is malformed or does not match the draft."""

    code = "STATEMENT_INVALID"

    def __init__(self) -> None:
        super().__init__(_GENERIC_ERROR)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class VerifiedArtifact:
    blob_id: uuid.UUID
    kind: CapsuleBlobKind
    ordinal: int | None
    ciphertext_size: int
    ciphertext_sha256: bytes

    def __repr__(self) -> str:
        return "VerifiedArtifact(<redacted>)"


@dataclass(frozen=True, slots=True)
class VerifiedPublishStatement:
    canonical_bytes: bytes
    sha256: bytes
    capsule_id: uuid.UUID
    sender_user_id: uuid.UUID
    recipient_user_id: uuid.UUID
    sender_key_bundle_id: uuid.UUID
    recipient_key_bundle_id: uuid.UUID
    created_at: datetime
    artifacts: tuple[VerifiedArtifact, ...]

    def __repr__(self) -> str:
        return "VerifiedPublishStatement(<redacted>)"


def verify_publish_statement(
    raw: bytes,
    capsule: Capsule,
    blob_declarations: Iterable[CapsuleBlob],
) -> VerifiedPublishStatement:
    """Parse and compare one canonical statement against authoritative draft data."""

    try:
        if type(raw) is not bytes or not raw or len(raw) > MAX_PUBLISH_STATEMENT_BYTES:
            raise PublishStatementInvalidError
        if not isinstance(capsule, Capsule):
            raise PublishStatementInvalidError

        declarations = _bounded_declarations(blob_declarations)
        if not _valid_declaration_count(declarations):
            raise PublishStatementInvalidError
        canonical_capsule = _capsule_identity(capsule)
        if capsule.protocol_version != LIMITS_V1.protocol_version:
            raise PublishStatementInvalidError
        statement, canonical_bytes = _parse_canonical(raw)

        if statement.protocol_version != LIMITS_V1.protocol_version:
            raise PublishStatementInvalidError
        statement_ids = _statement_ids(statement)
        if statement_ids != canonical_capsule:
            raise PublishStatementInvalidError
        created_at = _signed_created_at(statement.created_at_epoch_seconds)

        by_id = _declarations_by_id(declarations, capsule.id)
        expected_ids = _canonical_declaration_blob_ids(by_id)
        if [bytes(artifact.blob_id) for artifact in statement.artifacts] != expected_ids:
            raise PublishStatementInvalidError

        verified_artifacts: list[VerifiedArtifact] = []
        total_size = 0
        for artifact in statement.artifacts:
            blob_id = uuid.UUID(bytes=bytes(artifact.blob_id))
            declaration = by_id.get(blob_id.bytes)
            if declaration is None:
                raise PublishStatementInvalidError
            verified = _verify_artifact(artifact, declaration)
            total_size += verified.ciphertext_size
            verified_artifacts.append(verified)
        if total_size > LIMITS_V1.total_capsule_max_ciphertext_bytes:
            raise PublishStatementInvalidError

        return VerifiedPublishStatement(
            canonical_bytes=canonical_bytes,
            sha256=hashlib.sha256(canonical_bytes).digest(),
            capsule_id=statement_ids[0],
            sender_user_id=statement_ids[1],
            recipient_user_id=statement_ids[2],
            sender_key_bundle_id=statement_ids[3],
            recipient_key_bundle_id=statement_ids[4],
            created_at=created_at,
            artifacts=tuple(verified_artifacts),
        )
    except PublishStatementInvalidError:
        raise
    except Exception:
        raise PublishStatementInvalidError from None


class PublishStatementVerifier:
    """Named facade for callers that prefer an object-shaped verifier."""

    @staticmethod
    def verify(
        raw: bytes,
        capsule: Capsule,
        blob_declarations: Iterable[CapsuleBlob],
    ) -> VerifiedPublishStatement:
        return verify_publish_statement(raw, capsule, blob_declarations)


def _parse_canonical(raw: bytes) -> tuple[PublishStatement, bytes]:
    statement = PublishStatement()
    try:
        statement.ParseFromString(raw)
        canonical = statement.SerializeToString(deterministic=True)
        known_fields = PublishStatement()
        known_fields.CopyFrom(statement)
        known_fields.DiscardUnknownFields()
        if canonical != raw or known_fields.SerializeToString(deterministic=True) != canonical:
            raise PublishStatementInvalidError
        return statement, canonical
    except PublishStatementInvalidError:
        raise
    except Exception:
        raise PublishStatementInvalidError from None


def _valid_declaration_count(declarations: tuple[CapsuleBlob, ...]) -> bool:
    return _MIN_ARTIFACTS <= len(declarations) <= _MAX_ARTIFACTS


def _bounded_declarations(source: Iterable[CapsuleBlob]) -> tuple[CapsuleBlob, ...]:
    result: list[CapsuleBlob] = []
    iterator = iter(source)
    for _ in range(_MAX_ARTIFACTS + 1):
        try:
            result.append(next(iterator))
        except StopIteration:
            return tuple(result)
    raise PublishStatementInvalidError


def _capsule_identity(capsule: Capsule) -> tuple[uuid.UUID, ...]:
    values = (
        capsule.id,
        capsule.sender_user_id,
        capsule.recipient_user_id,
        capsule.sender_key_bundle_id,
        capsule.recipient_key_bundle_id,
    )
    if any(not isinstance(value, uuid.UUID) for value in values):
        raise PublishStatementInvalidError
    return values


def _statement_ids(statement: PublishStatement) -> tuple[uuid.UUID, ...]:
    fields = (
        statement.capsule_id,
        statement.sender_user_id,
        statement.recipient_user_id,
        statement.sender_key_bundle_id,
        statement.recipient_key_bundle_id,
    )
    if any(type(value) is not bytes or len(value) != 16 for value in fields):
        raise PublishStatementInvalidError
    try:
        return tuple(uuid.UUID(bytes=value) for value in fields)
    except (ValueError, TypeError):
        raise PublishStatementInvalidError from None


def _signed_created_at(value: object) -> datetime:
    if type(value) is not int or value < 0:
        raise PublishStatementInvalidError
    try:
        normalized = datetime.fromtimestamp(value, timezone.utc)
        if _epoch_seconds(normalized) != value:
            raise PublishStatementInvalidError
    except (OverflowError, OSError, ValueError):
        raise PublishStatementInvalidError from None
    return normalized


def _epoch_seconds(value: datetime) -> int:
    try:
        seconds = calendar.timegm(value.utctimetuple())
    except (OverflowError, OSError, ValueError):
        raise PublishStatementInvalidError from None
    if not -(1 << 63) <= seconds <= (1 << 63) - 1:
        raise PublishStatementInvalidError
    return seconds


def _canonical_artifact_key(declaration: CapsuleBlob) -> tuple[int, int, bytes]:
    kind_number = _MODEL_KIND_TO_PROTO_NUMBER.get(declaration.kind)
    if kind_number is None or not isinstance(declaration.id, uuid.UUID):
        raise PublishStatementInvalidError
    ordinal = -1 if declaration.ordinal is None else declaration.ordinal
    if type(ordinal) is not int:
        raise PublishStatementInvalidError
    return (kind_number, ordinal, declaration.id.bytes)


def _canonical_declaration_blob_ids(by_id: dict[bytes, CapsuleBlob]) -> list[bytes]:
    return [
        declaration.id.bytes
        for declaration in sorted(by_id.values(), key=_canonical_artifact_key)
    ]


def _declarations_by_id(
    declarations: tuple[CapsuleBlob, ...], capsule_id: uuid.UUID
) -> dict[bytes, CapsuleBlob]:
    result: dict[bytes, CapsuleBlob] = {}
    for declaration in declarations:
        if not isinstance(declaration, CapsuleBlob):
            raise PublishStatementInvalidError
        if (
            not isinstance(declaration.id, uuid.UUID)
            or declaration.id.bytes in result
            or declaration.capsule_id != capsule_id
        ):
            raise PublishStatementInvalidError
        if declaration.state not in (CapsuleBlobState.DECLARED, CapsuleBlobState.STORED):
            raise PublishStatementInvalidError
        result[declaration.id.bytes] = declaration
    return result


def _verify_artifact(artifact: object, declaration: CapsuleBlob) -> VerifiedArtifact:
    if artifact.kind not in _PROTO_KIND_TO_MODEL:
        raise PublishStatementInvalidError
    kind = _PROTO_KIND_TO_MODEL[artifact.kind]
    if declaration.kind is not kind:
        raise PublishStatementInvalidError

    blob_id = bytes(artifact.blob_id)
    if type(artifact.blob_id) is not bytes or len(blob_id) != 16:
        raise PublishStatementInvalidError
    if blob_id != declaration.id.bytes:
        raise PublishStatementInvalidError

    ordinal = artifact.ordinal
    if kind is CapsuleBlobKind.PHOTO:
        if type(ordinal) is not int or not 0 <= ordinal <= 4:
            raise PublishStatementInvalidError
        if declaration.ordinal != ordinal:
            raise PublishStatementInvalidError
    else:
        if ordinal != -1 or declaration.ordinal is not None:
            raise PublishStatementInvalidError
        ordinal = None

    size = artifact.ciphertext_size
    if type(size) is not int or size <= 0:
        raise PublishStatementInvalidError
    if size > _MODEL_KIND_MAX_SIZE[kind] or size != declaration.expected_ciphertext_size:
        raise PublishStatementInvalidError
    if type(declaration.expected_ciphertext_size) is not int or declaration.expected_ciphertext_size <= 0:
        raise PublishStatementInvalidError

    digest = bytes(artifact.ciphertext_sha256)
    if type(artifact.ciphertext_sha256) is not bytes or len(digest) != 32:
        raise PublishStatementInvalidError
    if type(declaration.expected_ciphertext_sha256) is not bytes or len(declaration.expected_ciphertext_sha256) != 32:
        raise PublishStatementInvalidError
    if digest != declaration.expected_ciphertext_sha256:
        raise PublishStatementInvalidError

    return VerifiedArtifact(
        blob_id=declaration.id,
        kind=kind,
        ordinal=ordinal,
        ciphertext_size=size,
        ciphertext_sha256=digest,
    )


verify_canonical_publish_statement = verify_publish_statement
