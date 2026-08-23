"""Authenticated current-account endpoint."""

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from postmark.api.auth_schemas import (
    ActiveKeyBundleResponse,
    MeResponse,
    ProblemDetail,
)
from postmark.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal, get_db_session
from postmark.users.key_models import KeyBundleStatus, UserKeyBundle
from postmark.users.models import User

router = APIRouter()


def _account_state_invalid_response() -> JSONResponse:
    problem = ProblemDetail(
        type="https://postmark.invalid/problems/account-state-invalid",
        title="Account state invalid",
        status=409,
        code="ACCOUNT_STATE_INVALID",
    )
    return JSONResponse(
        status_code=409,
        content=problem.model_dump(),
        media_type="application/problem+json",
    )


@router.get("/v1/me", response_model=MeResponse)
def me(
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session=Depends(get_db_session),
) -> MeResponse:
    user = session.get(User, principal.user_id)
    if user is None:
        return _account_state_invalid_response()
    key_bundle = session.scalar(
        select(UserKeyBundle).where(
            UserKeyBundle.user_id == principal.user_id,
            UserKeyBundle.status == KeyBundleStatus.ACTIVE,
        )
    )
    if key_bundle is None:
        return _account_state_invalid_response()
    return MeResponse(
        user_id=user.id,
        email=user.email_normalized,
        handle=user.handle_normalized,
        created_at=user.created_at,
        updated_at=user.updated_at,
        active_key_bundle=ActiveKeyBundleResponse(
            key_bundle_id=key_bundle.id,
            suite=key_bundle.suite,
            protocol_version=key_bundle.protocol_version,
            status=key_bundle.status.value,
        ),
    )