"""T376-send-access-read-v1. No public grant or accounting mutation surface."""

from fastapi import APIRouter, Depends, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session
from starlette.datastructures import MutableHeaders
from starlette.exceptions import HTTPException

from remanence.api.dependencies import (
    AuthenticatedPrincipal, AuthenticationRequiredError, DatabaseUnavailableError,
    get_authenticated_principal, get_db_session,
)
from remanence.api.problems import problem_response
from remanence.send_access.policy import SendAccessMode
from remanence.send_access.query import read_access
from remanence.send_access.service import SendAccessError

PATH = "/v1/me/send-access"


class SendAccessRoute(APIRoute):
    def get_route_handler(self):
        original = super().get_route_handler()

        async def bounded(request: Request):
            try:
                return await original(request)
            except (DatabaseUnavailableError, SQLAlchemyError, SendAccessError):
                # Includes auth/DB dependency failures, before the endpoint runs.
                return problem_response(request, "SEND_ACCESS_UNAVAILABLE")
            except (AuthenticationRequiredError, RequestValidationError, HTTPException):
                raise  # Preserve the application's existing auth/validation contract.
            except Exception:
                return problem_response(request, "INTERNAL_ERROR")

        return bounded


class SendAccessNoStoreMiddleware:
    """Also covers dependency errors and the router's trailing-slash redirect."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope.get("path", "").rstrip("/") != PATH:
            return await self.app(scope, receive, send)

        async def private_send(message):
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                headers["Cache-Control"] = "private, no-store"
                if "etag" in headers:
                    del headers["etag"]
            await send(message)

        await self.app(scope, receive, private_send)


router = APIRouter(route_class=SendAccessRoute)


@router.get(PATH)
async def get_send_access(
    request: Request,
    principal: AuthenticatedPrincipal = Depends(get_authenticated_principal),
    session: Session = Depends(get_db_session, use_cache=False),
):
    # Capture once; neither parameters nor headers can override configured mode.
    mode = request.app.state.settings.send_access_mode
    if request.query_params:
        return problem_response(request, "VALIDATION_FAILED")
    # Reject any body without accumulating an unbounded request in memory.
    async for chunk in request.stream():
        if chunk:
            return problem_response(request, "VALIDATION_FAILED")
    if mode is SendAccessMode.OFF:
        return problem_response(request, "ROUTE_NOT_FOUND")
    return read_access(session, account_id=principal.user_id, mode=mode)
