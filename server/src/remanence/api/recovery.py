"""ADR-011 experimental product recovery. Server verifies, never decrypts."""
import base64
import hashlib
import json
import re
import uuid
from datetime import datetime, timedelta, timezone
from typing import Literal

import tink
from tink import signature, tink_config
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.responses import Response
from pathlib import Path
from pydantic import BaseModel, ConfigDict, Field, model_validator, field_validator
from sqlalchemy import select, delete, func
from sqlalchemy.orm import Session
from webauthn import generate_registration_options, generate_authentication_options, verify_registration_response, verify_authentication_response, options_to_json
from webauthn.helpers.structs import AuthenticatorSelectionCriteria, ResidentKeyRequirement, UserVerificationRequirement, PublicKeyCredentialDescriptor

from remanence.api.dependencies import get_authenticated_principal, get_db_session, AuthenticatedPrincipal
from remanence.api.auth_schemas import LoginResponse, RegistrationUserResponse, ActiveKeyBundleResponse
from remanence.auth.tokens import generate_access_token, generate_refresh_token, hash_opaque_token
from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.session_rotation import ACCESS_TTL, REFRESH_TTL
from remanence.recovery.models import RecoveryCredential, RecoveryRecord, RecoveryChallenge
from remanence.users.models import User
from remanence.users.key_models import UserKeyBundle, KeyBundleStatus

VERSION = "REMANENCE-RECOVERY-V1"
PRF_INPUT = hashlib.sha256(b"remanence/recovery/prf-input/v1").digest()


def enabled(request: Request, response: Response):
    if not request.app.state.settings.recovery_enabled:
        raise HTTPException(404)
    response.headers["Cache-Control"] = "no-store"


router = APIRouter(dependencies=[Depends(enabled)])


@router.get("/recovery-test/")
@router.get("/recovery-test/{filename}")
def browser(filename: str = "index.html"):
    if filename not in {"index.html", "app.mjs", "crypto.mjs"}: raise HTTPException(404)
    content = (Path(__file__).parent.parent / "recovery" / "web" / filename).read_bytes()
    return Response(content, media_type="text/html" if filename.endswith("html") else "text/javascript", headers={
        "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
        "Content-Security-Policy": "default-src 'none'; script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
        "Cross-Origin-Opener-Policy": "same-origin"})


def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def unb64(value: str, low=1, high=32796) -> bytes:
    if not isinstance(value, str) or len(value) > 45000 or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError()
    data = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    if not low <= len(data) <= high or b64(data) != value:
        raise ValueError()
    return data


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True, strict=True)


class Empty(Strict):
    pass


class CredentialRequest(Strict):
    credential_id: str = Field(max_length=1366)


class VerifyRequest(Strict):
    flow: str = Field(max_length=36)
    response: dict = Field(repr=False)

    @model_validator(mode="after")
    def public_only(self):
        c = self.response
        if set(c) != {"id", "rawId", "type", "response", "clientExtensionResults"} or c["clientExtensionResults"] != {}:
            raise ValueError("public credential fields only")
        if c["type"] != "public-key" or c["id"] != c["rawId"]:
            raise ValueError("invalid credential")
        unb64(c["id"], high=1024)
        r = c["response"]
        if not isinstance(r, dict) or set(r) - {"clientDataJSON", "attestationObject", "transports", "authenticatorData", "signature", "userHandle"}:
            raise ValueError("invalid response")
        for key, value in r.items():
            if key == "transports":
                if not isinstance(value, list) or len(value) > 8 or any(not isinstance(t, str) or len(t) > 30 for t in value):
                    raise ValueError()
            elif not (key == "userHandle" and value is None):
                unb64(value, high=16384)
        data = json.loads(unb64(r["clientDataJSON"]))
        if data.get("crossOrigin", False) is not False or "topOrigin" in data:
            raise ValueError("cross-origin credentials refused")
        return self


