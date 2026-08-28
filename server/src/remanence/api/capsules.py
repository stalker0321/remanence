"""Authenticated capsule draft creation endpoint."""

import hashlib
import re
import uuid
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Literal

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict
from sqlalchemy.orm import Session

from remanence.api.auth_schemas import ProblemDetail
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_blob_store,
    get_ciphertext_stager,
    get_authenticated_principal,
    get_db_session,
)
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
from remanence.capsules.schemas import (
    CapsuleDraftValidationError,
    parse_create_capsule_draft_request,
    parse_finalize_capsule_request,
)
from remanence.storage import (
    BlobStore,
    CiphertextStager,
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
_DECIMAL = re.compile(r"[0-9]+")
_ERRORS = {
    "VALIDATION_FAILED": (422, "Invalid request"),
    "IDEMPOTENCY_CONFLICT": (409, "Idempotency conflict"),
    "RECIPIENT_NOT_CONFIRMED": (409, "Recipient not confirmed"),
    "RECIPIENT_KEY_STALE": (409, "Recipient key stale"),
    "KEY_BUNDLE_NOT_FOUND": (404, "Key bundle not found"),
    "KEY_BUNDLE_INVALID": (409, "Key bundle invalid"),
    "AUTH_INVALID": (401, "Authentication required"),
    "INTERNAL_ERROR": (500, "Internal server error"),
    "CAPSULE_NOT_FOUND": (404, "Capsule not found"),
    "CAPSULE_STATE_INVALID": (409, "Capsule state invalid"),
    "DRAFT_EXPIRED": (409, "Draft expired"),
    "BLOB_NOT_DECLARED": (404, "Blob not declared"),
    "BLOB_SIZE_INVALID": (400, "Blob size invalid"),
    "BLOB_HASH_MISMATCH": (400, "Blob hash mismatch"),
    "BLOB_CONFLICT": (409, "Blob conflict"),
    "STATEMENT_INVALID": (422, "Invalid statement"),
    "SIGNATURE_INVALID": (422, "Invalid signature"),
    "ENVELOPE_INVALID": (422, "Invalid envelope"),
    "FINALIZE_CONFLICT": (409, "Finalize conflict"),
    "KEY_BUNDLE_REVOKED": (409, "Key bundle revoked"),
}


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


def _problem_response(code: str) -> JSONResponse:
    status, title = _ERRORS.get(code, _ERRORS["INTERNAL_ERROR"])
    safe_code = code if code in _ERRORS else "INTERNAL_ERROR"
    problem = ProblemDetail(
        type=f"https://remanence.invalid/problems/{safe_code.lower().replace('_', '-')}",
        title=title,
        status=status,
        code=safe_code,
    )
    return JSONResponse(
        status_code=status,
        content=problem.model_dump(),
        media_type="application/problem+json",
    )


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
        return _problem_response(exc.code)
    except (StagingSizeExceededError, StagingSizeTruncatedError):
        return _problem_response("BLOB_SIZE_INVALID")
    except StagingHashMismatchError:
        return _problem_response("BLOB_HASH_MISMATCH")
    except InvalidStagingExpectationError:
        return _problem_response("VALIDATION_FAILED")
    except StagingIOError:
        return _problem_response("INTERNAL_ERROR")
    except CapsuleBlobPromotionError as exc:
        return _problem_response(exc.code)
    except Exception:
        return _problem_response("INTERNAL_ERROR")
    finally:
        if staged is not None and not service_owns_staged:
            try:
                staged.cleanup()
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
        return _problem_response(exc.code)
    except CapsuleDraftServiceError as exc:
        return _problem_response(exc.code)
    except Exception:
        return _problem_response("INTERNAL_ERROR")

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
        return _problem_response(exc.code)
    except CapsuleFinalizeError as exc:
        return _problem_response(exc.code)
    except Exception:
        return _problem_response("INTERNAL_ERROR")
