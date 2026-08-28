"""Shared RFC 7807 application/problem+json catalog and factory.

Required body fields are type, title, status, code, detail, request_id, and
retryable. Optional fields such as `fields` are allow-listed and are not
emitted by this contract. `detail` is always fixed catalog text.

AUTH_EXPIRED is catalogued for protocol completeness but is not emitted.
M4: the current session repository cannot authoritatively distinguish expiry
from other invalid access tokens without broader auth changes.
"""

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Final
from uuid import uuid4

from fastapi import Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict
from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

PROBLEM_MEDIA_TYPE: Final = "application/problem+json"
REQUEST_ID_HEADER: Final = "X-Request-ID"
PROBLEM_BODY_FIELDS: Final = frozenset(
    {"type", "title", "status", "code", "detail", "request_id", "retryable"}
)
_TYPE_PREFIX: Final = "https://remanence.invalid/problems"


@dataclass(frozen=True, slots=True)
class ProblemSpec:
    type: str
    title: str
    status: int
    code: str
    detail: str
    retryable: bool


class ProblemDetail(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    type: str
    title: str
    status: int
    code: str
    detail: str
    request_id: str
    retryable: bool


def _spec(
    code: str,
    status: int,
    title: str,
    detail: str,
    *,
    retryable: bool = False,
    emit_code: str | None = None,
    type_slug: str | None = None,
) -> ProblemSpec:
    emitted = emit_code or code
    slug = type_slug or emitted.lower().replace("_", "-")
    return ProblemSpec(
        type=f"{_TYPE_PREFIX}/{slug}",
        title=title,
        status=status,
        code=emitted,
        detail=detail,
        retryable=retryable,
    )


PROBLEM_CATALOG: Final[dict[str, ProblemSpec]] = {
    "AUTH_INVALID": _spec(
        "AUTH_INVALID",
        401,
        "Authentication invalid",
        "Authentication is required or the presented credentials are invalid.",
    ),
    # Catalogued only; do not emit until M4 can distinguish expiry.
    "AUTH_EXPIRED": _spec(
        "AUTH_EXPIRED",
        401,
        "Authentication expired",
        "The access token has expired.",
    ),
    "SESSION_REPLAYED": _spec(
        "SESSION_REPLAYED",
        401,
        "Session replayed",
        "The refresh token was already used.",
    ),
    "RATE_LIMITED": _spec(
        "RATE_LIMITED",
        429,
        "Rate limited",
        "The request was rate limited.",
        retryable=True,
    ),
    "EMAIL_UNAVAILABLE": _spec(
        "EMAIL_UNAVAILABLE",
        409,
        "Email unavailable",
        "The email address is not available.",
    ),
    "HANDLE_INVALID": _spec(
        "HANDLE_INVALID",
        422,
        "Handle invalid",
        "The handle is invalid.",
    ),
    "HANDLE_UNAVAILABLE": _spec(
        "HANDLE_UNAVAILABLE",
        409,
        "Handle unavailable",
        "The handle is not available.",
    ),
    "HANDLE_NOT_FOUND": _spec(
        "HANDLE_NOT_FOUND",
        404,
        "Handle not found",
        "The handle was not found.",
    ),
    "RECIPIENT_NOT_CONFIRMED": _spec(
        "RECIPIENT_NOT_CONFIRMED",
        409,
        "Recipient not confirmed",
        "The recipient is not confirmed.",
    ),
    "RECIPIENT_KEY_STALE": _spec(
        "RECIPIENT_KEY_STALE",
        409,
        "Recipient key stale",
        "The recipient key is stale.",
    ),
    "KEY_BUNDLE_INVALID": _spec(
        "KEY_BUNDLE_INVALID",
        409,
        "Key bundle invalid",
        "The key bundle is invalid.",
    ),
    "KEY_BUNDLE_NOT_FOUND": _spec(
        "KEY_BUNDLE_NOT_FOUND",
        404,
        "Key bundle not found",
        "The key bundle was not found.",
    ),
    "KEY_BUNDLE_REVOKED": _spec(
        "KEY_BUNDLE_REVOKED",
        409,
        "Key bundle revoked",
        "The key bundle is revoked.",
    ),
    "CAPSULE_NOT_FOUND": _spec(
        "CAPSULE_NOT_FOUND",
        404,
        "Capsule not found",
        "The capsule was not found.",
    ),
    "CAPSULE_STATE_INVALID": _spec(
        "CAPSULE_STATE_INVALID",
        409,
        "Capsule state invalid",
        "The capsule state is invalid.",
    ),
    "DRAFT_EXPIRED": _spec(
        "DRAFT_EXPIRED",
        409,
        "Draft expired",
        "The draft has expired.",
    ),
    "BLOB_NOT_DECLARED": _spec(
        "BLOB_NOT_DECLARED",
        404,
        "Blob not declared",
        "The blob was not declared.",
    ),
    "BLOB_SIZE_INVALID": _spec(
        "BLOB_SIZE_INVALID",
        422,
        "Blob size invalid",
        "The blob size is invalid.",
    ),
    "BLOB_HASH_MISMATCH": _spec(
        "BLOB_HASH_MISMATCH",
        422,
        "Blob hash mismatch",
        "The blob hash does not match.",
    ),
    "BLOB_CONFLICT": _spec(
        "BLOB_CONFLICT",
        409,
        "Blob conflict",
        "The blob conflicts with stored state.",
    ),
    "STATEMENT_INVALID": _spec(
        "STATEMENT_INVALID",
        422,
        "Invalid statement",
        "The publish statement is invalid.",
    ),
    "SIGNATURE_INVALID": _spec(
        "SIGNATURE_INVALID",
        422,
        "Invalid signature",
        "The publish signature is invalid.",
    ),
    "ENVELOPE_INVALID": _spec(
        "ENVELOPE_INVALID",
        422,
        "Invalid envelope",
        "The recipient envelope is invalid.",
    ),
    "FINALIZE_CONFLICT": _spec(
        "FINALIZE_CONFLICT",
        409,
        "Finalize conflict",
        "The finalize request conflicts with stored state.",
    ),
    "IDEMPOTENCY_CONFLICT": _spec(
        "IDEMPOTENCY_CONFLICT",
        409,
        "Idempotency conflict",
        "The idempotency key conflicts with a different request.",
    ),
    "PROTOCOL_UNSUPPORTED": _spec(
        "PROTOCOL_UNSUPPORTED",
        422,
        "Protocol unsupported",
        "The protocol version is unsupported.",
    ),
    "VALIDATION_FAILED": _spec(
        "VALIDATION_FAILED",
        422,
        "Validation failed",
        "The request is invalid.",
    ),
    "ROUTE_NOT_FOUND": _spec(
        "ROUTE_NOT_FOUND",
        404,
        "Route not found",
        "The route was not found.",
    ),
    "METHOD_NOT_ALLOWED": _spec(
        "METHOD_NOT_ALLOWED",
        405,
        "Method not allowed",
        "The method is not allowed.",
    ),
    "INTERNAL_ERROR": _spec(
        "INTERNAL_ERROR",
        500,
        "Internal server error",
        "An internal error occurred.",
    ),
    "INTERNAL_UNAVAILABLE": _spec(
        "INTERNAL_UNAVAILABLE",
        503,
        "Internal server error",
        "The service is temporarily unavailable.",
        retryable=True,
        emit_code="INTERNAL_ERROR",
        type_slug="internal-error",
    ),
}

_UNKNOWN_SPEC: Final = PROBLEM_CATALOG["INTERNAL_ERROR"]


def bind_request_id(request: Request) -> str:
    """Generate a server UUID for this request. Never read a client request-id."""
    request_id = str(uuid4())
    request.state.request_id = request_id
    return request_id


def request_id_of(request: Request) -> str:
    existing = getattr(request.state, "request_id", None)
    if isinstance(existing, str) and existing:
        return existing
    return bind_request_id(request)


def _write_request_id(scope: Scope, request_id: str) -> None:
    state = scope.get("state")
    if state is None:
        scope["state"] = {"request_id": request_id}
        return
    if isinstance(state, dict):
        state["request_id"] = request_id
        return
    setattr(state, "request_id", request_id)


class RequestIdMiddleware:
    """Pure ASGI middleware so streaming uploads are not buffered."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        request_id = str(uuid4())
        _write_request_id(scope, request_id)

        async def send_with_request_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                headers[REQUEST_ID_HEADER] = request_id
            await send(message)

        await self.app(scope, receive, send_with_request_id)


def resolve_problem(code: str) -> ProblemSpec:
    spec = PROBLEM_CATALOG.get(code)
    if spec is None:
        return _UNKNOWN_SPEC
    return spec


def problem_payload(code: str, request_id: str) -> dict[str, object]:
    spec = resolve_problem(code)
    return ProblemDetail(
        type=spec.type,
        title=spec.title,
        status=spec.status,
        code=spec.code,
        detail=spec.detail,
        request_id=request_id,
        retryable=spec.retryable,
    ).model_dump()


def problem_response(
    request: Request,
    code: str,
    *,
    www_authenticate: bool = False,
    extra_headers: Mapping[str, str] | None = None,
) -> JSONResponse:
    request_id = request_id_of(request)
    spec = resolve_problem(code)
    headers = {REQUEST_ID_HEADER: request_id}
    if www_authenticate:
        headers["WWW-Authenticate"] = "Bearer"
    if extra_headers is not None:
        for name, value in extra_headers.items():
            if name.lower() == REQUEST_ID_HEADER.lower():
                continue
            headers[name] = value
    return JSONResponse(
        status_code=spec.status,
        content=problem_payload(code, request_id),
        media_type=PROBLEM_MEDIA_TYPE,
        headers=headers,
    )
