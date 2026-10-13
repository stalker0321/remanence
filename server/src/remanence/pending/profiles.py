"""Nullable receive profiles + one-time handle setup (T185 M2).

- `canReceive = authenticated && cryptoReady`; `canSend` additionally
  requires a handle plus existing send policy/terms (callers enforce).
- Old clients meeting a handleless account get CLIENT_UPGRADE_REQUIRED
  before session publication — never null-decoder 500, never fake handle.
- Capability `X-Remanence-Profile-Version: 2` negotiates nullable user
  summaries. v1 shapes are untouched.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.auth.google_models import GoogleFlow, GoogleIdentity
from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.session_rotation import ACCESS_TTL, REFRESH_TTL
from remanence.auth.tokens import generate_access_token, generate_refresh_token, hash_opaque_token
from remanence.users.handles import normalize_handle
from remanence.users.key_bundle_validation import validate_public_key_bundle
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

PROFILE_VERSION_2 = 2
PROFILE_HEADER = "x-remanence-profile-version"


def client_profile_version(headers) -> int:
    """Negotiated profile capability from request headers. 0/absent means
    a legacy v1-only client."""
    try:
        return int(headers.get(PROFILE_HEADER, 0))
    except (ValueError, TypeError):
        return 0


def v2_user_dict(user: User) -> dict:
    """Exact typed v2 user summary, including created_at (T186 needs the
    full auth user, not a bare id/handle pair)."""
    return {
        "user_id": str(user.id),
        "email": user.email_normalized,
        "handle": user.handle_normalized,
        "profile_version": 2 if user.handle_normalized is None else 1,
        "created_at": user.created_at.isoformat() if user.created_at else None,
    }


class ProfileError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__("profile failed")
        self.code = code


@dataclass(frozen=True)
class ReceiveRegistrationResult:
    user_id: uuid.UUID
    email: str
    handle: None
    active_key_bundle_id: uuid.UUID
    session_id: uuid.UUID
    access_token: str = field(repr=False)
    refresh_token: str = field(repr=False)
    access_expires_at: datetime
    refresh_expires_at: datetime
    created_at: datetime


def summarize_user(user: User, *, client_profile_version: int | None) -> dict:
    """Version-aware user summary. v1 callers keep the non-null shape for
    handleful accounts; handleless accounts require v2 capability."""
    handle = user.handle_normalized
    if handle is None and (client_profile_version is None or client_profile_version < 2):
        raise ProfileError("CLIENT_UPGRADE_REQUIRED")
    return {
        "user_id": str(user.id),
        "email": user.email_normalized,
        "handle": handle,
        "profile_version": 2 if handle is None else 1,
    }


def register_receive(
    session: Session,
    *,
    ticket: GoogleFlow,
    key_bundle_id: uuid.UUID,
    suite: str,
    protocol_version: int,
    encryption_public_keyset: bytes,
    signing_public_keyset: bytes,
    now: datetime,
    pending_protocol_enabled: bool,
) -> ReceiveRegistrationResult:
    """Create a receive-only account from a VERIFIED Google REGISTER ticket.

    The ticket row must come from the route layer's pending() check (secret
    verified, purpose REGISTER, unconsumed, unexpired); this service
    re-validates all of that and consumes the ticket ATOMICALLY with user,
    bundle, session and GoogleIdentity creation. There is deliberately NO
    bare-email -> new-session path: an unauthenticated email string can
    never mint an account or session here.
    """
    if not pending_protocol_enabled:
        raise ProfileError("PROTOCOL_UNSUPPORTED")
    if now.tzinfo is None or now.utcoffset() != timezone.utc.utcoffset(now):
        raise ProfileError("VALIDATION_FAILED")
    if (
        ticket.purpose != "REGISTER"
        or ticket.consumed_at is not None
        or ticket.expires_at <= now
        or ticket.subject is None
        or ticket.email is None
    ):
        raise ProfileError("AUTH_INVALID")
    if session.get(GoogleIdentity, ticket.subject) is not None:
        # Already registered: caller resumes the existing identity flow.
        raise ProfileError("REGISTRATION_CONFLICT")
    if (
        session.scalar(select(User.id).where(User.email_normalized == ticket.email))
        is not None
    ):
        # No implicit linking, mirroring the v1 Google route.
        raise ProfileError("EXISTING_ACCOUNT_REQUIRES_LINK")
    validate_public_key_bundle(
        suite=suite,
        protocol_version=protocol_version,
        encryption_public_keyset=encryption_public_keyset,
        signing_public_keyset=signing_public_keyset,
    )
    user = User(
        id=uuid.uuid4(),
        email_normalized=ticket.email,
        handle_normalized=None,
        handle_display=None,
    )
    session.add(user)
    session.flush()
    bundle = UserKeyBundle(
        id=key_bundle_id,
        user_id=user.id,
        encryption_public_keyset=encryption_public_keyset,
        signing_public_keyset=signing_public_keyset,
        suite=suite,
        protocol_version=protocol_version,
        status=KeyBundleStatus.ACTIVE,
    )
    session.add(bundle)
    session.flush()
    access_token = generate_access_token()
    refresh_token = generate_refresh_token()
    access_expires_at = now + ACCESS_TTL
    refresh_expires_at = now + REFRESH_TTL
    auth_session = AuthSessionRepository(session).create(
        user_id=user.id,
        access_token_hash=hash_opaque_token(access_token),
        refresh_token_hash=hash_opaque_token(refresh_token),
        access_expires_at=access_expires_at,
        refresh_expires_at=refresh_expires_at,
    )
    session.add(GoogleIdentity(subject=ticket.subject, user_id=user.id))
    ticket.consumed_at = now
    session.flush()
    # Server-default created_at is DB-generated: refresh it inside the same
    # transaction so the typed response carries the persisted value
    # (v2_user_dict parity, Android required createdAt).
    session.refresh(user, attribute_names=["created_at"])
    return ReceiveRegistrationResult(
        user_id=user.id,
        email=ticket.email,
        handle=None,
        active_key_bundle_id=key_bundle_id,
        session_id=auth_session.id,
        access_token=access_token,
        refresh_token=refresh_token,
        access_expires_at=access_expires_at,
        refresh_expires_at=refresh_expires_at,
        created_at=user.created_at,
    )


def set_first_handle(session: Session, *, user_id: uuid.UUID, handle: str) -> User:
    """One-time NULL -> unique valid handle under a row lock, so two
    concurrent first assignments cannot both succeed: the loser blocks on
    the lock, then sees the winner's handle and gets HANDLE_IMMUTABLE
    (or success on identical replay). Legacy v1 mutation stays disabled
    in recovery-enabled mode."""
    try:
        normalized = normalize_handle(handle)
    except ValueError as exc:
        raise ProfileError("VALIDATION_FAILED") from exc
    user = session.scalar(select(User).where(User.id == user_id).with_for_update())
    if user is None:
        raise ProfileError("AUTH_INVALID")
    if user.handle_normalized is not None:
        if user.handle_normalized == normalized:
            return user
        raise ProfileError("HANDLE_IMMUTABLE")
    user.handle_normalized = normalized
    user.handle_display = handle.strip()
    session.flush()
    return user
