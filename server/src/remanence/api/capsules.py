"""Authenticated capsule draft creation endpoint."""

import hashlib
import uuid
from datetime import datetime, timezone
from typing import Literal

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict
from sqlalchemy.orm import Session

from remanence.api.auth_schemas import ProblemDetail
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.capsules.draft_service import (
    CapsuleDraftResult,
    CapsuleDraftService,
    CapsuleDraftServiceError,
)
from remanence.capsules.limits import MAX_CREATE_DRAFT_REQUEST_BYTES
from remanence.capsules.schemas import (
    CapsuleDraftValidationError,
    parse_create_capsule_draft_request,
)


router = APIRouter()

_IDEMPOTENCY_HEADER = b"idempotency-key"
_ERRORS = {
    "VALIDATION_FAILED": (422, "Invalid request"),
    "IDEMPOTENCY_CONFLICT": (409, "Idempotency conflict"),
    "RECIPIENT_NOT_CONFIRMED": (409, "Recipient not confirmed"),
    "RECIPIENT_KEY_STALE": (409, "Recipient key stale"),
    "KEY_BUNDLE_NOT_FOUND": (404, "Key bundle not found"),
    "KEY_BUNDLE_INVALID": (409, "Key bundle invalid"),
    "AUTH_INVALID": (401, "Authentication required"),
    "INTERNAL_ERROR": (500, "Internal server error"),
}


class CapsuleDraftBlobResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")

    blob_id: uuid.UUID
    state: Literal["DECLARED"]


class CapsuleDraftResponse(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")

    capsule_id: uuid.UUID
    state: Literal["DRAFT"]
    draft_expires_at: datetime
    blobs: list[CapsuleDraftBlobResponse]


async def _read_bounded_body(request: Request) -> bytes:
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        if not isinstance(chunk, bytes):
            raise CapsuleDraftValidationError()
        if len(chunk) > MAX_CREATE_DRAFT_REQUEST_BYTES - total:
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
        state="DRAFT",
        draft_expires_at=result.draft_expires_at,
        blobs=[
            CapsuleDraftBlobResponse(blob_id=blob.blob_id, state="DECLARED")
            for blob in result.blobs
        ],
    )


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
