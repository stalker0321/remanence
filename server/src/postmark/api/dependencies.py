"""FastAPI shared dependencies and bearer authentication."""

import base64
import re
import uuid
from collections.abc import Iterator
from datetime import datetime, timezone
from dataclasses import dataclass

from fastapi import Depends, Header, Request
from sqlalchemy.orm import Session

from postmark.auth.session_repository import AuthSessionRepository
from postmark.auth.tokens import hash_opaque_token
from postmark.users.models import User

_ACCESS_TOKEN_PREFIX = "pm_at_"
_BASE64URL_RE = re.compile(r"^[A-Za-z0-9_-]+$")


class DatabaseUnavailableError(RuntimeError):
    pass


class AuthenticationRequiredError(RuntimeError):
    pass


@dataclass(frozen=True)
class AuthenticatedPrincipal:
    user_id: uuid.UUID
    session_id: uuid.UUID


def get_db_session(request: Request) -> Iterator[Session]:
    factory = getattr(request.app.state, "session_factory", None)
    if factory is None:
        raise DatabaseUnavailableError()
    session = factory()
    try:
        yield session
    finally:
        session.close()


def _decode_access_token(token: str) -> bytes:
    if not token.startswith(_ACCESS_TOKEN_PREFIX):
        raise AuthenticationRequiredError()
    payload = token[len(_ACCESS_TOKEN_PREFIX):]
    if _BASE64URL_RE.fullmatch(payload) is None:
        raise AuthenticationRequiredError()
    if "=" in payload:
        raise AuthenticationRequiredError()
    padded = payload + "=" * (-len(payload) % 4)
    try:
        decoded = base64.b64decode(padded, altchars=b"-_", validate=True)
    except Exception:
        raise AuthenticationRequiredError() from None
    if len(decoded) != 32:
        raise AuthenticationRequiredError()
    return decoded


def get_authenticated_principal(
    authorization: str | None = Header(default=None, alias="Authorization"),
    session: Session = Depends(get_db_session),
) -> AuthenticatedPrincipal:
    if authorization is None:
        raise AuthenticationRequiredError()
    try:
        scheme, token = authorization.split(" ", 1)
    except ValueError:
        raise AuthenticationRequiredError() from None
    if scheme != "Bearer" or " " in token or not token:
        raise AuthenticationRequiredError()
    if not _ascii_only(token):
        raise AuthenticationRequiredError()
    _decode_access_token(token)

    token_hash = hash_opaque_token(token)
    auth_session = AuthSessionRepository(session).find_by_access_token_hash(
        token_hash, datetime.now(timezone.utc)
    )
    if auth_session is None:
        raise AuthenticationRequiredError()
    user = session.get(User, auth_session.user_id)
    if user is None or user.disabled_at is not None:
        raise AuthenticationRequiredError()
    return AuthenticatedPrincipal(user_id=user.id, session_id=auth_session.id)


def _ascii_only(value: str) -> bool:
    try:
        value.encode("ascii")
    except UnicodeEncodeError:
        return False
    return True