"""Centralized application/problem+json contract tests."""

import json
from uuid import UUID

import pytest
from fastapi import APIRouter, Depends, Request
from fastapi.testclient import TestClient
from pydantic import SecretStr, ValidationError

from remanence.api.auth_schemas import ProblemDetail as ReexportedProblemDetail
from remanence.api.dependencies import get_access_bearer_token
from remanence.api.problems import (
    PROBLEM_BODY_FIELDS,
    PROBLEM_CATALOG,
    PROBLEM_MEDIA_TYPE,
    ProblemDetail,
    problem_payload,
    problem_response,
    resolve_problem,
)
from remanence.main import create_app
from remanence.settings import AppMode, Settings

pytest_plugins = ("test_registration_endpoint",)

from test_registration_endpoint import _valid_payload

_CANONICAL_UUID = "00000000-0000-4000-8000-000000000001"


def _assert_request_id(value: str) -> None:
    parsed = UUID(value)
    assert str(parsed) == value


def assert_problem(
    response,
    catalog_key: str,
    *,
    www_authenticate: bool | None = None,
) -> dict:
    spec = PROBLEM_CATALOG[catalog_key]
    assert response.status_code == spec.status
    assert response.headers["content-type"].startswith(PROBLEM_MEDIA_TYPE)
    header_id = response.headers["x-request-id"]
    _assert_request_id(header_id)
    body = response.json()
    assert set(body) == PROBLEM_BODY_FIELDS
    assert body == problem_payload(catalog_key, header_id)
    assert body["request_id"] == header_id
    assert "fields" not in body
    assert "errors" not in body
    assert "detail" in body
    if www_authenticate is True:
        assert response.headers["www-authenticate"] == "Bearer"
    elif www_authenticate is False:
        assert "www-authenticate" not in {key.lower() for key in response.headers}
    return body


def test_problem_detail_reexport_is_canonical() -> None:
    assert ReexportedProblemDetail is ProblemDetail


def test_catalog_required_codes_and_internal_unavailable() -> None:
    required = {
        "AUTH_INVALID",
        "AUTH_EXPIRED",
        "SESSION_REPLAYED",
        "RATE_LIMITED",
        "EMAIL_UNAVAILABLE",
        "HANDLE_INVALID",
        "HANDLE_UNAVAILABLE",
        "HANDLE_NOT_FOUND",
        "RECIPIENT_NOT_CONFIRMED",
        "RECIPIENT_KEY_STALE",
        "KEY_BUNDLE_INVALID",
        "KEY_BUNDLE_NOT_FOUND",
        "KEY_BUNDLE_REVOKED",
        "CAPSULE_NOT_FOUND",
        "CAPSULE_STATE_INVALID",
        "DRAFT_EXPIRED",
        "BLOB_NOT_DECLARED",
        "BLOB_SIZE_INVALID",
        "BLOB_HASH_MISMATCH",
        "BLOB_CONFLICT",
        "STATEMENT_INVALID",
        "SIGNATURE_INVALID",
        "ENVELOPE_INVALID",
        "FINALIZE_CONFLICT",
        "IDEMPOTENCY_CONFLICT",
        "PROTOCOL_UNSUPPORTED",
        "VALIDATION_FAILED",
        "INTERNAL_ERROR",
        "ROUTE_NOT_FOUND",
        "METHOD_NOT_ALLOWED",
    }
    assert required <= set(PROBLEM_CATALOG)
    assert "STORAGE_IO" not in PROBLEM_CATALOG
    assert "STORAGE_INTEGRITY" not in PROBLEM_CATALOG
    assert "STORAGE_INVALID" not in PROBLEM_CATALOG
    assert "STORAGE_NOT_FOUND" not in PROBLEM_CATALOG
    unavailable = PROBLEM_CATALOG["INTERNAL_UNAVAILABLE"]
    assert unavailable.code == "INTERNAL_ERROR"
    assert unavailable.status == 503
    assert unavailable.retryable is True
    internal = PROBLEM_CATALOG["INTERNAL_ERROR"]
    assert internal.status == 500
    assert internal.retryable is False
    assert PROBLEM_CATALOG["BLOB_SIZE_INVALID"].status == 422
    assert PROBLEM_CATALOG["BLOB_HASH_MISMATCH"].status == 422
    assert PROBLEM_CATALOG["AUTH_EXPIRED"].code == "AUTH_EXPIRED"


