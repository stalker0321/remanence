"""Authenticated capsule draft creation endpoint."""

import base64
import hashlib
import hmac
import re
import uuid
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import BinaryIO, Literal

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict
from sqlalchemy.exc import DBAPIError, DisconnectionError
from sqlalchemy.orm import Session

from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_blob_store,
    get_ciphertext_stager,
    get_authenticated_principal,
    get_db_session,
)
from remanence.api.problems import PROBLEM_CATALOG, problem_response
from remanence.capsules.abort_service import CapsuleAbortError, CapsuleAbortService
from remanence.capsules.blob_models import CapsuleBlobKind
from remanence.capsules.delivery_models import RecipientDeliveryStatus
from remanence.capsules.draft_service import (
    CapsuleDraftResult,
    CapsuleDraftService,
    CapsuleDraftServiceError,
)
from remanence.capsules.encoding import decode_canonical_base64url
from remanence.capsules.finalize_service import (
    CapsuleFinalizeEnvelope,
    CapsuleFinalizeError,
    CapsuleFinalizeResult,
    CapsuleFinalizeService,
)
from remanence.capsules.incoming_cursor import IncomingCursorCodecError, decode_incoming_cursor, encode_incoming_cursor
from remanence.capsules.incoming_query_service import (
    IncomingBlobSnapshot,
    IncomingCapsulePage,
    IncomingCapsuleQueryError,
    IncomingCapsuleQueryService,
    IncomingCapsuleSnapshot,
    IncomingEnvelopeSnapshot,
)
from remanence.capsules.limits import (
    LIMITS_V1,
    MAX_CREATE_DRAFT_REQUEST_BYTES,
    MAX_FINALIZE_REQUEST_BYTES,
)
from remanence.capsules.models import CapsuleState
from remanence.capsules.promotion_service import (
    CapsuleBlobPromotionError,
    CapsuleBlobPromotionService,
)
from remanence.capsules.recipient_blob_query_service import (
    RecipientBlobQueryError,
    RecipientBlobQueryService,
    RecipientBlobSnapshot,
)
from remanence.capsules.recipient_material_synced_service import (
    RecipientMaterialSyncedError,
    RecipientMaterialSyncedResult,
    RecipientMaterialSyncedService,
    is_transient_database_unavailability,
)
from remanence.capsules.schemas import (
    CapsuleDraftValidationError,
    parse_create_capsule_draft_request,
    parse_finalize_capsule_request,
)
from remanence.storage import (
    BlobInfo,
    BlobIntegrityError,
    BlobNotFoundError,
    BlobStore,
    BlobStoreError,
    CiphertextStager,
    InvalidBlobKeyError,
    InvalidStagingExpectationError,
    StagedBlob,
    StagingHashMismatchError,
    StagingIOError,
    StagingSizeExceededError,
    StagingSizeTruncatedError,
)


router = APIRouter()

_IDEMPOTENCY_HEADER = b"idempotency-key"
_CONTENT_LENGTH_HEADER = b"content-length"
_CONTENT_TYPE_HEADER = b"content-type"
_CIPHERTEXT_HASH_HEADER = b"x-remanence-ciphertext-sha256"
_CONTENT_ENCODING_HEADER = b"content-encoding"
_TRANSFER_ENCODING_HEADER = b"transfer-encoding"
_RANGE_HEADER = b"range"
_CONTENT_RANGE_HEADER = b"content-range"
_OCTET_STREAM = "application/octet-stream"
_JSON_MEDIA_TYPE = "application/json"
_BLOB_DOWNLOAD_CHUNK_SIZE = 64 * 1024
_CACHE_CONTROL_PRIVATE_NO_STORE = "private, no-store"
_DECIMAL = re.compile(r"[0-9]+")
_INCOMING_LIMIT_RE = re.compile(r"(?:[1-9]|[1-9][0-9]|100)")
_STORAGE_UNAVAILABLE_CODES = frozenset({"STORAGE_IO"})
_STORAGE_INTERNAL_CODES = frozenset(
    {"STORAGE_INTEGRITY", "STORAGE_INVALID", "STORAGE_NOT_FOUND"}
)


class CapsuleDraftBlobResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")

    blob_id: uuid.UUID
    state: Literal["DECLARED", "STORED"]


class CapsuleDraftResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")

    capsule_id: uuid.UUID
    state: Literal["DRAFT"]
    draft_expires_at: datetime
    blobs: list[CapsuleDraftBlobResponse]


class CapsuleFinalizeResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")

    capsule_id: uuid.UUID
    state: Literal["READY"]
    ready_at: datetime


class IncomingSignedStatementResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", hide_input_in_errors=True, frozen=True)

    statement: str
    statement_sha256: str
    signature: str


class IncomingEnvelopeResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", hide_input_in_errors=True, frozen=True)

    recipient_key_bundle_id: uuid.UUID
    ciphertext: str
    ciphertext_size: int
    ciphertext_sha256: str


class IncomingBlobResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", hide_input_in_errors=True, frozen=True)

    blob_id: uuid.UUID
    kind: Literal["RECOGNITION_MANIFEST", "CONTENT_MANIFEST", "PHOTO"]
    ordinal: int | None
    ciphertext_size: int
    ciphertext_sha256: str


class IncomingCapsuleItemResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", hide_input_in_errors=True, frozen=True)

    capsule_id: uuid.UUID
    sender_user_id: uuid.UUID
    recipient_user_id: uuid.UUID
    sender_key_bundle_id: uuid.UUID
    recipient_key_bundle_id: uuid.UUID
    protocol_version: int
    ready_at: datetime
    signed_publish_statement: IncomingSignedStatementResponse
    recipient_envelope: IncomingEnvelopeResponse
    blobs: tuple[IncomingBlobResponse, ...]


class IncomingCapsulesResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", hide_input_in_errors=True, frozen=True)

    items: tuple[IncomingCapsuleItemResponse, ...]
    has_more: bool
    next_cursor: str | None


@dataclass(frozen=True)
class _UploadHeaders:
    expected_size: int
    expected_sha256_hex: str
    idempotency_key: uuid.UUID


async def _read_bounded_body(
    request: Request, *, max_bytes: int = MAX_CREATE_DRAFT_REQUEST_BYTES
) -> bytes:
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        if not isinstance(chunk, bytes):
            raise CapsuleDraftValidationError()
        if len(chunk) > max_bytes - total:
            raise CapsuleDraftValidationError()
        chunks.append(chunk)
        total += len(chunk)
    if total == 0:
        raise CapsuleDraftValidationError()
    return b"".join(chunks)


async def _require_empty_material_synced_body(request: Request) -> None:
    """Reject request-body tricks without buffering an input payload."""

    headers = request.scope.get("headers", [])
    content_length = _header_values(headers, _CONTENT_LENGTH_HEADER)
    if len(content_length) > 1:
        raise CapsuleDraftValidationError()
    if content_length:
        if not isinstance(content_length[0], bytes) or content_length[0] != b"0":
            raise CapsuleDraftValidationError()

    if _header_values(headers, _TRANSFER_ENCODING_HEADER) or _header_values(
        headers, _CONTENT_ENCODING_HEADER
    ):
        raise CapsuleDraftValidationError()

    content_type = _header_values(headers, _CONTENT_TYPE_HEADER)
    if len(content_type) > 1:
        raise CapsuleDraftValidationError()
    if content_type:
        if not isinstance(content_type[0], bytes):
            raise CapsuleDraftValidationError()
        try:
            if content_type[0].decode("ascii").strip(" \t") != _JSON_MEDIA_TYPE:
                raise CapsuleDraftValidationError()
        except (AttributeError, TypeError, UnicodeDecodeError):
            raise CapsuleDraftValidationError() from None

    async for chunk in request.stream():
        if not isinstance(chunk, bytes) or chunk:
            raise CapsuleDraftValidationError()


def _single_idempotency_key(request: Request) -> uuid.UUID:
    values = [
        value
        for name, value in request.scope.get("headers", [])
        if name.lower() == _IDEMPOTENCY_HEADER
    ]
    if len(values) != 1:
        raise CapsuleDraftValidationError()
    try:
        text = values[0].decode("ascii")
        parsed = uuid.UUID(text)
    except (UnicodeDecodeError, ValueError, AttributeError, TypeError):
        raise CapsuleDraftValidationError() from None
    if str(parsed) != text:
        raise CapsuleDraftValidationError()
    return parsed


def _header_values(
    headers: Iterable[tuple[bytes, bytes]],
    name: bytes,
) -> list[bytes]:
    return [value for header_name, value in headers if header_name.lower() == name]


def _single_raw_header(headers: Iterable[tuple[bytes, bytes]], name: bytes) -> bytes:
    values = _header_values(headers, name)
    if len(values) != 1 or not isinstance(values[0], bytes):
        raise CapsuleDraftValidationError()
    return values[0]


def _canonical_path_uuid(value: str) -> uuid.UUID:
    try:
        parsed = uuid.UUID(value)
    except (AttributeError, TypeError, ValueError):
        raise CapsuleDraftValidationError() from None
    if str(parsed) != value:
        raise CapsuleDraftValidationError()
    return parsed


