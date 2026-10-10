"""DEV/TEST Google authentication; never unwraps or regenerates private keys."""
import hashlib
import hmac
import secrets
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator
from sqlalchemy import select, delete, func, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from remanence.api.dependencies import get_db_session
from remanence.api.auth_schemas import RegistrationKeyBundleRequest, RegistrationUserResponse, ActiveKeyBundleResponse, LoginResponse
from remanence.api.recovery import b64, active_bundle
from remanence.auth.google_models import GoogleIdentity, GoogleFlow
from remanence.auth.google_verifier import verify_google_token
from remanence.auth.registration import RegistrationService
from remanence.auth.passwords import PasswordService
from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.session_rotation import ACCESS_TTL, REFRESH_TTL
from remanence.auth.tokens import generate_access_token, generate_refresh_token, hash_opaque_token
from remanence.users.models import User
from remanence.users.handles import normalize_handle


def enabled(request: Request, response: Response):
    if not request.app.state.settings.google_sign_in_enabled:
        raise HTTPException(404)
    response.headers["Cache-Control"] = "no-store"


router = APIRouter(dependencies=[Depends(enabled)])


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)


class Empty(Strict):
    pass


class GoogleProof(Strict):
    flow: uuid.UUID
    nonce: str = Field(min_length=43, max_length=43)
    id_token: SecretStr = Field(repr=False)

    @field_validator("id_token")
    @classmethod
    def token_bound(cls, value):
        if not 1 <= len(value.get_secret_value()) <= 16384:
            raise ValueError("invalid token")
        return value


class GoogleRegistration(Strict):
    flow: uuid.UUID
    registration_token: SecretStr = Field(repr=False)
    handle: str = Field(max_length=32)
    key_bundle: RegistrationKeyBundleRequest

    @field_validator("handle")
    @classmethod
    def handle_format(cls, value):
        return normalize_handle(value)


def fail(reason="GOOGLE_AUTH_REJECTED", status=400):
    return JSONResponse({"reason": reason}, status_code=status, headers={"Cache-Control": "no-store"})


def fresh(db, purpose, secret, subject=None, email=None):
    now = datetime.now(timezone.utc)
    # Serialize admission to keep the anonymous ceremony budget bounded.
    db.execute(text("SELECT pg_advisory_xact_lock(162010)"))
    db.execute(delete(GoogleFlow).where(GoogleFlow.expires_at < now - timedelta(days=1)))
    if db.scalar(select(func.count()).select_from(GoogleFlow).where(GoogleFlow.expires_at > now)) >= 512:
        raise ValueError("flow budget exceeded")
    row = GoogleFlow(purpose=purpose, secret_hash=hashlib.sha256(secret.encode()).digest(),
                     subject=subject, email=email, expires_at=now + timedelta(minutes=10))
    db.add(row); db.flush(); return row


def pending(db, flow, purpose, secret):
    row = db.scalar(select(GoogleFlow).where(GoogleFlow.id == flow).with_for_update())
    if (not row or row.purpose != purpose or row.consumed_at is not None
        or row.expires_at <= datetime.now(timezone.utc)
        or not hmac.compare_digest(row.secret_hash, hashlib.sha256(secret.encode()).digest())):
        raise ValueError("invalid flow")
    return row


def login_response(db, user):
    if user.disabled_at is not None: raise ValueError("disabled account")
    bundle = active_bundle(db, user.id)
    now = datetime.now(timezone.utc); access = generate_access_token(); refresh = generate_refresh_token()
    session = AuthSessionRepository(db).create(user_id=user.id, access_token_hash=hash_opaque_token(access),
        refresh_token_hash=hash_opaque_token(refresh), access_expires_at=now + ACCESS_TTL, refresh_expires_at=now + REFRESH_TTL)
    return LoginResponse(user=RegistrationUserResponse(user_id=user.id, email=user.email_normalized,
        handle=user.handle_normalized, created_at=user.created_at),
        active_key_bundle=ActiveKeyBundleResponse(key_bundle_id=bundle.id, suite=bundle.suite,
            protocol_version=bundle.protocol_version, status="ACTIVE"), session_id=session.id,
        access_token=access, refresh_token=refresh, access_expires_at=now + ACCESS_TTL,
        refresh_expires_at=now + REFRESH_TTL).model_dump(mode="json")


