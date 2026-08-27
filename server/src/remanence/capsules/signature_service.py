"""Authoritative verification of one capsule publish signature."""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Final

import tink
from google.protobuf.message import DecodeError
from tink import signature, tink_config
from tink.proto import tink_pb2
from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.capsules.publish_statement import VerifiedPublishStatement
from remanence.users.key_bundle_validation import (
    ED25519_PUBLIC_KEY_TYPE_URL,
    SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
    SUPPORTED_KEY_BUNDLE_SUITE,
    validate_public_key_bundle,
)
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle


_DOMAIN_PREFIX: Final[bytes] = b"postmark/publish/v1"
_TINK_PREFIX: Final[int] = 0x01
_SIGNATURE_LENGTH: Final[int] = 69
_GENERIC_ERROR: Final[str] = "capsule publish signature verification failed"


class PublishSignatureVerificationError(Exception):
    """Redacted, stable failure for the S14 verification boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_ERROR)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class VerifiedPublishAuthorization:
    """The read-only authorization material passed to finalize."""

    verified_statement: VerifiedPublishStatement
    sender_key_bundle_id: uuid.UUID
    sender_key_bundle_status: KeyBundleStatus
    recipient_key_bundle_id: uuid.UUID
    recipient_key_bundle_status: KeyBundleStatus
    signature: bytes = field(repr=False)

    @property
    def statement(self) -> VerifiedPublishStatement:
        return self.verified_statement

    def __repr__(self) -> str:
        return "VerifiedPublishAuthorization(<redacted>)"


class PublishSignatureVerificationService:
    """Resolve authoritative public bundles and verify one publish signature."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def verify(
        self,
        verified_statement: VerifiedPublishStatement,
        signature_bytes: bytes,
    ) -> VerifiedPublishAuthorization:
        if not isinstance(self._session, Session):
            raise _error("INTERNAL_ERROR")
        if not isinstance(verified_statement, VerifiedPublishStatement):
            raise _error("STATEMENT_INVALID")
        if type(signature_bytes) is not bytes:
            raise _error("SIGNATURE_INVALID")

        with self._session.no_autoflush:
            try:
                sender = self._session.get(
                    UserKeyBundle,
                    verified_statement.sender_key_bundle_id,
                )
            except Exception:
                raise _error("INTERNAL_ERROR") from None
        if sender is None or sender.user_id != verified_statement.sender_user_id:
            raise _error("KEY_BUNDLE_NOT_FOUND")
        if sender.status == KeyBundleStatus.REVOKED:
            raise _error("KEY_BUNDLE_REVOKED")
        if sender.status not in (KeyBundleStatus.ACTIVE, KeyBundleStatus.RETIRED):
            raise _error("KEY_BUNDLE_INVALID")
        if not _canonical_bundle_metadata(sender):
            raise _error("KEY_BUNDLE_INVALID")
        _validate_bundle(sender, code="KEY_BUNDLE_INVALID")

        with self._session.no_autoflush:
            try:
                recipient = self._session.get(
                    UserKeyBundle,
                    verified_statement.recipient_key_bundle_id,
                )
                current_recipient_id = self._session.scalar(
                    select(UserKeyBundle.id).where(
                        UserKeyBundle.user_id == verified_statement.recipient_user_id,
                        UserKeyBundle.status == KeyBundleStatus.ACTIVE,
                    )
                )
            except Exception:
                raise _error("INTERNAL_ERROR") from None
        if recipient is None or recipient.user_id != verified_statement.recipient_user_id:
            raise _error("RECIPIENT_KEY_STALE")
        if recipient.status != KeyBundleStatus.ACTIVE:
            raise _error("RECIPIENT_KEY_STALE")
        if current_recipient_id != recipient.id:
            raise _error("RECIPIENT_KEY_STALE")
        if not _canonical_bundle_metadata(recipient):
            raise _error("RECIPIENT_KEY_STALE")
        _validate_bundle(recipient, code="RECIPIENT_KEY_STALE")

        signing_key_id = _validated_signing_key_id(sender.signing_public_keyset)
        if (
            len(signature_bytes) != _SIGNATURE_LENGTH
            or signature_bytes[0] != _TINK_PREFIX
            or int.from_bytes(signature_bytes[1:5], byteorder="big") == 0
            or int.from_bytes(signature_bytes[1:5], byteorder="big") != signing_key_id
        ):
            raise _error("SIGNATURE_INVALID")

        try:
            tink_config.register()
            handle = tink.KeysetHandle.read_no_secret(
                tink.BinaryKeysetReader(sender.signing_public_keyset)
            )
            verifier = handle.primitive(signature.PublicKeyVerify)
            verifier.verify(
                signature_bytes,
                _DOMAIN_PREFIX + verified_statement.canonical_bytes,
            )
        except Exception:
            raise _error("SIGNATURE_INVALID") from None

        return VerifiedPublishAuthorization(
            verified_statement=verified_statement,
            sender_key_bundle_id=sender.id,
            sender_key_bundle_status=sender.status,
            recipient_key_bundle_id=recipient.id,
            recipient_key_bundle_status=recipient.status,
            signature=signature_bytes,
        )


def _error(code: str) -> PublishSignatureVerificationError:
    return PublishSignatureVerificationError(code)


def _canonical_bundle_metadata(bundle: UserKeyBundle) -> bool:
    return (
        bundle.suite == SUPPORTED_KEY_BUNDLE_SUITE
        and bundle.protocol_version == SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION
    )


def _validate_bundle(bundle: UserKeyBundle, *, code: str) -> None:
    try:
        validate_public_key_bundle(
            suite=bundle.suite,
            protocol_version=bundle.protocol_version,
            encryption_public_keyset=bundle.encryption_public_keyset,
            signing_public_keyset=bundle.signing_public_keyset,
        )
    except Exception:
        raise _error(code) from None


def _validated_signing_key_id(serialized: bytes) -> int:
    keyset = tink_pb2.Keyset()
    try:
        keyset.ParseFromString(serialized)
    except (DecodeError, TypeError, ValueError):
        raise _error("KEY_BUNDLE_INVALID") from None
    if len(keyset.key) != 1 or keyset.primary_key_id == 0:
        raise _error("KEY_BUNDLE_INVALID")
    key = keyset.key[0]
    if (
        key.key_id == 0
        or key.key_id != keyset.primary_key_id
        or key.status != tink_pb2.ENABLED
        or key.output_prefix_type != tink_pb2.TINK
        or key.key_data.type_url != ED25519_PUBLIC_KEY_TYPE_URL
    ):
        raise _error("KEY_BUNDLE_INVALID")
    return int(keyset.primary_key_id)


CapsulePublishSignatureVerificationError = PublishSignatureVerificationError
CapsulePublishSignatureVerificationService = PublishSignatureVerificationService