def _parse_upload_headers(headers: Iterable[tuple[bytes, bytes]]) -> _UploadHeaders:
    if any(
        _header_values(headers, name)
        for name in (
            _CONTENT_ENCODING_HEADER,
            _TRANSFER_ENCODING_HEADER,
            _RANGE_HEADER,
            _CONTENT_RANGE_HEADER,
        )
    ):
        raise CapsuleDraftValidationError()

    content_type = _single_raw_header(headers, _CONTENT_TYPE_HEADER)
    try:
        if content_type.decode("ascii").strip(" \t") != _OCTET_STREAM:
            raise CapsuleDraftValidationError()
    except UnicodeDecodeError:
        raise CapsuleDraftValidationError() from None

    content_length = _single_raw_header(headers, _CONTENT_LENGTH_HEADER)
    try:
        length_text = content_length.decode("ascii")
    except UnicodeDecodeError:
        raise CapsuleDraftValidationError() from None
    if (
        _DECIMAL.fullmatch(length_text) is None
        or length_text.startswith("0")
        or len(length_text) > len(str(LIMITS_V1.encrypted_photo_max_ciphertext_bytes))
    ):
        raise CapsuleDraftValidationError()
    try:
        expected_size = int(length_text)
    except (ValueError, OverflowError):
        raise CapsuleDraftValidationError() from None
    if not 0 < expected_size <= LIMITS_V1.encrypted_photo_max_ciphertext_bytes:
        raise CapsuleDraftValidationError()

    digest_header = _single_raw_header(headers, _CIPHERTEXT_HASH_HEADER)
    try:
        digest_text = digest_header.decode("ascii")
        expected_sha256_hex = decode_canonical_base64url(
            digest_text,
            expected_length=32,
        ).hex()
    except (UnicodeDecodeError, TypeError, ValueError):
        raise CapsuleDraftValidationError() from None

    idempotency_key = _single_raw_header(headers, _IDEMPOTENCY_HEADER)
    try:
        idempotency_text = idempotency_key.decode("ascii")
        parsed_idempotency_key = uuid.UUID(idempotency_text)
    except (UnicodeDecodeError, AttributeError, TypeError, ValueError):
        raise CapsuleDraftValidationError() from None
    if str(parsed_idempotency_key) != idempotency_text:
        raise CapsuleDraftValidationError()
    return _UploadHeaders(
        expected_size=expected_size,
        expected_sha256_hex=expected_sha256_hex,
        idempotency_key=parsed_idempotency_key,
    )


def _map_capsule_problem_code(code: str) -> str:
    if code in _STORAGE_UNAVAILABLE_CODES:
        return "INTERNAL_UNAVAILABLE"
    if code in _STORAGE_INTERNAL_CODES or code not in PROBLEM_CATALOG:
        return "INTERNAL_ERROR"
    return code


def _problem_response(request: Request, code: str) -> JSONResponse:
    mapped = _map_capsule_problem_code(code)
    return problem_response(
        request,
        mapped,
        www_authenticate=(mapped == "AUTH_INVALID"),
    )


def _reject_range_headers(request: Request) -> None:
    headers = request.scope.get("headers", [])
    if _header_values(headers, _RANGE_HEADER) or _header_values(headers, _CONTENT_RANGE_HEADER):
        raise CapsuleDraftValidationError()


def _ciphertext_etag(digest: object) -> str:
    """Strong ETag: quoted lowercase hex SHA-256 of the declared ciphertext."""

    if type(digest) is not bytes or len(digest) != 32:
        raise RecipientBlobQueryError("INTERNAL_ERROR")
    return '"' + digest.hex() + '"'


def _blob_info_matches_snapshot(info: object, snapshot: RecipientBlobSnapshot) -> bool:
    if not isinstance(info, BlobInfo):
        return False
    if type(info.size) is not int or info.size != snapshot.expected_ciphertext_size:
        return False
    declared_hex = snapshot.expected_ciphertext_sha256.hex()
    if type(info.sha256_hex) is not str or len(info.sha256_hex) != len(declared_hex):
        return False
    return hmac.compare_digest(info.sha256_hex, declared_hex)


def iter_ready_blob_chunks(
    reader: BinaryIO,
    *,
    expected_size: int,
    chunk_size: int = _BLOB_DOWNLOAD_CHUNK_SIZE,
) -> Iterator[bytes]:
    if type(expected_size) is not int or expected_size <= 0 or type(chunk_size) is not int or chunk_size <= 0:
        try:
            reader.close()
        except Exception:
            pass
        raise BlobIntegrityError("blob integrity check failed")
    remaining = expected_size
    try:
        while remaining > 0:
            try:
                chunk = reader.read(min(chunk_size, remaining))
            except Exception:
                raise BlobStoreError("blob read failed") from None
            if type(chunk) is not bytes or not chunk:
                raise BlobIntegrityError("blob integrity check failed")
            if len(chunk) > remaining:
                raise BlobIntegrityError("blob integrity check failed")
            remaining -= len(chunk)
            yield chunk
        try:
            leftover = reader.read(1)
        except Exception:
            raise BlobStoreError("blob read failed") from None
        if type(leftover) is not bytes or leftover != b"":
            raise BlobIntegrityError("blob integrity check failed")
    finally:
        try:
            reader.close()
        except Exception:
            pass