def test_unknown_code_falls_back_to_internal_error() -> None:
    payload = problem_payload("NOT_A_REAL_CODE", _CANONICAL_UUID)
    assert payload["code"] == "INTERNAL_ERROR"
    assert payload["status"] == 500
    assert payload["retryable"] is False
    assert payload["detail"] == PROBLEM_CATALOG["INTERNAL_ERROR"].detail
    assert "NOT_A_REAL_CODE" not in json.dumps(payload)
    assert resolve_problem("NOT_A_REAL_CODE") is PROBLEM_CATALOG["INTERNAL_ERROR"]


def test_problem_detail_forbids_unknown_fields_and_dumps_required_only() -> None:
    spec = PROBLEM_CATALOG["VALIDATION_FAILED"]
    model = ProblemDetail(
        type=spec.type,
        title=spec.title,
        status=spec.status,
        code=spec.code,
        detail=spec.detail,
        request_id=_CANONICAL_UUID,
        retryable=spec.retryable,
    )
    dumped = model.model_dump()
    assert set(dumped) == PROBLEM_BODY_FIELDS

    with pytest.raises(ValidationError):
        ProblemDetail(
            type=spec.type,
            title=spec.title,
            status=spec.status,
            code=spec.code,
            detail=spec.detail,
            request_id=_CANONICAL_UUID,
            retryable=spec.retryable,
            fields={"email": "x"},
        )


def test_success_and_error_share_server_request_id_and_ignore_client_header() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    router = APIRouter()

    @router.get("/test/unknown-code")
    def unknown(request: Request):
        return problem_response(request, "NOT_A_REAL_CODE")

    @router.get("/test/boom")
    def boom() -> None:
        raise RuntimeError("secret-exception-message")

    @router.get("/test/bearer")
    def bearer(token: SecretStr = Depends(get_access_bearer_token)) -> dict:
        return {"ok": True}

    app.include_router(router)
    client = TestClient(app)
    client_header = "client-supplied-id"

    health = client.get("/healthz", headers={"X-Request-ID": client_header})
    assert health.status_code == 200
    assert health.json() == {"status": "ok"}
    health_id = health.headers["x-request-id"]
    _assert_request_id(health_id)
    assert health_id != client_header

    missing_auth = client.get(
        "/test/bearer",
        headers={"X-Request-ID": client_header},
    )
    body = assert_problem(missing_auth, "AUTH_INVALID", www_authenticate=True)
    assert body["request_id"] != client_header
    assert client_header not in missing_auth.text
    assert body["code"] != "AUTH_EXPIRED"

    unavailable = client.post(
        "/v1/auth/login",
        json={"email": "alice@example.com", "password": "correct horse battery staple"},
        headers={"X-Request-ID": client_header},
    )
    unavailable_body = assert_problem(unavailable, "INTERNAL_UNAVAILABLE")
    assert unavailable_body["request_id"] != client_header
    assert unavailable_body["code"] == "INTERNAL_ERROR"
    assert unavailable_body["retryable"] is True

    unknown = client.get("/test/unknown-code", headers={"X-Request-ID": client_header})
    unknown_body = assert_problem(unknown, "INTERNAL_ERROR")
    assert unknown_body["request_id"] != client_header
    assert "NOT_A_REAL_CODE" not in unknown.text

    boom_client = TestClient(app, raise_server_exceptions=False)
    boom_response = boom_client.get("/test/boom", headers={"X-Request-ID": client_header})
    boom_body = assert_problem(boom_response, "INTERNAL_ERROR")
    assert "secret-exception-message" not in boom_response.text
    assert boom_body["detail"] == PROBLEM_CATALOG["INTERNAL_ERROR"].detail
    assert boom_body["request_id"] != client_header

    ids = {
        health_id,
        body["request_id"],
        unavailable_body["request_id"],
        unknown_body["request_id"],
        boom_body["request_id"],
    }
    assert len(ids) == 5