class EncryptedRecord(Strict):
    version: Literal["REMANENCE-RECOVERY-V1"]
    generation: Literal[1]
    account_id: str = Field(max_length=36)
    bundle_id: str = Field(max_length=36)
    credential_id: str = Field(max_length=1366)
    salt: str = Field(max_length=43)
    package_sealed: str = Field(repr=False, max_length=43728)
    prf_ark: str = Field(repr=False, max_length=80)
    code_ark: str = Field(repr=False, max_length=80)
    signature: str = Field(repr=False, max_length=92)

    @field_validator("generation", mode="before")
    @classmethod
    def integer_generation(cls, value):
        if type(value) is not int: raise ValueError("integer generation required")
        return value

    @model_validator(mode="after")
    def valid_encoding(self):
        for value in (self.account_id, self.bundle_id):
            if str(uuid.UUID(value)) != value: raise ValueError()
        unb64(self.credential_id, high=1024); unb64(self.salt, 32, 32)
        unb64(self.package_sealed, 29, 32796); unb64(self.prf_ark, 60, 60)
        unb64(self.code_ark, 60, 60); unb64(self.signature, 69, 69)
        return self


class SaveRequest(Strict):
    flow: str = Field(max_length=36)
    record: EncryptedRecord = Field(repr=False)


def context(r: EncryptedRecord):
    return f"{VERSION}\n{r.account_id}\n{r.bundle_id}\n1\n{r.credential_id}\n{r.salt}"


def manifest(r: EncryptedRecord):
    return (context(r) + "\nmanifest").encode() + b"".join(hashlib.sha256(unb64(v)).digest() for v in (r.package_sealed, r.prf_ark, r.code_ark))


def issue(db, purpose, principal=None, credential=None, challenge=None):
    now = datetime.now(timezone.utc)
    db.execute(delete(RecoveryChallenge).where(RecoveryChallenge.expires_at < now - timedelta(days=1)))
    query = select(func.count()).select_from(RecoveryChallenge).where(RecoveryChallenge.expires_at > now)
    if db.scalar(query) >= 512: raise ValueError()
    if principal and db.scalar(query.where(RecoveryChallenge.user_id == principal.user_id)) >= 8: raise ValueError()
    row = RecoveryChallenge(purpose=purpose, user_id=principal.user_id if principal else None,
        session_id=principal.session_id if principal else None, credential_id=credential,
        challenge=challenge or __import__("secrets").token_bytes(32), expires_at=now + timedelta(minutes=5))
    db.add(row); db.flush(); return row


def consume(db, flow, purpose, principal=None):
    row = db.scalar(select(RecoveryChallenge).where(RecoveryChallenge.id == uuid.UUID(flow)).with_for_update())
    now = datetime.now(timezone.utc)
    if not row or row.purpose != purpose or row.consumed_at is not None or row.expires_at.replace(tzinfo=timezone.utc) <= now:
        raise ValueError()
    if (row.user_id, row.session_id) != ((principal.user_id, principal.session_id) if principal else (None, None)):
        raise ValueError()
    row.consumed_at = now
    return row


def active_bundle(db, user_id):
    bundle = db.scalar(select(UserKeyBundle).where(UserKeyBundle.user_id == user_id, UserKeyBundle.status == KeyBundleStatus.ACTIVE))
    if not bundle: raise ValueError()
    return bundle


def record_response(db, user_id):
    record = db.get(RecoveryRecord, user_id); bundle = active_bundle(db, user_id)
    if not record or record.bundle_id != bundle.id: raise ValueError()
    return {"record": record.payload, "encryption_public_keyset": b64(bundle.encryption_public_keyset), "signing_public_keyset": b64(bundle.signing_public_keyset)}


def rejected():
    return JSONResponse({"reason": "RECOVERY_REJECTED"}, status_code=400, headers={"Cache-Control": "no-store"})


def run(db, operation, ceremony=None):
    # Burn the validated challenge OUTSIDE the work savepoint. Failed proofs
    # cannot replay, and no partial credential/record/session write is committed.
    burned = False
    try:
        row = None
        if ceremony:
            row = consume(db, *ceremony); db.flush(); burned = True
        with db.begin_nested():
            result = operation(row) if ceremony else operation()
        db.commit(); return result
    except Exception:
        if burned: db.commit()
        else: db.rollback()
        return rejected()


