"""Registration endpoint."""

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from pydantic import SecretStr
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from remanence.api.rate_limit import auth_limit, refresh_limit
from remanence.api.auth_schemas import (
    ActiveKeyBundleResponse,
    LoginRequest,
    LoginResponse,
    RefreshRequest,
    RefreshResponse,
    RegistrationRequest,
    RegistrationResponse,
    RegistrationUserResponse,
)
from remanence.api.dependencies import get_access_bearer_token, get_db_session
from remanence.api.problems import problem_response
from remanence.auth.login import LoginService, LoginStatus
from remanence.auth.logout import LogoutService
from remanence.auth.passwords import PasswordService
from remanence.auth.registration import RegistrationService
from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.session_rotation import (
    RefreshRotationStatus,
    SessionRotationService,
)
from remanence.users.key_bundle_validation import PublicKeyBundleValidationError
router = APIRouter()


class _NeedUpgrade(Exception):
    """Handleless account on an old client: roll back, then 426."""


def _registration_conflict_code(exc: IntegrityError) -> str | None:
    orig = str(getattr(exc, "orig", exc))
    if "uq_users_email_normalized" in orig:
        return "EMAIL_UNAVAILABLE"
    if "uq_users_handle_normalized" in orig:
        return "HANDLE_UNAVAILABLE"
    if "user_key_bundles_pkey" in orig or "pk_user_key_bundles" in orig:
        return "KEY_BUNDLE_INVALID"
    return None


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
    "/v1/auth/refresh", dependencies=[Depends(refresh_limit)],
    response_model=RefreshResponse,
    status_code=200,
)
def refresh(
    payload: RefreshRequest,
    request: Request,
    session: Session = Depends(get_db_session),
) -> RefreshResponse:
    with session.begin():
        result = SessionRotationService(AuthSessionRepository(session)).rotate(
            payload.refresh_token.get_secret_value(),
            datetime.now(timezone.utc),
        )
    if result.status is RefreshRotationStatus.INVALID:
        return problem_response(request, "AUTH_INVALID")
    if result.status is RefreshRotationStatus.REPLAYED:
        return problem_response(request, "SESSION_REPLAYED")
    return RefreshResponse(
        session_id=result.session_id,
        access_token=result.access_token,
        refresh_token=result.refresh_token,
        access_expires_at=result.access_expires_at,
        refresh_expires_at=result.refresh_expires_at,
    )


def password_auth_fence(request: Request) -> None:
    """Password login/registration closes once atomic registration is required.

    Product accounts are created through Google + passkey atomic registration;
    these legacy routes would bypass it (and recovery).
    """
    if request.app.state.settings.registration_atomic_required:
        raise HTTPException(status_code=404)


@router.post(
    "/v1/auth/login", dependencies=[Depends(auth_limit), Depends(password_auth_fence)],
    response_model=LoginResponse,
    status_code=200,
)
def login(
    payload: LoginRequest,
    request: Request,
    session: Session = Depends(get_db_session),
) -> LoginResponse:
    from remanence.pending.profiles import PROFILE_VERSION_2, client_profile_version

    capability = client_profile_version(request.headers)
    try:
        with session.begin():
            result = LoginService(session, PasswordService()).login(
                email_normalized=payload.email,
                password=payload.password.get_secret_value(),
                now=datetime.now(timezone.utc),
            )
            if (
                result.status is LoginStatus.SUCCESS
                and result.handle is None
                and capability < PROFILE_VERSION_2
            ):
                # Old client: roll the just-minted session back with the
                # transaction, then 426. No orphan session persists.
                raise _NeedUpgrade()
    except _NeedUpgrade:
        return problem_response(request, "CLIENT_UPGRADE_REQUIRED")
    if result.status is not LoginStatus.SUCCESS:
        return problem_response(request, "AUTH_INVALID")
    if result.handle is None:
        # Nullable-profile fence: old clients get 426, v2 clients get a
        # usable session with a null handle (same shape as Google v2).
        from remanence.pending.profiles import PROFILE_VERSION_2, client_profile_version

        if client_profile_version(request.headers) < PROFILE_VERSION_2:
            return problem_response(request, "CLIENT_UPGRADE_REQUIRED")
        return JSONResponse(
            status_code=200,
            content={
                "user": {
                    "user_id": str(result.user_id),
                    "email": result.email,
                    "handle": None,
                    "profile_version": 2,
                    "created_at": result.created_at.isoformat(),
                },
                "active_key_bundle": {
                    "key_bundle_id": str(result.active_key_bundle_id),
                    "suite": result.suite,
                    "protocol_version": result.protocol_version,
                    "status": "ACTIVE",
                },
                "session_id": str(result.session_id),
                "access_token": result.access_token,
                "access_expires_at": result.access_expires_at.isoformat(),
                "refresh_token": result.refresh_token,
                "refresh_expires_at": result.refresh_expires_at.isoformat(),
            },
        )
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
    "/v1/auth/register", dependencies=[Depends(auth_limit), Depends(password_auth_fence)],
    response_model=RegistrationResponse,
    status_code=201,
)
def register(
    payload: RegistrationRequest,
    request: Request,
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
        code = _registration_conflict_code(exc)
        if code is None:
            raise
        return problem_response(request, code)
    except PublicKeyBundleValidationError:
        return problem_response(request, "KEY_BUNDLE_INVALID")
    except ValueError:
        return problem_response(request, "VALIDATION_FAILED")
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
