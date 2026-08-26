"""Handle-directory lookup and public key-bundle endpoints."""

import hashlib
import uuid

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal, get_db_session
from remanence.api.auth_schemas import ProblemDetail
from remanence.api.directory_schemas import (
    DirectoryKeyBundleResponse,
    DirectoryLookupResponse,
    DirectoryUserSummary,
    KeyBundleByIdResponse,
    encode_base64url,
)
from remanence.users.handles import normalize_handle
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

router = APIRouter()

_BUNDLE_PUBLIC_FIELDS = (
    "key_bundle_id",
    "user_id",
    "suite",
    "protocol_version",
    "encryption_public_keyset",
    "signing_public_keyset",
    "status",
    "created_at",
)


def _handle_not_found_response() -> JSONResponse:
    return _problem_response(
        problem_type="https://remanence.invalid/problems/handle-not-found",
        title="Handle not found",
        status=404,
        code="HANDLE_NOT_FOUND",
    )


def _key_bundle_not_found_response() -> JSONResponse:
    return _problem_response(
        problem_type="https://remanence.invalid/problems/key-bundle-not-found",
        title="Key bundle not found",
        status=404,
        code="KEY_BUNDLE_NOT_FOUND",
    )


def _problem_response(problem_type: str, title: str, status: int, code: str) -> JSONResponse:
    problem = ProblemDetail(type=problem_type, title=title, status=status, code=code)
    return JSONResponse(
        status_code=status,
        content=problem.model_dump(),
        media_type="application/problem+json",
    )


def _directory_version(bundle: UserKeyBundle) -> str:
    digest = hashlib.sha256()
    digest.update(str(bundle.id).encode("ascii"))
    digest.update(b"|")
    digest.update(str(int(bundle.created_at.timestamp())).encode("ascii"))
    return digest.hexdigest()[:32]


def _public_bundle_fields(bundle: UserKeyBundle) -> dict:
    return {
        "key_bundle_id": bundle.id,
        "user_id": bundle.user_id,
        "suite": bundle.suite,
        "protocol_version": bundle.protocol_version,
        "encryption_public_keyset": encode_base64url(bundle.encryption_public_keyset),
        "signing_public_keyset": encode_base64url(bundle.signing_public_keyset),
        "status": bundle.status.value,
        "created_at": bundle.created_at,
    }


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
        key_bundle=DirectoryKeyBundleResponse(**_public_bundle_fields(key_bundle)),
        directory_version=_directory_version(key_bundle),
    )
    return body


@router.get("/v1/directory/key-bundles/{key_bundle_id}", response_model=KeyBundleByIdResponse)
def get_key_bundle(
    key_bundle_id: str,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session),
) -> KeyBundleByIdResponse | JSONResponse:
    try:
        bundle_id = uuid.UUID(key_bundle_id)
    except ValueError:
        return _key_bundle_not_found_response()
    key_bundle = session.get(UserKeyBundle, bundle_id)
    if key_bundle is None:
        return _key_bundle_not_found_response()
    return KeyBundleByIdResponse(**_public_bundle_fields(key_bundle))