def registration_options(request, db, principal):
    db.scalar(select(User).where(User.id == principal.user_id).with_for_update())
    if db.get(RecoveryRecord, principal.user_id): raise ValueError()
    # Bound incomplete attempts; failed/cancelled setup never becomes recoverable.
    pending = db.scalars(select(RecoveryCredential).where(RecoveryCredential.user_id == principal.user_id)).all()
    if len(pending) >= 4: raise ValueError()
    cfg = request.app.state.settings
    options = generate_registration_options(rp_id=cfg.recovery_rp_id, rp_name="Remanence",
        user_id=principal.user_id.bytes, user_name=f"remanence-{principal.user_id}",
        authenticator_selection=AuthenticatorSelectionCriteria(resident_key=ResidentKeyRequirement.REQUIRED, user_verification=UserVerificationRequirement.REQUIRED),
        exclude_credentials=[PublicKeyCredentialDescriptor(id=unb64(c.id)) for c in pending])
    row = issue(db, "REGISTER", principal, challenge=options.challenge)
    out = json.loads(options_to_json(options)); out["extensions"] = {"prf": {}}
    return {"flow": str(row.id), "options": out}


@router.post("/v1/recovery/register/options")
def register_options(payload: Empty, request: Request, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    return run(db, lambda: registration_options(request, db, principal))


@router.post("/v1/recovery/register/verify")
def register_verify(payload: VerifyRequest, request: Request, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    def operation(row):
        db.scalar(select(User).where(User.id == principal.user_id).with_for_update())
        if db.get(RecoveryRecord, principal.user_id): raise ValueError()
        result = verify_registration_response(credential=payload.response, expected_challenge=row.challenge,
            expected_rp_id=request.app.state.settings.recovery_rp_id, expected_origin=request.app.state.settings.recovery_origins,
            require_user_verification=True, require_user_presence=True)
        db.add(RecoveryCredential(id=b64(result.credential_id), user_id=principal.user_id, public_key=result.credential_public_key,
            counter=result.sign_count, created_at=datetime.now(timezone.utc)))
        return {"credential_id": b64(result.credential_id), "registered": True, "recoverable": False}
    return run(db, operation, (payload.flow, "REGISTER", principal))


def auth_options(request, db, principal=None, credential_id=None):
    cfg = request.app.state.settings; allowed = None
    if principal:
        c = db.get(RecoveryCredential, credential_id)
        if not c or c.user_id != principal.user_id: raise ValueError()
        allowed = [PublicKeyCredentialDescriptor(id=unb64(c.id))]
    options = generate_authentication_options(rp_id=cfg.recovery_rp_id, allow_credentials=allowed, user_verification=UserVerificationRequirement.REQUIRED)
    row = issue(db, "SETUP" if principal else "LOGIN", principal, credential_id, options.challenge)
    out = json.loads(options_to_json(options)); out["extensions"] = {"prf": {"eval": {"first": b64(PRF_INPUT)}}}
    return {"flow": str(row.id), "options": out}


@router.post("/v1/recovery/setup/options")
def setup_options(payload: CredentialRequest, request: Request, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    return run(db, lambda: auth_options(request, db, principal, payload.credential_id))


@router.post("/v1/recovery/login/options")
def login_options(payload: Empty, request: Request, db: Session = Depends(get_db_session)):
    return run(db, lambda: auth_options(request, db))


def authenticate(payload, request, db, row, principal=None):
    c = db.scalar(select(RecoveryCredential).where(RecoveryCredential.id == payload.response["id"]).with_for_update())
    if not c or (principal and (c.user_id != principal.user_id or row.credential_id != c.id)): raise ValueError()
    handle = payload.response["response"].get("userHandle")
    if (not principal and not handle) or (handle and unb64(handle) != c.user_id.bytes): raise ValueError()
    user = db.scalar(select(User).where(User.id == c.user_id).with_for_update())
    if not user or user.disabled_at is not None: raise ValueError()
    result = verify_authentication_response(credential=payload.response, expected_challenge=row.challenge,
        expected_rp_id=request.app.state.settings.recovery_rp_id, expected_origin=request.app.state.settings.recovery_origins,
        credential_public_key=c.public_key, credential_current_sign_count=c.counter, require_user_verification=True)
    c.counter = result.new_sign_count
    if principal:
        flow = issue(db, "UPLOAD", principal, c.id)
        return {"flow": str(flow.id), "credential_id": c.id, "authentication_verified": True}
    recovery = db.get(RecoveryRecord, user.id)
    if not recovery or recovery.credential_id != c.id: raise ValueError()
    recovered = record_response(db, user.id); bundle = active_bundle(db, user.id)
    now = datetime.now(timezone.utc); access = generate_access_token(); refresh = generate_refresh_token()
    auth = AuthSessionRepository(db).create(user_id=user.id, access_token_hash=hash_opaque_token(access), refresh_token_hash=hash_opaque_token(refresh),
        access_expires_at=now + ACCESS_TTL, refresh_expires_at=now + REFRESH_TTL)
    login = LoginResponse(user=RegistrationUserResponse(user_id=user.id, email=user.email_normalized, handle=user.handle_normalized, created_at=user.created_at),
        active_key_bundle=ActiveKeyBundleResponse(key_bundle_id=bundle.id, suite=bundle.suite, protocol_version=bundle.protocol_version, status="ACTIVE"),
        session_id=auth.id, access_token=access, refresh_token=refresh, access_expires_at=now + ACCESS_TTL, refresh_expires_at=now + REFRESH_TTL)
    return {**recovered, "login": login.model_dump(mode="json"), "authentication_verified": True}


@router.post("/v1/recovery/setup/verify")
def setup_verify(payload: VerifyRequest, request: Request, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    return run(db, lambda row: authenticate(payload, request, db, row, principal), (payload.flow, "SETUP", principal))


@router.post("/v1/recovery/login/verify")
def login_verify(payload: VerifyRequest, request: Request, db: Session = Depends(get_db_session)):
    return run(db, lambda row: authenticate(payload, request, db, row), (payload.flow, "LOGIN", None))


@router.post("/v1/recovery/record")
def save_record(payload: SaveRequest, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    def operation(row):
        r = payload.record
        db.scalar(select(User).where(User.id == principal.user_id).with_for_update())
        bundle = active_bundle(db, principal.user_id)
        if r.account_id != str(principal.user_id) or r.bundle_id != str(bundle.id) or r.credential_id != row.credential_id: raise ValueError()
        tink_config.register()
        handle = tink.KeysetHandle.read_no_secret(tink.BinaryKeysetReader(bundle.signing_public_keyset))
        handle.primitive(signature.PublicKeyVerify).verify(unb64(r.signature), manifest(r))
        data = r.model_dump(); existing = db.get(RecoveryRecord, principal.user_id)
        if existing:
            if existing.payload != data: raise ValueError()
        else:
            db.add(RecoveryRecord(user_id=principal.user_id, credential_id=r.credential_id, bundle_id=bundle.id, generation=1, payload=data))
        return {"stored": True, "generation": 1}
    return run(db, operation, (payload.flow, "UPLOAD", principal))


@router.get("/v1/recovery/record")
def get_record(principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    return run(db, lambda: record_response(db, principal.user_id))


@router.get("/v1/recovery/status")
def recovery_status(principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    def operation():
        record = db.get(RecoveryRecord, principal.user_id)
        if record:
            if record.bundle_id != active_bundle(db, principal.user_id).id: raise ValueError()
            return {"state": "STORED", "credential_id": record.credential_id}
        pending = db.scalar(select(RecoveryCredential).where(RecoveryCredential.user_id == principal.user_id)
            .order_by(RecoveryCredential.created_at.desc(), RecoveryCredential.id).limit(1))
        return {"state": "PENDING" if pending else "NOT_STARTED", "credential_id": pending.id if pending else None}
    return run(db, operation)