class _OwnedBlobBody:
    """Yields blob chunks and always closes the acquired reader context."""

    __slots__ = ("_cm", "_reader", "_chunks", "_closed")

    def __init__(self, reader_cm: object, reader: BinaryIO, *, expected_size: int) -> None:
        self._cm = reader_cm
        self._reader = reader
        self._chunks = iter_ready_blob_chunks(reader, expected_size=expected_size)
        self._closed = False

    def __iter__(self) -> Iterator[bytes]:
        return self

    def __next__(self) -> bytes:
        try:
            return next(self._chunks)
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._chunks.close()
        except Exception:
            pass
        try:
            self._reader.close()
        except Exception:
            pass
        try:
            exit_cm = getattr(self._cm, "__exit__", None)
            if exit_cm is not None:
                exit_cm(None, None, None)
        except Exception:
            pass

    async def aclose(self) -> None:
        self.close()


class _OwnedBlobStreamingResponse(StreamingResponse):
    """StreamingResponse that always closes the acquired blob reader."""

    def __init__(
        self,
        body: _OwnedBlobBody,
        *,
        status_code: int = 200,
        headers: dict[str, str] | None = None,
        media_type: str | None = None,
    ) -> None:
        self._owned_body = body
        super().__init__(
            body,
            status_code=status_code,
            headers=headers,
            media_type=media_type,
        )

    async def __call__(self, scope, receive, send) -> None:
        try:
            await super().__call__(scope, receive, send)
        finally:
            self._owned_body.close()


def _response_dto(result: CapsuleDraftResult) -> CapsuleDraftResponse:
    return CapsuleDraftResponse(
        capsule_id=result.capsule_id,
        state=result.state.value,
        draft_expires_at=result.draft_expires_at,
        blobs=[
            CapsuleDraftBlobResponse(blob_id=blob.blob_id, state=blob.state.value)
            for blob in result.blobs
        ],
    )


def _require_material_synced_result(
    result: object, *, capsule_id: uuid.UUID
) -> RecipientMaterialSyncedResult:
    if not isinstance(result, RecipientMaterialSyncedResult):
        raise RecipientMaterialSyncedError("INTERNAL_ERROR")
    if result.capsule_id != capsule_id:
        raise RecipientMaterialSyncedError("INTERNAL_ERROR")
    if result.state is not RecipientDeliveryStatus.CIPHERTEXT_SYNCED:
        raise RecipientMaterialSyncedError("INTERNAL_ERROR")
    synced_at = result.ciphertext_synced_at
    if (
        not isinstance(synced_at, datetime)
        or synced_at.tzinfo is None
        or synced_at.utcoffset() != timedelta(0)
    ):
        raise RecipientMaterialSyncedError("INTERNAL_ERROR")
    return result


@router.post(
    "/v1/capsules/{capsule_id}/material-synced",
    status_code=204,
    response_model=None,
)
async def mark_capsule_material_synced(
    capsule_id: str,
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session, use_cache=False),
) -> Response | JSONResponse:
    try:
        parsed_capsule_id = _canonical_path_uuid(capsule_id)
        await _require_empty_material_synced_body(request)
        with session.begin():
            result = RecipientMaterialSyncedService(session).mark_material_synced(
                authenticated_recipient_user_id=principal.user_id,
                capsule_id=parsed_capsule_id,
                now=datetime.now(timezone.utc),
            )
            _require_material_synced_result(result, capsule_id=parsed_capsule_id)
        return Response(status_code=204)
    except CapsuleDraftValidationError as exc:
        return _problem_response(request, exc.code)
    except RecipientMaterialSyncedError as exc:
        return _problem_response(request, exc.code)
    except DisconnectionError:
        return _problem_response(request, "INTERNAL_UNAVAILABLE")
    except DBAPIError as exc:
        return _problem_response(
            request,
            "INTERNAL_UNAVAILABLE"
            if is_transient_database_unavailability(exc)
            else "INTERNAL_ERROR",
        )
    except Exception:
        return _problem_response(request, "INTERNAL_ERROR")