def test_validation_problem_is_fixed_and_redacts_input() -> None:
    class _Session:
        def close(self) -> None:
            return None

    app = create_app(
        settings=Settings(mode=AppMode.TEST),
        session_factory=lambda: _Session(),
    )
    client = TestClient(app)
    password = "short-secret"
    email = "not-an-email"
    response = client.post(
        "/v1/auth/login",
        json={"email": email, "password": password, "extra": "leak-me"},
    )
    body = assert_problem(response, "VALIDATION_FAILED", www_authenticate=False)
    serialized = json.dumps(body)
    assert email not in serialized
    assert password not in serialized
    assert "leak-me" not in serialized
    assert "msg" not in body
    assert "loc" not in serialized
    assert "ctx" not in serialized
    assert body["detail"] == PROBLEM_CATALOG["VALIDATION_FAILED"].detail


def test_registration_conflicts_and_directory_codes(client_factory) -> None:
    client, _factory = client_factory
    first = client.post("/v1/auth/register", json=_valid_payload())
    assert first.status_code == 201
    _assert_request_id(first.headers["x-request-id"])

    email_dup = _valid_payload()
    email_dup["handle"] = "bob"
    email_conflict = client.post("/v1/auth/register", json=email_dup)
    assert_problem(email_conflict, "EMAIL_UNAVAILABLE")
    assert "alice@example.com" not in email_conflict.text
    assert "bob" not in json.dumps(email_conflict.json())

    handle_dup = _valid_payload()
    handle_dup["email"] = "bob@example.com"
    handle_conflict = client.post("/v1/auth/register", json=handle_dup)
    assert_problem(handle_conflict, "HANDLE_UNAVAILABLE")
    assert "bob@example.com" not in handle_conflict.text

    key_dup = _valid_payload()
    key_dup["email"] = "carol@example.com"
    key_dup["handle"] = "carol"
    key_conflict = client.post("/v1/auth/register", json=key_dup)
    assert_problem(key_conflict, "KEY_BUNDLE_INVALID")

    token = first.json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}
    absent = client.get("/v1/directory/handles/nobody", headers=headers)
    assert_problem(absent, "HANDLE_NOT_FOUND")
    invalid = client.get("/v1/directory/handles/ab", headers=headers)
    invalid_body = assert_problem(invalid, "HANDLE_INVALID")
    assert "ab" not in invalid_body["detail"]
    assert "ab" not in invalid_body["title"]
    assert "ab" not in invalid_body["code"]

    login = client.post(
        "/v1/auth/login",
        json={"email": "nobody@example.com", "password": "wrong-password-here"},
    )
    login_body = assert_problem(login, "AUTH_INVALID", www_authenticate=False)
    assert login_body["code"] != "AUTH_EXPIRED"
    assert "nobody@example.com" not in login.text
    assert "wrong-password-here" not in login.text

    refresh = client.post("/v1/auth/refresh", json={"refresh_token": "pm_rt_" + "A" * 43})
    assert_problem(refresh, "AUTH_INVALID", www_authenticate=False)

    logout_missing = client.post("/v1/auth/logout")
    assert_problem(logout_missing, "AUTH_INVALID", www_authenticate=True)

    logout_unknown = client.post(
        "/v1/auth/logout",
        headers={"Authorization": "Bearer " + "pm_at_" + "A" * 43},
    )
    assert logout_unknown.status_code == 204
    _assert_request_id(logout_unknown.headers["x-request-id"])
    assert logout_unknown.content == b""


def test_unknown_route_and_wrong_method_use_problem_contract() -> None:
    from fastapi import HTTPException

    app = create_app(settings=Settings(mode=AppMode.TEST))

    @app.get("/test/forbidden")
    def forbidden() -> None:
        raise HTTPException(status_code=403, detail="secret-framework-detail")

    client = TestClient(app)
    missing = client.get("/definitely-not-a-route", headers={"X-Request-ID": "client-id"})
    body = assert_problem(missing, "ROUTE_NOT_FOUND")
    serialized = json.dumps(body)
    assert "Not Found" not in serialized
    assert "secret-framework-detail" not in missing.text
    assert body["request_id"] != "client-id"
    assert body["retryable"] is False

    wrong = client.post("/healthz")
    wrong_body = assert_problem(wrong, "METHOD_NOT_ALLOWED")
    assert "Method Not Allowed" not in json.dumps(wrong_body)
    assert "allow" in wrong.headers
    assert "GET" in wrong.headers["allow"].upper()
    assert wrong_body["retryable"] is False

    other = client.get("/test/forbidden")
    other_body = assert_problem(other, "INTERNAL_ERROR")
    assert "secret-framework-detail" not in other.text
    assert "Forbidden" not in json.dumps(other_body)
    assert other_body["status"] == 500
