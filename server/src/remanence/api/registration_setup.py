"""Setup-only capabilities; permanent authentication is never inferred from them."""
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Path, Request
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session

from remanence.api.dependencies import (AuthenticationRequiredError, get_db_session,
    get_access_bearer_token, get_authenticated_principal)
from remanence.registration_setup.schemas import EpochRequest, SetupOptions, SetupVerify, FinalRequest
from remanence.registration_setup.service import AtomicRegistration, SetupFailure
from remanence.settings import AppMode


class RegistrationNoStoreMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        relevant = scope.get("path", "").startswith(("/v2/registration/", "/v1/auth/google/"))
        async def private_send(message):
            if relevant and message["type"] == "http.response.start":
                message["headers"] = [(k, v) for k, v in message.get("headers", []) if k.lower() != b"cache-control"]
                message["headers"].append((b"cache-control", b"no-store"))
            await send(message)
        await self.app(scope, receive, private_send)


def enabled(request: Request):
    cfg = request.app.state.settings
    if cfg.mode not in (AppMode.DEV, AppMode.TEST) or not cfg.google_sign_in_enabled or not cfg.recovery_enabled:
        raise HTTPException(404)


router = APIRouter(prefix="/v2/registration/reservations", dependencies=[Depends(enabled)])
ID = Path(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def service(request, db):
    return AtomicRegistration(db, request.app.state.settings,
        getattr(request.app.state, "registration_clock", lambda: datetime.now(timezone.utc)))


def execute(request, db, reservation_id, payload, action):
    engine = service(request, db)
    try:
        authorization = request.headers.get("Authorization", "")
        if action == "status" and authorization.startswith("Bearer pm_at_"):
            principal = get_authenticated_principal(token=get_access_bearer_token(authorization), session=db)
            result = engine.ordinary_status(uuid.UUID(reservation_id), principal.user_id)
        else:
            secret = authorization[7:] if authorization.startswith("Bearer ") else ""
            row = engine.authorized(uuid.UUID(reservation_id), secret, payload.epoch if payload else None)
            if action == "status": result = dict(reservation=engine.view(row))
            elif action == "credential_options": result = engine.credential_options(row)
            elif action == "setup_options": result = engine.setup_options(row, payload.credential_id)
            elif action in ("credential_verify", "setup_verify", "finalize"):
                result = getattr(engine, action)(row, payload)
            else: result = getattr(engine, action)(row)
        status, content = result if isinstance(result, tuple) else (200, result)
        db.commit()
        return JSONResponse(content, status_code=status, headers={"Cache-Control": "no-store"})
    except SetupFailure as error:
        if error.persist: db.commit()
        else: db.rollback()
        return JSONResponse({"reason": error.reason}, status_code=error.status, headers={"Cache-Control": "no-store"})
    except AuthenticationRequiredError:
        db.rollback()
        raise
    except Exception:
        db.rollback()
        # A lost COMMIT reply is UNKNOWN. Do not advertise rollback to callers.
        return JSONResponse({"reason": "SETUP_UNAVAILABLE"}, status_code=503, headers={"Cache-Control": "no-store"})


@router.get("/{reservation_id}")
def status(request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, None, "status")


@router.post("/{reservation_id}/credential/options")
def credential_options(payload: EpochRequest, request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, payload, "credential_options")


@router.post("/{reservation_id}/credential/verify")
def credential_verify(payload: SetupVerify, request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, payload, "credential_verify")


@router.post("/{reservation_id}/setup/options")
def setup_options(payload: SetupOptions, request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, payload, "setup_options")


@router.post("/{reservation_id}/setup/verify")
def setup_verify(payload: SetupVerify, request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, payload, "setup_verify")


@router.post("/{reservation_id}/finalize")
def finalize(payload: FinalRequest, request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, payload, "finalize")


@router.post("/{reservation_id}/leave")
def leave(payload: EpochRequest, request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, payload, "leave")


@router.post("/{reservation_id}/cancel")
def cancel(payload: EpochRequest, request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, payload, "cancel")


@router.post("/{reservation_id}/restart")
def restart(payload: EpochRequest, request: Request, reservation_id: str = ID, db: Session = Depends(get_db_session)):
    return execute(request, db, reservation_id, payload, "restart")
