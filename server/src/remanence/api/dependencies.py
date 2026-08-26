"""FastAPI shared dependencies and bearer authentication."""

import base64
import re
import uuid
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import Depends, Header, Request
from pydantic import SecretStr
from sqlalchemy.orm import Session

from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.tokens import hash_opaque_token
from remanence.users.models import User

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


def _validate_access_token(token: str) -> None:
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


def get_access_bearer_token(
    authorization: str | None = Header(default=None, alias="Authorization"),
) -> SecretStr:
    if authorization is None:
        raise AuthenticationRequiredError()
    try:
        scheme, token = authorization.split(" ", 1)
    except ValueError:
        raise AuthenticationRequiredError() from None
    if scheme != "Bearer" or " " in token or not token:
        raise AuthenticationRequiredError()
    try:
        token.encode("ascii")
    except UnicodeEncodeError:
        raise AuthenticationRequiredError()
    _validate_access_token(token)
    return SecretStr(token)


def get_authenticated_principal(
    token: SecretStr = Depends(get_access_bearer_token),
    session: Session = Depends(get_db_session),
) -> AuthenticatedPrincipal:
    access_token = token.get_secret_value()
    token_hash = hash_opaque_token(access_token)
    auth_session = AuthSessionRepository(session).find_by_access_token_hash(
        token_hash, datetime.now(timezone.utc)
    )
    if auth_session is None:
        raise AuthenticationRequiredError()
    user = session.get(User, auth_session.user_id)
    if user is None or user.disabled_at is not None:
        raise AuthenticationRequiredError()
    return AuthenticatedPrincipal(user_id=user.id, session_id=auth_session.id)