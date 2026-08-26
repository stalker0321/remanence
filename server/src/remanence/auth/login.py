"""Login domain service."""

import enum
import uuid
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.auth.models import AuthCredential
from remanence.auth.passwords import PasswordService
from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.session_rotation import ACCESS_TTL, REFRESH_TTL
from remanence.auth.tokens import generate_access_token, generate_refresh_token, hash_opaque_token
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


class LoginStatus(enum.Enum):
    SUCCESS = "SUCCESS"
    INVALID_CREDENTIALS = "INVALID_CREDENTIALS"


@dataclass(frozen=True)
class LoginResult:
    status: LoginStatus
    user_id: uuid.UUID | None = None
    email: str | None = None
    handle: str | None = None
    created_at: datetime | None = None
    session_id: uuid.UUID | None = None
    active_key_bundle_id: uuid.UUID | None = None
    suite: str | None = None
    protocol_version: int | None = None
    access_token: str | None = field(repr=False, default=None)
    refresh_token: str | None = field(repr=False, default=None)
    access_expires_at: datetime | None = None
    refresh_expires_at: datetime | None = None
    password_hash_replaced: bool = False

    @staticmethod
    def invalid() -> "LoginResult":
        return LoginResult(status=LoginStatus.INVALID_CREDENTIALS)


class LoginService:
    def __init__(self, session: Session, password_service: PasswordService) -> None:
        self._session = session
        self._password_service = password_service

    def login(self, email_normalized: str, password: str, now: datetime) -> LoginResult:
        user = self._session.scalar(
            select(User).where(User.email_normalized == email_normalized)
        )
        if user is None:
            return LoginResult.invalid()
        if user.disabled_at is not None:
            return LoginResult.invalid()
        credential = self._session.scalar(
            select(AuthCredential).where(AuthCredential.user_id == user.id)
        )
        if credential is None:
            return LoginResult.invalid()

        result = self._password_service.verify_password(credential.password_hash, password)
        if not result.verified:
            return LoginResult.invalid()

        rehashed = False
        if result.needs_rehash:
            credential.password_hash = self._password_service.hash_password(password)
            credential.password_changed_at = now
            rehashed = True

        key_bundle = self._session.scalar(
            select(UserKeyBundle).where(
                UserKeyBundle.user_id == user.id,
                UserKeyBundle.status == KeyBundleStatus.ACTIVE,
            )
        )
        if key_bundle is None:
            return LoginResult.invalid()

        access_token = generate_access_token()
        refresh_token = generate_refresh_token()
        access_expires_at = now + ACCESS_TTL
        refresh_expires_at = now + REFRESH_TTL
        auth_session = AuthSessionRepository(self._session).create(
            user_id=user.id,
            access_token_hash=hash_opaque_token(access_token),
            refresh_token_hash=hash_opaque_token(refresh_token),
            access_expires_at=access_expires_at,
            refresh_expires_at=refresh_expires_at,
        )
        return LoginResult(
            status=LoginStatus.SUCCESS,
            user_id=user.id,
            email=user.email_normalized,
            handle=user.handle_normalized,
            created_at=user.created_at,
            session_id=auth_session.id,
            active_key_bundle_id=key_bundle.id,
            suite=key_bundle.suite,
            protocol_version=key_bundle.protocol_version,
            access_token=access_token,
            refresh_token=refresh_token,
            access_expires_at=access_expires_at,
            refresh_expires_at=refresh_expires_at,
            password_hash_replaced=rehashed,
        )