@router.put(
    "/v1/capsules/{capsule_id}/blobs/{blob_id}",
    status_code=204,
    response_model=None,
)
async def upload_capsule_blob(
    capsule_id: str,
    blob_id: str,
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session, use_cache=False),
    blob_store: BlobStore = Depends(get_blob_store),
    ciphertext_stager: CiphertextStager = Depends(get_ciphertext_stager),
) -> Response | JSONResponse:
    staged: StagedBlob | None = None
    service_owns_staged = False
    try:
        parsed_capsule_id = _canonical_path_uuid(capsule_id)
        parsed_blob_id = _canonical_path_uuid(blob_id)
        upload_headers = _parse_upload_headers(request.scope.get("headers", []))
        staged = await ciphertext_stager.stage(
            request.stream(),
            expected_size=upload_headers.expected_size,
            expected_sha256=upload_headers.expected_sha256_hex,
            max_bytes=LIMITS_V1.encrypted_photo_max_ciphertext_bytes,
        )
        with session.begin():
            service = CapsuleBlobPromotionService(session, blob_store)
            service_owns_staged = True
            service.promote_blob(
                authenticated_sender_user_id=principal.user_id,
                capsule_id=parsed_capsule_id,
                blob_id=parsed_blob_id,
                staged_blob=staged,
                now=datetime.now(timezone.utc),
            )
        return Response(status_code=204)
    except CapsuleDraftValidationError as exc:
        return _problem_response(request, exc.code)
    except (StagingSizeExceededError, StagingSizeTruncatedError):
        return _problem_response(request, "BLOB_SIZE_INVALID")
    except StagingHashMismatchError:
        return _problem_response(request, "BLOB_HASH_MISMATCH")
    except InvalidStagingExpectationError:
        return _problem_response(request, "VALIDATION_FAILED")
    except StagingIOError:
        return _problem_response(request, "INTERNAL_UNAVAILABLE")
    except CapsuleBlobPromotionError as exc:
        return _problem_response(request, exc.code)
    except Exception:
        return _problem_response(request, "INTERNAL_ERROR")
    finally:
        if staged is not None and not service_owns_staged:
            try:
                staged.cleanup()
            except Exception:
                pass


@router.get(
    "/v1/capsules/{capsule_id}/blobs/{blob_id}",
    response_model=None,
    status_code=200,
)
def download_capsule_blob(
    capsule_id: str,
    blob_id: str,
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session, use_cache=False),
    blob_store: BlobStore = Depends(get_blob_store),
) -> Response | JSONResponse:
    reader_cm: object | None = None
    reader: BinaryIO | None = None
    stream: _OwnedBlobBody | None = None
    try:
        parsed_capsule_id = _canonical_path_uuid(capsule_id)
        parsed_blob_id = _canonical_path_uuid(blob_id)
        _reject_range_headers(request)
        with session.begin():
            snapshot = RecipientBlobQueryService(session).get_ready_blob(
                authenticated_recipient_user_id=principal.user_id,
                capsule_id=parsed_capsule_id,
                blob_id=parsed_blob_id,
            )
        if not isinstance(snapshot, RecipientBlobSnapshot):
            raise RecipientBlobQueryError("INTERNAL_ERROR")
        object_key = snapshot.object_key
        if type(object_key) is not str or not object_key:
            raise RecipientBlobQueryError("INTERNAL_ERROR")
        try:
            info = blob_store.stat(object_key)
        except InvalidBlobKeyError:
            return _problem_response(request, "INTERNAL_ERROR")
        except BlobNotFoundError:
            return _problem_response(request, "INTERNAL_UNAVAILABLE")
        except BlobStoreError:
            return _problem_response(request, "INTERNAL_UNAVAILABLE")
        if not _blob_info_matches_snapshot(info, snapshot):
            return _problem_response(request, "INTERNAL_ERROR")
        etag = _ciphertext_etag(snapshot.expected_ciphertext_sha256)
        try:
            reader_cm = blob_store.open_reader(object_key)
            reader = reader_cm.__enter__()
        except InvalidBlobKeyError:
            return _problem_response(request, "INTERNAL_ERROR")
        except BlobNotFoundError:
            return _problem_response(request, "INTERNAL_UNAVAILABLE")
        except BlobStoreError:
            return _problem_response(request, "INTERNAL_UNAVAILABLE")
        stream = _OwnedBlobBody(
            reader_cm, reader, expected_size=snapshot.expected_ciphertext_size
        )
        reader_cm = None
        reader = None
        response = _OwnedBlobStreamingResponse(
            stream,
            status_code=200,
            media_type=_OCTET_STREAM,
            headers={
                "Content-Length": str(info.size),
                "ETag": etag,
                "Cache-Control": _CACHE_CONTROL_PRIVATE_NO_STORE,
            },
        )
        stream = None
        return response
    except CapsuleDraftValidationError as exc:
        return _problem_response(request, exc.code)
    except RecipientBlobQueryError as exc:
        return _problem_response(request, exc.code)
    except Exception:
        return _problem_response(request, "INTERNAL_ERROR")
    finally:
        if stream is not None:
            try:
                stream.close()
            except Exception:
                pass
        elif reader is not None:
            try:
                reader.close()
            except Exception:
                pass
            if reader_cm is not None:
                try:
                    exit_cm = getattr(reader_cm, "__exit__", None)
                    if exit_cm is not None:
                        exit_cm(None, None, None)
                except Exception:
                    pass


