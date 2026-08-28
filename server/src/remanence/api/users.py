"""Authenticated current-account and handle-change endpoints."""

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from remanence.api.auth_schemas import (
    ActiveKeyBundleResponse,
    HandleChangeRequest,
    HandleChangeResponse,
    MeResponse,
)
from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal, get_db_session
from remanence.api.problems import problem_response
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

router = APIRouter()


@router.patch("/v1/me/handle", response_model=HandleChangeResponse)
def change_handle(
    payload: HandleChangeRequest,
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session),
) -> HandleChangeResponse | JSONResponse:
    try:
        user = session.scalar(
            select(User).where(User.id == principal.user_id).with_for_update()
        )
        if user is None:
            return problem_response(request, "INTERNAL_ERROR")
        user.handle_normalized = payload.handle
        user.handle_display = payload.handle
        session.flush()
        session.commit()
    except IntegrityError as exc:
        session.rollback()
        if "uq_users_handle_normalized" not in str(exc.orig):
            raise
        return problem_response(request, "HANDLE_UNAVAILABLE")
    return HandleChangeResponse(user_id=user.id, handle=user.handle_normalized)


@router.get("/v1/me", response_model=MeResponse)
def me(
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session=Depends(get_db_session),
) -> MeResponse | JSONResponse:
    user = session.get(User, principal.user_id)
    if user is None:
        return problem_response(request, "INTERNAL_ERROR")
    key_bundle = session.scalar(
        select(UserKeyBundle).where(
            UserKeyBundle.user_id == principal.user_id,
            UserKeyBundle.status == KeyBundleStatus.ACTIVE,
        )
    )
    if key_bundle is None:
        return problem_response(request, "INTERNAL_ERROR")
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
