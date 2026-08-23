"""Registration endpoint."""

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import SecretStr
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from postmark.api.auth_schemas import (
    ActiveKeyBundleResponse,
    LoginRequest,
    LoginResponse,
    ProblemDetail,
    RefreshRequest,
    RefreshResponse,
    RegistrationRequest,
    RegistrationResponse,
    RegistrationUserResponse,
    registration_validation_problem,
)
from postmark.api.dependencies import DatabaseUnavailableError, get_access_bearer_token, get_db_session
from postmark.auth.login import LoginService, LoginStatus
from postmark.auth.logout import LogoutService
from postmark.auth.passwords import PasswordService
from postmark.auth.registration import RegistrationService
from postmark.auth.session_repository import AuthSessionRepository
from postmark.auth.session_rotation import (
    RefreshRotationStatus,
    SessionRotationService,
)
from postmark.users.key_bundle_validation import PublicKeyBundleValidationError

router = APIRouter()


def _account_conflict_problem() -> ProblemDetail:
    return ProblemDetail(
        type="https://postmark.invalid/problems/account-conflict",
        title="Account conflict",
        status=409,
        code="ACCOUNT_CONFLICT",
    )


def _conflict_response() -> JSONResponse:
    return JSONResponse(
        status_code=409,
        content=_account_conflict_problem().model_dump(),
        media_type="application/problem+json",
    )


def _invalid_response() -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content=registration_validation_problem().model_dump(),
        media_type="application/problem+json",
    )


def _service_unavailable_problem() -> ProblemDetail:
    return ProblemDetail(
        type="https://postmark.invalid/problems/service-unavailable",
        title="Service unavailable",
        status=503,
        code="SERVICE_UNAVAILABLE",
    )


def register_database_unavailable_handler(request: object, exc: DatabaseUnavailableError) -> JSONResponse:
    return JSONResponse(
        status_code=503,
        content=_service_unavailable_problem().model_dump(),
        media_type="application/problem+json",
    )


def register_validation_error_handler(request: object, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content=registration_validation_problem().model_dump(),
        media_type="application/problem+json",
    )


def _invalid_credentials_problem() -> ProblemDetail:
    return ProblemDetail(
        type="https://postmark.invalid/problems/invalid-credentials",
        title="Invalid credentials",
        status=401,
        code="INVALID_CREDENTIALS",
    )


def _invalid_credentials_response() -> JSONResponse:
    return JSONResponse(
        status_code=401,
        content=_invalid_credentials_problem().model_dump(),
        media_type="application/problem+json",
    )


def _invalid_refresh_problem() -> ProblemDetail:
    return ProblemDetail(
        type="https://postmark.invalid/problems/invalid-refresh-token",
        title="Invalid refresh token",
        status=401,
        code="INVALID_REFRESH_TOKEN",
    )


def _session_replayed_problem() -> ProblemDetail:
    return ProblemDetail(
        type="https://postmark.invalid/problems/session-replayed",
        title="Session replayed",
        status=401,
        code="SESSION_REPLAYED",
    )


def _problem_response(status: int, problem: ProblemDetail) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content=problem.model_dump(),
        media_type="application/problem+json",
    )


@router.post("/v1/auth/logout", status_code=204)
def logout(
    token: SecretStr = Depends(get_access_bearer_token),
    session: Session = Depends(get_db_session),
) -> Response:
    with session.begin():
        LogoutService(AuthSessionRepository(session)).logout(
            token.get_secret_value(), datetime.now(timezone.utc)
        )
    return Response(status_code=204)


@router.post(
    "/v1/auth/refresh",
    response_model=RefreshResponse,
    status_code=200,
)
def refresh(
    payload: RefreshRequest,
    session: Session = Depends(get_db_session),
) -> RefreshResponse:
    with session.begin():
        result = SessionRotationService(AuthSessionRepository(session)).rotate(
            payload.refresh_token.get_secret_value(),
            datetime.now(timezone.utc),
        )
    if result.status is RefreshRotationStatus.INVALID:
        return _problem_response(401, _invalid_refresh_problem())
    if result.status is RefreshRotationStatus.REPLAYED:
        return _problem_response(401, _session_replayed_problem())
    return RefreshResponse(
        session_id=result.session_id,
        access_token=result.access_token,
        refresh_token=result.refresh_token,
        access_expires_at=result.access_expires_at,
        refresh_expires_at=result.refresh_expires_at,
    )


@router.post(
    "/v1/auth/login",
    response_model=LoginResponse,
    status_code=200,
)
def login(
    payload: LoginRequest,
    session: Session = Depends(get_db_session),
) -> LoginResponse:
    with session.begin():
        result = LoginService(session, PasswordService()).login(
            email_normalized=payload.email,
            password=payload.password.get_secret_value(),
            now=datetime.now(timezone.utc),
        )
    if result.status is not LoginStatus.SUCCESS:
        return _invalid_credentials_response()
    return LoginResponse(
        user=RegistrationUserResponse(
            user_id=result.user_id,
            email=result.email,
            handle=result.handle,
            created_at=result.created_at,
        ),
        active_key_bundle=ActiveKeyBundleResponse(
            key_bundle_id=result.active_key_bundle_id,
            suite=result.suite,
            protocol_version=result.protocol_version,
            status="ACTIVE",
        ),
        session_id=result.session_id,
        access_token=result.access_token,
        access_expires_at=result.access_expires_at,
        refresh_token=result.refresh_token,
        refresh_expires_at=result.refresh_expires_at,
    )


@router.post(
    "/v1/auth/register",
    response_model=RegistrationResponse,
    status_code=201,
)
def register(
    payload: RegistrationRequest,
    session: Session = Depends(get_db_session),
) -> RegistrationResponse:
    try:
        with session.begin():
            result = RegistrationService(session, PasswordService()).register(
                email_normalized=payload.email,
                password=payload.password.get_secret_value(),
                handle_normalized=payload.handle,
                key_bundle_id=payload.key_bundle.key_bundle_id,
                suite=payload.key_bundle.suite,
                protocol_version=payload.key_bundle.protocol_version,
                encryption_public_keyset=payload.key_bundle.encryption_public_keyset,
                signing_public_keyset=payload.key_bundle.signing_public_keyset,
                now=datetime.now(timezone.utc),
            )
    except IntegrityError as exc:
        if (
            "uq_users_email_normalized" not in str(exc.orig)
            and "uq_users_handle_normalized" not in str(exc.orig)
            and "user_key_bundles_pkey" not in str(exc.orig)
        ):
            raise
        return _conflict_response()
    except (ValueError, PublicKeyBundleValidationError):
        return _invalid_response()
    return RegistrationResponse(
        user=RegistrationUserResponse(
            user_id=result.user_id,
            email=result.email,
            handle=result.handle,
            created_at=result.created_at,
        ),
        active_key_bundle_id=result.active_key_bundle_id,
        access_token=result.access_token,
        access_expires_at=result.access_expires_at,
        refresh_token=result.refresh_token,
        refresh_expires_at=result.refresh_expires_at,
    )