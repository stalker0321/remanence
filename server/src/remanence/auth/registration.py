"""Atomic registration domain service."""

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from sqlalchemy.orm import Session

from remanence.auth.models import AuthCredential
from remanence.auth.passwords import PasswordService
from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.session_rotation import ACCESS_TTL, REFRESH_TTL
from remanence.auth.tokens import generate_access_token, generate_refresh_token, hash_opaque_token
from remanence.users.handles import normalize_handle
from remanence.users.key_bundle_validation import validate_public_key_bundle
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


@dataclass(frozen=True)
class RegistrationResult:
    user_id: uuid.UUID
    email: str
    handle: str
    created_at: datetime
    active_key_bundle_id: uuid.UUID
    session_id: uuid.UUID
    access_token: str = field(repr=False)
    refresh_token: str = field(repr=False)
    access_expires_at: datetime
    refresh_expires_at: datetime


class RegistrationService:
    def __init__(self, session: Session, password_service: PasswordService) -> None:
        self._session = session
        self._password_service = password_service

    def register(
        self,
        *,
        email_normalized: str,
        password: str,
        handle_normalized: str,
        key_bundle_id: uuid.UUID,
        suite: str,
        protocol_version: int,
        encryption_public_keyset: bytes,
        signing_public_keyset: bytes,
        now: datetime,
    ) -> RegistrationResult:
        if now.tzinfo is None or now.utcoffset() != timezone.utc.utcoffset(now):
            raise ValueError("invalid timestamp")
        if normalize_handle(handle_normalized) != handle_normalized:
            raise ValueError("invalid handle")
        if not email_normalized or len(email_normalized) > 320:
            raise ValueError("invalid email")
        if email_normalized != email_normalized.strip(" \t\n\r\f\v").casefold():
            raise ValueError("invalid email")

        validate_public_key_bundle(
            suite=suite,
            protocol_version=protocol_version,
            encryption_public_keyset=encryption_public_keyset,
            signing_public_keyset=signing_public_keyset,
        )

        user_id = uuid.uuid4()
        user = User(
            id=user_id,
            email_normalized=email_normalized,
            handle_normalized=handle_normalized,
            handle_display=handle_normalized,
        )
        self._session.add(user)
        self._session.flush()

        auth_credential = AuthCredential(
            user_id=user_id,
            password_hash=self._password_service.hash_password(password),
        )
        self._session.add(auth_credential)
        self._session.flush()

        key_bundle = UserKeyBundle(
            id=key_bundle_id,
            user_id=user_id,
            encryption_public_keyset=encryption_public_keyset,
            signing_public_keyset=signing_public_keyset,
            suite=suite,
            protocol_version=protocol_version,
            status=KeyBundleStatus.ACTIVE,
        )
        self._session.add(key_bundle)
        self._session.flush()

        access_token = generate_access_token()
        refresh_token = generate_refresh_token()
        access_expires_at = now + ACCESS_TTL
        refresh_expires_at = now + REFRESH_TTL
        session_repository = AuthSessionRepository(self._session)
        auth_session = session_repository.create(
            user_id=user_id,
            access_token_hash=hash_opaque_token(access_token),
            refresh_token_hash=hash_opaque_token(refresh_token),
            access_expires_at=access_expires_at,
            refresh_expires_at=refresh_expires_at,
        )
        return RegistrationResult(
            user_id=user_id,
            email=email_normalized,
            handle=handle_normalized,
            created_at=user.created_at,
            active_key_bundle_id=key_bundle_id,
            session_id=auth_session.id,
            access_token=access_token,
            refresh_token=refresh_token,
            access_expires_at=access_expires_at,
            refresh_expires_at=refresh_expires_at,
        )