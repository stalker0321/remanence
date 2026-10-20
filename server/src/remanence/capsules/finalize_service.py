"""PostgreSQL finalize transaction. The caller owns commit/rollback."""

from __future__ import annotations

import hashlib
import hmac
import logging
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Final

from sqlalchemy import func, inspect, select
from sqlalchemy.orm import Session, make_transient

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.publish_statement import (
    PublishStatementInvalidError,
    verify_publish_statement,
)
from remanence.capsules.signature_service import (
    PublishSignatureVerificationError,
    PublishSignatureVerificationService,
)
from remanence.storage import BlobNotFoundError, BlobStore, BlobStoreError
from remanence.users.models import User
from remanence.send_access import service as send_access
from remanence.send_access.policy import SendAccessMode


_GENERIC_SERVICE_MESSAGE: Final = "capsule finalize failed"
_SIGNATURE_LENGTH: Final = 69
_PUBLICATION_SEQUENCE_MAX: Final = (1 << 63) - 1

_LOGGER = logging.getLogger(__name__)


def _log_internal(stage: str, capsule_id: uuid.UUID, exc: BaseException) -> None:
    """Operational record for a redacted INTERNAL_ERROR.

    Only the stage, the capsule id, and the exception type are logged.
    Messages, tracebacks, and all request payloads stay out because storage
    and database errors routinely embed paths, statements, or ciphertext.
    """
    _LOGGER.error(
        "capsule finalize internal failure stage=%s capsule_id=%s exc_type=%s",
        stage,
        capsule_id,
        type(exc).__name__,
    )


