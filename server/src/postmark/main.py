"""FastAPI application factory."""

from collections.abc import Iterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy import Engine

from postmark.api.auth import (
    register_database_unavailable_handler,
    register_validation_error_handler,
    router as auth_router,
)
from postmark.api.dependencies import (
    AuthenticationRequiredError,
    DatabaseUnavailableError,
)
from postmark.api.directory import router as directory_router
from postmark.api.health import router as health_router
from postmark.api.users import router as users_router
from postmark.db.session import build_engine, build_session_factory
from postmark.settings import AppMode, Settings


def _authentication_required_problem() -> dict:
    return {
        "type": "https://postmark.invalid/problems/authentication-required",
        "title": "Authentication required",
        "status": 401,
        "code": "AUTHENTICATION_REQUIRED",
    }


def create_app(
    settings: Settings | None = None,
    session_factory=None,
) -> FastAPI:
    resolved = Settings() if settings is None else settings
    engine: Engine | None = None

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> Iterator[None]:
        nonlocal engine
        if session_factory is not None:
            app.state.session_factory = session_factory
        elif resolved.mode is not AppMode.TEST:
            engine = build_engine(resolved)
            app.state.session_factory = build_session_factory(engine)
        yield
        if engine is not None:
            engine.dispose()

    app = FastAPI(title="Postmark API", version="0.1.0", lifespan=lifespan)
    app.state.settings = resolved
    if session_factory is not None:
        app.state.session_factory = session_factory

    @app.exception_handler(DatabaseUnavailableError)
    def _database_unavailable(request: Request, exc: DatabaseUnavailableError) -> JSONResponse:
        return register_database_unavailable_handler(request, exc)

    @app.exception_handler(AuthenticationRequiredError)
    def _authentication_required(request: Request, exc: AuthenticationRequiredError) -> JSONResponse:
        return JSONResponse(
            status_code=401,
            content=_authentication_required_problem(),
            media_type="application/problem+json",
            headers={"WWW-Authenticate": "Bearer"},
        )

    @app.exception_handler(RequestValidationError)
    def _validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        return register_validation_error_handler(request, exc)

    app.include_router(health_router)
    app.include_router(auth_router)
    app.include_router(users_router)
    app.include_router(directory_router)
    return app