@router.post(
    "/v1/capsules",
    response_model=CapsuleDraftResponse,
    status_code=201,
)
async def create_capsule_draft(
    request: Request,
    response: Response,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session, use_cache=False),
) -> CapsuleDraftResponse | JSONResponse:
    try:
        idempotency_key = _single_idempotency_key(request)
        raw_body = await _read_bounded_body(request)
        parsed = parse_create_capsule_draft_request(raw_body)
        request_sha256 = hashlib.sha256(raw_body).digest()
        with session.begin():
            result = CapsuleDraftService(session).create_draft(
                authenticated_sender_user_id=principal.user_id,
                request=parsed,
                idempotency_key=idempotency_key,
                request_sha256=request_sha256,
                now=datetime.now(timezone.utc),
            )
    except CapsuleDraftValidationError as exc:
        return _problem_response(request, exc.code)
    except CapsuleDraftServiceError as exc:
        return _problem_response(request, exc.code)
    except Exception:
        return _problem_response(request, "INTERNAL_ERROR")

    response.status_code = 200 if result.is_replay else 201
    return _response_dto(result)


def _accepted_finalize_response(result: object) -> tuple[CapsuleFinalizeResponse, bool]:
    if not isinstance(result, CapsuleFinalizeResult):
        raise CapsuleFinalizeError("INTERNAL_ERROR")
    if result.state is not CapsuleState.READY:
        raise CapsuleFinalizeError("INTERNAL_ERROR")
    if type(result.is_replay) is not bool:
        raise CapsuleFinalizeError("INTERNAL_ERROR")
    if not isinstance(result.capsule_id, uuid.UUID):
        raise CapsuleFinalizeError("INTERNAL_ERROR")
    ready_at = result.ready_at
    if (
        not isinstance(ready_at, datetime)
        or ready_at.tzinfo is None
        or ready_at.utcoffset() != timedelta(0)
    ):
        raise CapsuleFinalizeError("INTERNAL_ERROR")
    try:
        dto = CapsuleFinalizeResponse.model_validate(
            {
                "capsule_id": result.capsule_id,
                "state": "READY",
                "ready_at": ready_at,
            }
        )
    except Exception:
        raise CapsuleFinalizeError("INTERNAL_ERROR") from None
    return dto, result.is_replay


@router.post(
    "/v1/capsules/{capsule_id}/finalize",
    response_model=CapsuleFinalizeResponse,
    status_code=201,
)
async def finalize_capsule(
    capsule_id: str,
    request: Request,
    response: Response,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session, use_cache=False),
    blob_store: BlobStore = Depends(get_blob_store),
) -> CapsuleFinalizeResponse | JSONResponse:
    try:
        parsed_capsule_id = _canonical_path_uuid(capsule_id)
        raw_body = await _read_bounded_body(
            request, max_bytes=MAX_FINALIZE_REQUEST_BYTES
        )
        parsed = parse_finalize_capsule_request(raw_body)
        with session.begin():
            result = CapsuleFinalizeService(session, blob_store).finalize(
                authenticated_sender_user_id=principal.user_id,
                capsule_id=parsed_capsule_id,
                statement=parsed.signed_publish_statement.statement,
                signature=parsed.signed_publish_statement.signature,
                sender_key_bundle_id=parsed.signed_publish_statement.sender_key_bundle_id,
                envelope=CapsuleFinalizeEnvelope(
                    recipient_key_bundle_id=parsed.recipient_envelope.recipient_key_bundle_id,
                    ciphertext=parsed.recipient_envelope.ciphertext,
                    ciphertext_size=parsed.recipient_envelope.ciphertext_size,
                    ciphertext_sha256=parsed.recipient_envelope.ciphertext_sha256,
                ),
                now=datetime.now(timezone.utc),
            )
            dto, is_replay = _accepted_finalize_response(result)
        response.status_code = 200 if is_replay else 201
        return dto
    except CapsuleDraftValidationError as exc:
        return _problem_response(request, exc.code)
    except CapsuleFinalizeError as exc:
        return _problem_response(request, exc.code)
    except Exception:
        return _problem_response(request, "INTERNAL_ERROR")


@router.delete(
    "/v1/capsules/{capsule_id}",
    status_code=204,
    response_model=None,
)
def abort_capsule(
    capsule_id: str,
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session, use_cache=False),
) -> Response | JSONResponse:
    try:
        parsed_capsule_id = _canonical_path_uuid(capsule_id)
        with session.begin():
            CapsuleAbortService(session).abort(
                authenticated_sender_user_id=principal.user_id,
                capsule_id=parsed_capsule_id,
                now=datetime.now(timezone.utc),
            )
        return Response(status_code=204)
    except CapsuleDraftValidationError as exc:
        return _problem_response(request, exc.code)
    except CapsuleAbortError as exc:
        return _problem_response(request, exc.code)
    except Exception:
        return _problem_response(request, "INTERNAL_ERROR")


