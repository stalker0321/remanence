"""Handle-directory lookup endpoint."""

import hashlib

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from postmark.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal, get_db_session
from postmark.api.auth_schemas import ProblemDetail
from postmark.api.directory_schemas import (
    DirectoryKeyBundleResponse,
    DirectoryLookupResponse,
    DirectoryUserSummary,
    encode_base64url,
)
from postmark.users.handles import normalize_handle
from postmark.users.key_models import KeyBundleStatus, UserKeyBundle
from postmark.users.models import User

router = APIRouter()


def _handle_not_found_response() -> JSONResponse:
    problem = ProblemDetail(
        type="https://postmark.invalid/problems/handle-not-found",
        title="Handle not found",
        status=404,
        code="HANDLE_NOT_FOUND",
    )
    return JSONResponse(
        status_code=404,
        content=problem.model_dump(),
        media_type="application/problem+json",
    )


def _directory_version(bundle: UserKeyBundle) -> str:
    digest = hashlib.sha256()
    digest.update(str(bundle.id).encode("ascii"))
    digest.update(b"|")
    digest.update(str(int(bundle.created_at.timestamp())).encode("ascii"))
    return digest.hexdigest()[:32]


@router.get("/v1/directory/handles/{handle}", response_model=DirectoryLookupResponse)
def lookup_handle(
    handle: str,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session),
) -> DirectoryLookupResponse | JSONResponse:
    try:
        normalized = normalize_handle(handle)
    except ValueError:
        return _handle_not_found_response()
    user = session.scalar(select(User).where(User.handle_normalized == normalized))
    if user is None:
        return _handle_not_found_response()
    key_bundle = session.scalar(
        select(UserKeyBundle).where(
            UserKeyBundle.user_id == user.id,
            UserKeyBundle.status == KeyBundleStatus.ACTIVE,
        )
    )
    if key_bundle is None:
        return _handle_not_found_response()
    body = DirectoryLookupResponse(
        user=DirectoryUserSummary(user_id=user.id, handle=user.handle_normalized),
        key_bundle=DirectoryKeyBundleResponse(
            key_bundle_id=key_bundle.id,
            user_id=key_bundle.user_id,
            suite=key_bundle.suite,
            protocol_version=key_bundle.protocol_version,
            encryption_public_keyset=encode_base64url(key_bundle.encryption_public_keyset),
            signing_public_keyset=encode_base64url(key_bundle.signing_public_keyset),
            status=key_bundle.status.value,
            created_at=key_bundle.created_at,
        ),
        directory_version=_directory_version(key_bundle),
    )
    return body
