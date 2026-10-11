"""Contact controls do not read encrypted media or private key material."""
import uuid
from datetime import datetime, timedelta, timezone
from typing import Literal
from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import select, func
from sqlalchemy.orm import Session
from remanence.api.dependencies import AuthenticatedPrincipal, get_authenticated_principal, get_db_session
from remanence.api.problems import problem_response
from remanence.users.handles import normalize_handle
from remanence.users.models import User
from remanence.users.safety_models import UserBlock, SafetyReport, TermsAcceptance

from remanence.users.safety import TERMS_VERSION, owner_lock
router = APIRouter()


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)


class Contact(Strict):
    handle: str = Field(min_length=1, max_length=30)

    @field_validator("handle")
    @classmethod
    def normalized(cls, value):
        return normalize_handle(value)


class Report(Contact):
    reason: Literal["SPAM", "HARASSMENT", "INAPPROPRIATE_CONTENT", "OTHER"]


class Terms(Strict):
    version: Literal["2026-10-11"]


def contact(db, owner, handle):
    user = db.scalar(select(User).where(User.handle_normalized == handle, User.disabled_at.is_(None)))
    return user if user is not None and user.id != owner else None


@router.get("/v1/me/safety")
def safety_state(response: Response, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    response.headers["Cache-Control"] = "no-store"
    accepted = db.get(TermsAcceptance, principal.user_id)
    blocked = db.scalars(select(User.handle_normalized).join(UserBlock, UserBlock.blocked_id == User.id)
        .where(UserBlock.owner_id == principal.user_id).order_by(User.handle_normalized)).all()
    return {"terms_version": TERMS_VERSION, "terms_accepted": accepted is not None and accepted.version == TERMS_VERSION,
            "blocked_handles": blocked}


@router.post("/v1/me/terms")
def accept_terms(payload: Terms, response: Response, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    owner_lock(db, principal.user_id)
    row = db.get(TermsAcceptance, principal.user_id)
    if row is None: db.add(TermsAcceptance(user_id=principal.user_id, version=payload.version))
    elif row.version != payload.version:
        row.version = payload.version; row.accepted_at = datetime.now(timezone.utc)
    db.commit(); response.headers["Cache-Control"] = "no-store"
    return {"status": "ACCEPTED"}


@router.post("/v1/me/blocks")
def block_contact(payload: Contact, request: Request, response: Response, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    owner_lock(db, principal.user_id)
    user = contact(db, principal.user_id, payload.handle)
    if user is None: return problem_response(request, "USER_NOT_FOUND")
    if db.get(UserBlock, (principal.user_id, user.id)) is None:
        if db.scalar(select(func.count()).select_from(UserBlock).where(UserBlock.owner_id == principal.user_id)) >= 256:
            return problem_response(request, "RATE_LIMITED")
        db.add(UserBlock(owner_id=principal.user_id, blocked_id=user.id))
    db.commit(); response.headers["Cache-Control"] = "no-store"
    return {"status": "BLOCKED"}


@router.post("/v1/me/blocks/remove")
def unblock_contact(payload: Contact, response: Response, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    owner_lock(db, principal.user_id)
    user = db.scalar(select(User).where(User.handle_normalized == payload.handle))
    row = db.get(UserBlock, (principal.user_id, user.id)) if user else None
    if row: db.delete(row)
    db.commit(); response.headers["Cache-Control"] = "no-store"
    return {"status": "UNBLOCKED"}


@router.post("/v1/me/reports", status_code=201)
def report_contact(payload: Report, request: Request, response: Response, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal), db: Session = Depends(get_db_session)):
    owner_lock(db, principal.user_id)
    user = contact(db, principal.user_id, payload.handle)
    if user is None: return problem_response(request, "USER_NOT_FOUND")
    recent = datetime.now(timezone.utc) - timedelta(days=1)
    if db.scalar(select(func.count()).select_from(SafetyReport).where(SafetyReport.reporter_id == principal.user_id, SafetyReport.created_at >= recent)) >= 5:
        return problem_response(request, "RATE_LIMITED")
    db.add(SafetyReport(reporter_id=principal.user_id, reported_id=user.id, reason=payload.reason))
    db.commit(); response.headers["Cache-Control"] = "no-store"
    return {"status": "RECEIVED"}