def _canonical_b64url(value: object) -> str:
    if type(value) is not bytes or not value:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    encoded = base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")
    mapped: IncomingCapsuleQueryError | None = None
    decoded: bytes | None = None
    try:
        decoded = decode_canonical_base64url(encoded, expected_length=len(value))
    except Exception:
        mapped = IncomingCapsuleQueryError("INTERNAL_ERROR")
    if mapped is not None:
        raise mapped
    if decoded != value:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    return encoded


def _parse_incoming_query(request: Request) -> tuple[str | None, int]:
    raw = request.scope.get("query_string", b"")
    if raw is None:
        raw = b""
    if not isinstance(raw, (bytes, bytearray)):
        raise CapsuleDraftValidationError()
    try:
        text = bytes(raw).decode("ascii")
    except UnicodeDecodeError:
        raise CapsuleDraftValidationError() from None
    cursor_values: list[str] = []
    limit_values: list[str] = []
    if text:
        for part in text.split("&"):
            if not part or "=" not in part:
                raise CapsuleDraftValidationError()
            name, value = part.split("=", 1)
            if name == "cursor":
                cursor_values.append(value)
            elif name == "limit":
                limit_values.append(value)
            else:
                raise CapsuleDraftValidationError()
    if len(cursor_values) > 1 or len(limit_values) > 1:
        raise CapsuleDraftValidationError()
    cursor: str | None = None
    if cursor_values:
        cursor = cursor_values[0]
        if not cursor:
            raise CapsuleDraftValidationError()
        mapped: IncomingCapsuleQueryError | CapsuleDraftValidationError | None = None
        try:
            decode_incoming_cursor(cursor)
        except IncomingCursorCodecError:
            mapped = CapsuleDraftValidationError()
        except Exception:
            mapped = CapsuleDraftValidationError()
        if mapped is not None:
            raise mapped
    limit = LIMITS_V1.incoming_page_default
    if limit_values:
        limit_text = limit_values[0]
        if _INCOMING_LIMIT_RE.fullmatch(limit_text) is None:
            raise CapsuleDraftValidationError()
        parsed_limit = int(limit_text)
        if type(parsed_limit) is not int:
            raise CapsuleDraftValidationError()
        if not 1 <= parsed_limit <= LIMITS_V1.incoming_page_max:
            raise CapsuleDraftValidationError()
        limit = parsed_limit
    return cursor, limit


def _incoming_blob_response(blob: object) -> IncomingBlobResponse:
    if not isinstance(blob, IncomingBlobSnapshot):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if not isinstance(blob.blob_id, uuid.UUID):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if blob.kind is CapsuleBlobKind.PHOTO:
        kind: Literal["RECOGNITION_MANIFEST", "CONTENT_MANIFEST", "PHOTO"] = "PHOTO"
        if type(blob.ordinal) is not int:
            raise IncomingCapsuleQueryError("INTERNAL_ERROR")
        ordinal: int | None = blob.ordinal
    elif blob.kind is CapsuleBlobKind.RECOGNITION_MANIFEST:
        kind = "RECOGNITION_MANIFEST"
        if blob.ordinal is not None:
            raise IncomingCapsuleQueryError("INTERNAL_ERROR")
        ordinal = None
    elif blob.kind is CapsuleBlobKind.CONTENT_MANIFEST:
        kind = "CONTENT_MANIFEST"
        if blob.ordinal is not None:
            raise IncomingCapsuleQueryError("INTERNAL_ERROR")
        ordinal = None
    else:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if type(blob.expected_ciphertext_size) is not int:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    try:
        return IncomingBlobResponse(
            blob_id=blob.blob_id,
            kind=kind,
            ordinal=ordinal,
            ciphertext_size=blob.expected_ciphertext_size,
            ciphertext_sha256=_canonical_b64url(blob.expected_ciphertext_sha256),
        )
    except IncomingCapsuleQueryError:
        raise
    except Exception:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR") from None


