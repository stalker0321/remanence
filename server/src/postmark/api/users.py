"""Authenticated current-account and handle-change endpoints."""

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from postmark.api.auth_schemas import (
    ActiveKeyBundleResponse,
    HandleChangeRequest,
    HandleChangeResponse,
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


def _handle_conflict_response() -> JSONResponse:
    problem = ProblemDetail(
        type="https://postmark.invalid/problems/handle-conflict",
        title="Handle conflict",
        status=409,
        code="HANDLE_CONFLICT",
    )
    return JSONResponse(
        status_code=409,
        content=problem.model_dump(),
        media_type="application/problem+json",
    )


@router.patch("/v1/me/handle", response_model=HandleChangeResponse)
def change_handle(
    payload: HandleChangeRequest,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session),
) -> HandleChangeResponse:
    try:
        user = session.scalar(
            select(User).where(User.id == principal.user_id).with_for_update()
        )
        if user is None:
            return _account_state_invalid_response()
        user.handle_normalized = payload.handle
        user.handle_display = payload.handle
        session.flush()
        session.commit()
    except IntegrityError as exc:
        session.rollback()
        if "uq_users_handle_normalized" not in str(exc.orig):
            raise
        return _handle_conflict_response()
    return HandleChangeResponse(user_id=user.id, handle=user.handle_normalized)


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