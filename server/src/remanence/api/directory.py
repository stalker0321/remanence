"""Handle-directory lookup and public key-bundle endpoints."""

import hashlib
import uuid

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal, get_db_session
from remanence.api.directory_schemas import (
    DirectoryKeyBundleResponse,
    DirectoryLookupResponse,
    DirectoryUserSummary,
    KeyBundleByIdResponse,
    encode_base64url,
)
from remanence.api.problems import problem_response
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
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session),
) -> DirectoryLookupResponse | JSONResponse:
    try:
        normalized = normalize_handle(handle)
    except ValueError:
        return problem_response(request, "HANDLE_INVALID")
    user = session.scalar(select(User).where(User.handle_normalized == normalized))
    if user is None:
        return problem_response(request, "HANDLE_NOT_FOUND")
    key_bundle = session.scalar(
        select(UserKeyBundle).where(
            UserKeyBundle.user_id == user.id,
            UserKeyBundle.status == KeyBundleStatus.ACTIVE,
        )
    )
    if key_bundle is None:
        return problem_response(request, "HANDLE_NOT_FOUND")
    body = DirectoryLookupResponse(
        user=DirectoryUserSummary(user_id=user.id, handle=user.handle_normalized),
        key_bundle=DirectoryKeyBundleResponse(**_public_bundle_fields(key_bundle)),
        directory_version=_directory_version(key_bundle),
    )
    return body


@router.get("/v1/directory/key-bundles/{key_bundle_id}", response_model=KeyBundleByIdResponse)
def get_key_bundle(
    key_bundle_id: str,
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session),
) -> KeyBundleByIdResponse | JSONResponse:
    try:
        bundle_id = uuid.UUID(key_bundle_id)
    except ValueError:
        return problem_response(request, "KEY_BUNDLE_NOT_FOUND")
    key_bundle = session.get(UserKeyBundle, bundle_id)
    if key_bundle is None:
        return problem_response(request, "KEY_BUNDLE_NOT_FOUND")
    return KeyBundleByIdResponse(**_public_bundle_fields(key_bundle))