@router.post("/v1/auth/google/options")
def options(payload: Empty, request: Request, db: Session = Depends(get_db_session)):
    nonce = b64(secrets.token_bytes(32))
    try:
        row = fresh(db, "AUTH", nonce)
        result = {"flow": str(row.id), "nonce": nonce, "client_id": request.app.state.settings.google_client_ids[0]}
        db.commit(); return result
    except Exception:
        db.rollback(); return fail()


@router.post("/v1/auth/google/verify")
def verify(payload: GoogleProof, request: Request, db: Session = Depends(get_db_session)):
    burned = False
    try:
        row = pending(db, payload.flow, "AUTH", payload.nonce)
        row.consumed_at = datetime.now(timezone.utc); db.flush(); burned = True
        with db.begin_nested():
            proof = verify_google_token(payload.id_token.get_secret_value(), payload.nonce, request.app.state.settings)
            identity = db.get(GoogleIdentity, proof["subject"])
            if identity:
                user = db.scalar(select(User).where(User.id == identity.user_id).with_for_update())
                result = {"status": "SIGNED_IN", "login": login_response(db, user)}
            elif db.scalar(select(User.id).where(User.email_normalized == proof["email"])):
                # No implicit linking, including non-Gmail addresses controlled elsewhere.
                result = fail("EXISTING_ACCOUNT_REQUIRES_LINK", 409)
            else:
                token = b64(secrets.token_bytes(32))
                registration = fresh(db, "REGISTER", token, proof["subject"], proof["email"])
                result = {"status": "NEEDS_HANDLE", "flow": str(registration.id), "registration_token": token}
        db.commit(); return result
    except Exception:
        if burned: db.commit()
        else: db.rollback()
        return fail()


@router.post("/v1/auth/google/register")
def register(payload: GoogleRegistration, db: Session = Depends(get_db_session)):
    try:
        row = pending(db, payload.flow, "REGISTER", payload.registration_token.get_secret_value())
        if db.get(GoogleIdentity, row.subject): raise ValueError("already registered")
        bundle = payload.key_bundle
        result = RegistrationService(db, PasswordService()).register(email_normalized=row.email, password=None,
            handle_normalized=payload.handle, key_bundle_id=bundle.key_bundle_id, suite=bundle.suite,
            protocol_version=bundle.protocol_version, encryption_public_keyset=bundle.encryption_public_keyset,
            signing_public_keyset=bundle.signing_public_keyset, now=datetime.now(timezone.utc))
        db.add(GoogleIdentity(subject=row.subject, user_id=result.user_id))
        row.consumed_at = datetime.now(timezone.utc)
        # Reuse the already-issued registration session; do not mint a second one.
        user = db.get(User, result.user_id)
        out = {"status": "SIGNED_IN", "login": LoginResponse(
            user=RegistrationUserResponse(user_id=user.id, email=user.email_normalized, handle=user.handle_normalized, created_at=user.created_at),
            active_key_bundle=ActiveKeyBundleResponse(key_bundle_id=result.active_key_bundle_id, suite=bundle.suite, protocol_version=bundle.protocol_version, status="ACTIVE"),
            session_id=result.session_id, access_token=result.access_token, refresh_token=result.refresh_token,
            access_expires_at=result.access_expires_at, refresh_expires_at=result.refresh_expires_at).model_dump(mode="json")}
        db.commit(); return out
    except IntegrityError:
        # Atomic rollback retains the registration ticket for handle correction.
        db.rollback(); return fail("REGISTRATION_CONFLICT", 409)
    except Exception:
        db.rollback(); return fail()
