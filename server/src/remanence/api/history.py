"""T368-history-v1: authenticated read-only sender metadata."""
import re
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Request
from fastapi.routing import APIRoute
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session
from starlette.datastructures import MutableHeaders
from starlette.responses import JSONResponse

from remanence.api.dependencies import AuthenticatedPrincipal, DatabaseUnavailableError, get_authenticated_principal, get_db_session
from remanence.api.problems import PROBLEM_MEDIA_TYPE, REQUEST_ID_HEADER, problem_payload, problem_response, request_id_of
from remanence.capsules.outgoing_query_service import HistoryQueryError, OutgoingQueryService

_PATHS = frozenset(("/v1/capsules/outgoing", "/v1/recipients/recent"))
_PRIVATE = "private, no-store"


def _unavailable(request: Request):
    # The legacy catalog aliases 503 to code INTERNAL_ERROR. These two NEW
    # endpoints keep the sealed T368-history-v1 code, not a global catalog edit.
    request_id = request_id_of(request)
    payload = problem_payload("INTERNAL_UNAVAILABLE", request_id)
    payload["code"] = "INTERNAL_UNAVAILABLE"
    return JSONResponse(payload, status_code=503, media_type=PROBLEM_MEDIA_TYPE,
                        headers={REQUEST_ID_HEADER: request_id, "Cache-Control": _PRIVATE})


class HistoryRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def history_handler(request: Request):
            try:
                return await handler(request)
            except (SQLAlchemyError, DatabaseUnavailableError):
                # Includes database failure while resolving bearer dependencies.
                return _unavailable(request)
        return history_handler


router = APIRouter(route_class=HistoryRoute)


class HistoryPrivacyMiddleware:
    """Cover dependency/auth/error responses too, only on the two new paths."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope.get("path", "").rstrip("/") not in _PATHS:
            return await self.app(scope, receive, send)

        started = False

        async def private_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
                MutableHeaders(scope=message)["Cache-Control"] = _PRIVATE
            await send(message)
        try:
            await self.app(scope, receive, private_send)
        except Exception:
            if started:
                raise
            # Unexpected dependency errors otherwise escape to Starlette's
            # outer ServerErrorMiddleware, outside our no-store header wrapper.
            response = problem_response(Request(scope), "INTERNAL_ERROR")
            await response(scope, receive, private_send)


def _query(request: Request, *, recent: bool) -> tuple[int, str | None]:
    pairs = request.query_params.multi_items()
    values = dict(pairs)
    if len(pairs) != len(values) or set(values) - ({"limit"} if recent else {"limit", "cursor"}):
        raise HistoryQueryError("invalid query")
    maximum = 20 if recent else 50
    raw = values.get("limit", "8" if recent else "20")
    if re.fullmatch(r"[1-9][0-9]?", raw) is None or int(raw) > maximum:
        raise HistoryQueryError("invalid limit")
    return int(raw), values.get("cursor")


def _read(request: Request, session: Session, principal: AuthenticatedPrincipal, *, recent: bool):
    try:
        limit, cursor = _query(request, recent=recent)
        service = OutgoingQueryService(session)
        if recent:
            body = service.recent(owner=principal.user_id, limit=limit)
        else:
            settings = request.app.state.settings
            custody = getattr(request.app.state, "pending_ctx", None)
            body = service.outgoing(
                owner=principal.user_id, now=datetime.now(timezone.utc), limit=limit, cursor=cursor,
                mode=settings.mode.value, custody=custody,
                pending_cancel_enabled=bool(settings.pending_protocol_enabled and settings.pending_sends_enabled and isinstance(custody, dict)),
            )
        return JSONResponse(body, headers={"Cache-Control": _PRIVATE})
    except HistoryQueryError:
        return problem_response(request, "VALIDATION_FAILED")
    except SQLAlchemyError:
        return _unavailable(request)
    except Exception:
        return problem_response(request, "INTERNAL_ERROR")


@router.get("/v1/capsules/outgoing")
def outgoing(request: Request, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
             session: Session = Depends(get_db_session, use_cache=False)):
    return _read(request, session, principal, recent=False)


@router.get("/v1/recipients/recent")
def recent(request: Request, principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
           session: Session = Depends(get_db_session, use_cache=False)):
    return _read(request, session, principal, recent=True)