def _incoming_item_response(
    item: object, *, recipient_id: uuid.UUID
) -> IncomingCapsuleItemResponse:
    if not isinstance(item, IncomingCapsuleSnapshot):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    ids = (
        item.capsule_id,
        item.sender_user_id,
        item.recipient_user_id,
        item.sender_key_bundle_id,
        item.recipient_key_bundle_id,
    )
    if any(not isinstance(value, uuid.UUID) for value in ids):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if item.recipient_user_id != recipient_id:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if type(item.protocol_version) is not int or item.protocol_version != LIMITS_V1.protocol_version:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    ready_at = item.ready_at
    if (
        not isinstance(ready_at, datetime)
        or ready_at.tzinfo is None
        or ready_at.utcoffset() != timedelta(0)
    ):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    envelope = item.envelope
    if not isinstance(envelope, IncomingEnvelopeSnapshot):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if not isinstance(envelope.recipient_key_bundle_id, uuid.UUID):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if envelope.recipient_key_bundle_id != item.recipient_key_bundle_id:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if type(envelope.ciphertext_size) is not int:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if not isinstance(item.blobs, tuple):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    try:
        statement = IncomingSignedStatementResponse(
            statement=_canonical_b64url(item.signed_statement),
            statement_sha256=_canonical_b64url(item.signed_statement_sha256),
            signature=_canonical_b64url(item.publish_signature),
        )
        envelope_dto = IncomingEnvelopeResponse(
            recipient_key_bundle_id=envelope.recipient_key_bundle_id,
            ciphertext=_canonical_b64url(envelope.ciphertext),
            ciphertext_size=envelope.ciphertext_size,
            ciphertext_sha256=_canonical_b64url(envelope.ciphertext_sha256),
        )
        blobs = tuple(_incoming_blob_response(blob) for blob in item.blobs)
        return IncomingCapsuleItemResponse(
            capsule_id=item.capsule_id,
            sender_user_id=item.sender_user_id,
            recipient_user_id=item.recipient_user_id,
            sender_key_bundle_id=item.sender_key_bundle_id,
            recipient_key_bundle_id=item.recipient_key_bundle_id,
            protocol_version=item.protocol_version,
            ready_at=ready_at,
            signed_publish_statement=statement,
            recipient_envelope=envelope_dto,
            blobs=blobs,
        )
    except IncomingCapsuleQueryError:
        raise
    except Exception:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR") from None


def _incoming_page_response(
    result: object, *, recipient_id: uuid.UUID, requested_cursor: str | None
) -> IncomingCapsulesResponse:
    if not isinstance(result, IncomingCapsulePage):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if type(result.has_more) is not bool:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if not isinstance(result.items, tuple):
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    items = tuple(_incoming_item_response(item, recipient_id=recipient_id) for item in result.items)
    if result.has_more is True and not items:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if result.next_cursor is not None:
        if type(result.next_cursor) is not str or not result.next_cursor:
            raise IncomingCapsuleQueryError("INTERNAL_ERROR")
        try:
            decoded = decode_incoming_cursor(result.next_cursor)
            canonical = encode_incoming_cursor(
                ready_at=decoded.ready_at, capsule_id=decoded.capsule_id
            )
        except Exception:
            raise IncomingCapsuleQueryError("INTERNAL_ERROR") from None
        if canonical != result.next_cursor:
            raise IncomingCapsuleQueryError("INTERNAL_ERROR")
    if items:
        last = result.items[-1]
        if not isinstance(last, IncomingCapsuleSnapshot):
            raise IncomingCapsuleQueryError("INTERNAL_ERROR")
        encoded: str | None = None
        mapped: IncomingCapsuleQueryError | None = None
        try:
            encoded = encode_incoming_cursor(ready_at=last.ready_at, capsule_id=last.capsule_id)
        except Exception:
            mapped = IncomingCapsuleQueryError("INTERNAL_ERROR")
        if mapped is not None:
            raise mapped
        if encoded != result.next_cursor:
            raise IncomingCapsuleQueryError("INTERNAL_ERROR")
        next_cursor: str | None = result.next_cursor
    else:
        if result.next_cursor != requested_cursor:
            raise IncomingCapsuleQueryError("INTERNAL_ERROR")
        next_cursor = result.next_cursor
    try:
        return IncomingCapsulesResponse(
            items=items, has_more=result.has_more, next_cursor=next_cursor
        )
    except IncomingCapsuleQueryError:
        raise
    except Exception:
        raise IncomingCapsuleQueryError("INTERNAL_ERROR") from None


@router.get(
    "/v1/capsules/incoming",
    response_model=IncomingCapsulesResponse,
    status_code=200,
)
def list_incoming_capsules(
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session, use_cache=False),
) -> IncomingCapsulesResponse | JSONResponse:
    try:
        cursor, limit = _parse_incoming_query(request)
        with session.begin():
            result = IncomingCapsuleQueryService(session).list_incoming(
                authenticated_recipient_user_id=principal.user_id,
                cursor=cursor,
                limit=limit,
            )
            dto = _incoming_page_response(
                result,
                recipient_id=principal.user_id,
                requested_cursor=cursor,
            )
            try:
                payload = dto.model_dump(mode="json")
            except Exception:
                raise IncomingCapsuleQueryError("INTERNAL_ERROR") from None
        return JSONResponse(content=payload, status_code=200)
    except CapsuleDraftValidationError as exc:
        return _problem_response(request, exc.code)
    except IncomingCapsuleQueryError as exc:
        return _problem_response(request, exc.code)
    except Exception:
        return _problem_response(request, "INTERNAL_ERROR")