class CapsuleFinalizeError(Exception):
    """Redacted, stable failure suitable for the S16 HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class CapsuleFinalizeEnvelope:
    recipient_key_bundle_id: uuid.UUID
    ciphertext: bytes
    ciphertext_size: int
    ciphertext_sha256: bytes

    def __repr__(self) -> str:
        return "CapsuleFinalizeEnvelope(<redacted>)"


@dataclass(frozen=True, slots=True)
class CapsuleFinalizeResult:
    capsule_id: uuid.UUID
    state: CapsuleState
    ready_at: datetime
    recipient_key_bundle_id: uuid.UUID
    is_replay: bool
    # R2 common-locator extension (additive): plaintext code + hint are
    # present ONLY when the caller negotiated issuance on this call.
    # Never persisted, never logged; v1 signed bytes untouched.
    locator_code: str | None = None
    locator_hint: str | None = None

    def __repr__(self) -> str:
        return "CapsuleFinalizeResult(<redacted>)"


@dataclass(frozen=True, slots=True)
class LocatorIssuance:
    """Opt-in R2 issuance context for one v1 finalize call.

    None (the default) means legacy behavior: no locator row, legacy
    response shape. Supplied only by the route layer when the client
    negotiated the locator capability AND the issuance flag + custody
    context allow it. No EscrowVault is involved on this path.
    """

    hmac_key: bytes
    code_key_32: bytes
    code_key_mode: str

    def __repr__(self) -> str:
        return "LocatorIssuance(<redacted>)"


def _error(code: str) -> CapsuleFinalizeError:
    return CapsuleFinalizeError(code)


def _require_uuid(value: object) -> uuid.UUID:
    if not isinstance(value, uuid.UUID):
        raise _error("VALIDATION_FAILED")
    return value


def _require_utc(value: object) -> datetime:
    if (
        not isinstance(value, datetime)
        or value.tzinfo is None
        or value.utcoffset() != timedelta(0)
    ):
        raise _error("VALIDATION_FAILED")
    return value


def _require_bytes(value: object) -> bytes:
    if type(value) is not bytes:
        raise _error("VALIDATION_FAILED")
    return value


def _digest_equal(left: object, right: object) -> bool:
    return (
        type(left) is bytes
        and type(right) is bytes
        and len(left) == len(right)
        and hmac.compare_digest(left, right)
    )


def _identity_snapshot(capsule: Capsule, recipient_key_bundle_id: uuid.UUID) -> Capsule:
    snapshot = Capsule(
        id=capsule.id,
        sender_user_id=capsule.sender_user_id,
        recipient_user_id=capsule.recipient_user_id,
        sender_key_bundle_id=capsule.sender_key_bundle_id,
        recipient_key_bundle_id=recipient_key_bundle_id,
        protocol_version=capsule.protocol_version,
        state=capsule.state,
        signed_statement=None,
        signed_statement_sha256=None,
        publish_signature=None,
        created_at=capsule.created_at,
        ready_at=None,
        draft_expires_at=capsule.draft_expires_at,
    )
    if inspect(snapshot).session is not None:
        make_transient(snapshot)
    if inspect(snapshot).session is not None:
        raise _error("INTERNAL_ERROR")
    return snapshot


class CapsuleFinalizeService:
    """Mark one draft READY inside the caller's PostgreSQL transaction."""

    def __init__(self, session: Session, blob_store: BlobStore) -> None:
        self._session = session
        self._blob_store = blob_store

    def finalize(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        statement: bytes,
        signature: bytes,
        sender_key_bundle_id: uuid.UUID,
        envelope: CapsuleFinalizeEnvelope,
        now: datetime,
        locator_issuance: LocatorIssuance | None = None,
        clock: Callable[[], datetime] | None = None,
        send_access_mode: SendAccessMode = SendAccessMode.OFF,
    ) -> CapsuleFinalizeResult:
        _require_uuid(authenticated_sender_user_id)
        _require_uuid(capsule_id)
        _require_uuid(sender_key_bundle_id)
        statement = _require_bytes(statement)
        signature = _require_bytes(signature)
        if not isinstance(envelope, CapsuleFinalizeEnvelope):
            raise _error("VALIDATION_FAILED")
        _require_uuid(envelope.recipient_key_bundle_id)
        _require_bytes(envelope.ciphertext)
        _require_bytes(envelope.ciphertext_sha256)
        now = _require_utc(now)
        if locator_issuance is not None and not isinstance(locator_issuance, LocatorIssuance):
            raise _error("VALIDATION_FAILED")

        try:
            return self._finalize(
                authenticated_sender_user_id=authenticated_sender_user_id,
                capsule_id=capsule_id,
                statement=statement,
                signature=signature,
                sender_key_bundle_id=sender_key_bundle_id,
                envelope=envelope,
                now=now,
                locator_issuance=locator_issuance,
                clock=clock,
                send_access_mode=send_access_mode,
            )
        except send_access.SendAccessError as exc:
            raise _error(exc.code) from None
        except CapsuleFinalizeError:
            raise
        except Exception as exc:
            _log_internal("finalize", capsule_id, exc)
            raise _error("INTERNAL_ERROR") from None

    def _finalize(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        statement: bytes,
        signature: bytes,
        sender_key_bundle_id: uuid.UUID,
        envelope: CapsuleFinalizeEnvelope,
        now: datetime,
        locator_issuance: LocatorIssuance | None,
        clock: Callable[[], datetime] | None,
        send_access_mode: SendAccessMode,
    ) -> CapsuleFinalizeResult:
        with self._session.no_autoflush:
            self._session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
            capsule = self._session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            if capsule is None or capsule.sender_user_id != authenticated_sender_user_id:
                raise _error("CAPSULE_NOT_FOUND")
            if capsule.state is CapsuleState.READY:
                return self._replay_ready(
                    capsule,
                    statement=statement,
                    signature=signature,
                    sender_key_bundle_id=sender_key_bundle_id,
                    envelope=envelope,
                    locator_issuance=locator_issuance,
                )
            if capsule.state is not CapsuleState.DRAFT:
                raise _error("CAPSULE_STATE_INVALID")

            def check_deadline() -> datetime:
                # A request timestamp predates advisory/recipient lock waits.
                # Preserve the supplied timestamp/explicit test clock, but never
                # use it alone to authorize a NEW publication after a slow wait.
                fresh = _require_utc(clock() if clock is not None else datetime.now(timezone.utc))
                current = max(now, fresh)
                if current >= capsule.draft_expires_at:
                    raise _error("DRAFT_EXPIRED")
                return current

            check_deadline()
            if sender_key_bundle_id != capsule.sender_key_bundle_id:
                raise _error("KEY_BUNDLE_INVALID")

            blobs = list(
                self._session.scalars(
                    select(CapsuleBlob)
                    .where(CapsuleBlob.capsule_id == capsule.id)
                    .execution_options(populate_existing=True)
                )
            )
            if not (
                LIMITS_V1.recognition_manifest_count
                + LIMITS_V1.content_manifest_count
                + LIMITS_V1.photo_count_min
                <= len(blobs)
                <= LIMITS_V1.recognition_manifest_count
                + LIMITS_V1.content_manifest_count
                + LIMITS_V1.photo_count_max
            ):
                raise _error("INTERNAL_ERROR")
            if any(blob.state is not CapsuleBlobState.STORED for blob in blobs):
                raise _error("CAPSULE_STATE_INVALID")

            snapshot = _identity_snapshot(capsule, envelope.recipient_key_bundle_id)
            try:
                verified = verify_publish_statement(statement, snapshot, blobs)
            except PublishStatementInvalidError:
                raise _error("STATEMENT_INVALID") from None
            if verified.sender_key_bundle_id != sender_key_bundle_id:
                raise _error("KEY_BUNDLE_INVALID")

            try:
                authorization = PublishSignatureVerificationService(self._session).verify(
                    verified,
                    signature,
                )
            except PublishSignatureVerificationError as exc:
                raise _error(exc.code) from None

            self._validate_envelope(verified, authorization, envelope)
            for blob in blobs:
                self._stat_stored(blob)
            publication_sequence = self._next_publication_sequence(
                capsule.recipient_user_id
            )
            allocation = send_access.admit(
                self._session, capsule, mode=send_access_mode,
                publication_sha256=hashlib.sha256(statement).digest(),
                check_deadline=check_deadline,
            )
            publication_at = allocation.published_at if allocation is not None else check_deadline()
            result = self._persist_ready(
                capsule,
                authorization=authorization,
                envelope=envelope,
                now=publication_at,
                publication_sequence=publication_sequence,
                locator_issuance=locator_issuance,
            )
            # Flush/optional locator work can also wait. Raising here requires
            # the caller's outer rollback, just like any late finalize failure.
            send_access.record(self._session, capsule, allocation, now=check_deadline())
            check_deadline()
            return result

    def _next_publication_sequence(self, recipient_user_id: uuid.UUID) -> int:
        """Reserve the recipient's next stream position until this transaction commits."""
        recipient_id = self._session.scalar(
            select(User.id)
            .where(User.id == recipient_user_id)
            .with_for_update()
        )
        if recipient_id != recipient_user_id:
            raise _error("INTERNAL_ERROR")
        current = self._session.scalar(
            select(func.coalesce(func.max(RecipientDeliveryState.publication_sequence), 0))
            .where(RecipientDeliveryState.recipient_user_id == recipient_user_id)
        )
        if type(current) is not int or not 0 <= current < _PUBLICATION_SEQUENCE_MAX:
            raise _error("INTERNAL_ERROR")
        return current + 1

    def _stat_stored(self, blob: CapsuleBlob) -> None:
        try:
            info = self._blob_store.stat(blob.object_key)
        except (BlobNotFoundError, BlobStoreError, OSError):
            raise _error("INTERNAL_ERROR") from None
        except Exception as exc:
            _log_internal("blob_stat", blob.capsule_id, exc)
            raise _error("INTERNAL_ERROR") from None
        if (
            info.size != blob.expected_ciphertext_size
            or not isinstance(info.sha256_hex, str)
            or not hmac.compare_digest(info.sha256_hex, blob.expected_ciphertext_sha256.hex())
        ):
            raise _error("BLOB_CONFLICT")

    def _validate_envelope(self, verified, authorization, envelope: CapsuleFinalizeEnvelope) -> None:
        size = envelope.ciphertext_size
        digest = envelope.ciphertext_sha256
        ciphertext = envelope.ciphertext
        if type(size) is not int or not 0 < size <= LIMITS_V1.recipient_envelope_max_ciphertext_bytes:
            raise _error("ENVELOPE_INVALID")
        if len(ciphertext) != size:
            raise _error("ENVELOPE_INVALID")
        if type(digest) is not bytes or len(digest) != 32:
            raise _error("ENVELOPE_INVALID")
        if not hmac.compare_digest(hashlib.sha256(ciphertext).digest(), digest):
            raise _error("ENVELOPE_INVALID")
        if (
            envelope.recipient_key_bundle_id != authorization.recipient_key_bundle_id
            or envelope.recipient_key_bundle_id != verified.recipient_key_bundle_id
        ):
            raise _error("ENVELOPE_INVALID")

    def _replay_ready(
        self,
        capsule: Capsule,
        *,
        statement: bytes,
        signature: bytes,
        sender_key_bundle_id: uuid.UUID,
        envelope: CapsuleFinalizeEnvelope,
        locator_issuance: LocatorIssuance | None,
    ) -> CapsuleFinalizeResult:
        stored_envelope = self._session.get(CapsuleEnvelope, capsule.id)
        delivery = self._session.get(
            RecipientDeliveryState, (capsule.recipient_user_id, capsule.id)
        )
        if not self._stored_ready_shape_is_consistent(capsule, stored_envelope, delivery):
            raise _error("INTERNAL_ERROR")
        equivalent = (
            sender_key_bundle_id == capsule.sender_key_bundle_id
            and _digest_equal(capsule.signed_statement, statement)
            and _digest_equal(capsule.publish_signature, signature)
            and envelope.recipient_key_bundle_id == stored_envelope.recipient_key_bundle_id
            and envelope.ciphertext_size == stored_envelope.ciphertext_size
            and _digest_equal(stored_envelope.ciphertext, envelope.ciphertext)
            and _digest_equal(stored_envelope.ciphertext_sha256, envelope.ciphertext_sha256)
        )
        if not equivalent:
            raise _error("FINALIZE_CONFLICT")
        send_access.validate_replay(self._session, capsule)
        locator_code, locator_hint = self._read_issued_code(capsule.id, locator_issuance)
        return CapsuleFinalizeResult(
            capsule_id=capsule.id,
            state=CapsuleState.READY,
            ready_at=capsule.ready_at,
            recipient_key_bundle_id=capsule.recipient_key_bundle_id,
            is_replay=True,
            locator_code=locator_code,
            locator_hint=locator_hint,
        )

    def _read_issued_code(
        self, capsule_id: uuid.UUID, issuance: LocatorIssuance | None
    ) -> tuple[str | None, str | None]:
        """Replay read-back: return the committed code ONLY when the caller
        negotiated issuance on THIS call and a locator row exists. Replays
        never allocate (no retro-issuance); any seal/lookup failure
        degrades to the legacy shape, never a finalize failure."""
        if issuance is None or issuance.code_key_mode in ("prod", "beta"):
            return None, None
        try:
            from remanence.pending.models import GiftLocator as _GiftLocator
            from remanence.pending.vault import aead_from_raw_key as _aead

            row = self._session.scalar(
                select(_GiftLocator).where(_GiftLocator.capsule_id == capsule_id)
            )
            if row is None or row.code_ciphertext is None:
                return None, None
            code = (
                _aead(issuance.code_key_32)
                .decrypt(bytes(row.code_ciphertext), b"pending-locator-code-v1")
                .decode("utf-8")
            )
            return code, row.hint_mask
        except Exception as exc:
            _log_internal("locator", capsule_id, exc)
            return None, None

    @staticmethod
    def _stored_ready_shape_is_consistent(
        capsule: Capsule,
        stored_envelope: CapsuleEnvelope | None,
        delivery: RecipientDeliveryState | None,
    ) -> bool:
        if (
            capsule.signed_statement is None
            or capsule.signed_statement_sha256 is None
            or capsule.publish_signature is None
            or capsule.ready_at is None
            or stored_envelope is None
            or delivery is None
        ):
            return False
        if not _digest_equal(
            hashlib.sha256(capsule.signed_statement).digest(),
            capsule.signed_statement_sha256,
        ):
            return False
        if type(capsule.publish_signature) is not bytes or len(capsule.publish_signature) != _SIGNATURE_LENGTH:
            return False
        if stored_envelope.recipient_user_id != capsule.recipient_user_id:
            return False
        if stored_envelope.recipient_key_bundle_id != capsule.recipient_key_bundle_id:
            return False
        size = stored_envelope.ciphertext_size
        if type(size) is not int or not 0 < size <= LIMITS_V1.recipient_envelope_max_ciphertext_bytes:
            return False
        if type(stored_envelope.ciphertext) is not bytes or len(stored_envelope.ciphertext) != size:
            return False
        if not _digest_equal(
            hashlib.sha256(stored_envelope.ciphertext).digest(),
            stored_envelope.ciphertext_sha256,
        ):
            return False
        if delivery.recipient_user_id != capsule.recipient_user_id or delivery.capsule_id != capsule.id:
            return False
        if delivery.available_at != capsule.ready_at:
            return False
        if delivery.state is RecipientDeliveryStatus.AVAILABLE:
            return delivery.ciphertext_synced_at is None
        if delivery.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED:
            return delivery.ciphertext_synced_at is not None
        return False

    def _persist_ready(
        self,
        capsule: Capsule,
        *,
        authorization,
        envelope: CapsuleFinalizeEnvelope,
        now: datetime,
        publication_sequence: int,
        locator_issuance: LocatorIssuance | None,
    ) -> CapsuleFinalizeResult:
        signature = authorization.signature
        if type(signature) is not bytes or len(signature) != _SIGNATURE_LENGTH:
            raise _error("SIGNATURE_INVALID")
        original = {
            "recipient_key_bundle_id": capsule.recipient_key_bundle_id,
            "state": capsule.state,
            "signed_statement": capsule.signed_statement,
            "signed_statement_sha256": capsule.signed_statement_sha256,
            "publish_signature": capsule.publish_signature,
            "ready_at": capsule.ready_at,
        }
        envelope_row = CapsuleEnvelope(
            capsule_id=capsule.id,
            recipient_user_id=capsule.recipient_user_id,
            recipient_key_bundle_id=authorization.recipient_key_bundle_id,
            ciphertext=envelope.ciphertext,
            ciphertext_size=envelope.ciphertext_size,
            ciphertext_sha256=envelope.ciphertext_sha256,
        )
        delivery_row = RecipientDeliveryState(
            recipient_user_id=capsule.recipient_user_id,
            capsule_id=capsule.id,
            state=RecipientDeliveryStatus.AVAILABLE,
            publication_sequence=publication_sequence,
            available_at=now,
            ciphertext_synced_at=None,
        )
        locator_row = None
        try:
            capsule.recipient_key_bundle_id = authorization.recipient_key_bundle_id
            capsule.signed_statement = authorization.verified_statement.canonical_bytes
            capsule.signed_statement_sha256 = authorization.verified_statement.sha256
            capsule.publish_signature = signature
            capsule.ready_at = now
            capsule.state = CapsuleState.READY
            self._session.add(envelope_row)
            self._session.add(delivery_row)
            self._session.flush()
            locator_code, locator_hint, locator_row = self._maybe_issue_locator(
                capsule, locator_issuance
            )
        except CapsuleFinalizeError:
            self._restore_capsule(capsule, original, envelope_row, delivery_row, locator_row)
            raise
        except Exception as exc:
            self._restore_capsule(capsule, original, envelope_row, delivery_row, locator_row)
            _log_internal("persist", capsule.id, exc)
            raise _error("INTERNAL_ERROR") from None
        return CapsuleFinalizeResult(
            capsule_id=capsule.id,
            state=CapsuleState.READY,
            ready_at=now,
            recipient_key_bundle_id=capsule.recipient_key_bundle_id,
            is_replay=False,
            locator_code=locator_code,
            locator_hint=locator_hint,
        )

    def _maybe_issue_locator(
        self, capsule: Capsule, issuance: LocatorIssuance | None
    ) -> tuple:
        """R2: allocate + activate the common locator inside the caller's
        finalize transaction (same pattern as the pending path).

        None issuance (no negotiated capability) is byte-for-byte legacy:
        no row, no response fields. Issuance runs in its own savepoint so
        an allocation failure degrades to the legacy shape (logged,
        redacted) instead of failing the existing v1 send; the locator
        is public routing metadata, never key material, and no
        EscrowVault call happens on this path. Production/beta modes
        refuse the dev code adapter loudly (route layer never requests
        issuance there). Returns (code, hint, row) with Nones on legacy
        or degrade; the row lets the persist-failure path expunge it.
        """
        if issuance is None:
            return None, None, None
        if issuance.code_key_mode in ("prod", "beta"):
            raise _error("INTERNAL_ERROR")
        try:
            from remanence.pending.locator import mask_hint as _mask
            from remanence.pending.locator_store import (
                activate as _activate,
            )
            from remanence.pending.locator_store import (
                allocate_reserved as _allocate,
            )
            from remanence.pending.vault import aead_from_raw_key as _aead

            recipient_email = self._session.scalar(
                select(User.email_normalized).where(User.id == capsule.recipient_user_id)
            )
            if not isinstance(recipient_email, str) or not recipient_email:
                raise _error("INTERNAL_ERROR")
            hint_mask = _mask("email", recipient_email)
            with self._session.begin_nested():
                locator, code = _allocate(
                    self._session,
                    capsule_id=capsule.id,
                    hint_mask=hint_mask,
                    hmac_key=issuance.hmac_key,
                )
                primitive = _aead(issuance.code_key_32)
                _activate(
                    self._session,
                    locator_id=locator.id,
                    code_ciphertext=primitive.encrypt(
                        code.encode("ascii"), b"pending-locator-code-v1"
                    ),
                )
                self._session.flush()
            return code, hint_mask, locator
        except CapsuleFinalizeError:
            raise
        except Exception as exc:
            _log_internal("locator", capsule.id, exc)
            return None, None, None

    def _restore_capsule(
        self,
        capsule: Capsule,
        original: dict,
        envelope_row: CapsuleEnvelope,
        delivery_row: RecipientDeliveryState,
        locator_row=None,
    ) -> None:
        try:
            capsule.recipient_key_bundle_id = original["recipient_key_bundle_id"]
            capsule.state = original["state"]
            capsule.signed_statement = original["signed_statement"]
            capsule.signed_statement_sha256 = original["signed_statement_sha256"]
            capsule.publish_signature = original["publish_signature"]
            capsule.ready_at = original["ready_at"]
        except Exception:
            pass
        for row in (envelope_row, delivery_row, locator_row):
            if row is None:
                continue
            try:
                if inspect(row).session is self._session:
                    self._session.expunge(row)
            except Exception:
                pass
