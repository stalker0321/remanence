"""FastAPI application factory."""

from collections.abc import Iterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException as FastAPIHTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy import Engine
from starlette.exceptions import HTTPException as StarletteHTTPException

from remanence.api.auth import router as auth_router
from remanence.api.dependencies import (
    AuthenticationRequiredError,
    DatabaseUnavailableError,
    StorageUnavailableError,
)
from remanence.api.directory import router as directory_router
from remanence.api.health import router as health_router
from remanence.api.capsules import router as capsules_router
from remanence.api.problems import RequestIdMiddleware, problem_response
from remanence.api.users import router as users_router
from remanence.db.session import build_engine, build_session_factory
from remanence.settings import AppMode, Settings
from remanence.storage import BlobStore, CiphertextStager, LocalFileBlobStore


def create_app(
    settings: Settings | None = None,
    session_factory=None,
    blob_store: BlobStore | None = None,
    ciphertext_stager: CiphertextStager | None = None,
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
        if blob_store is not None:
            app.state.blob_store = blob_store
        elif resolved.mode is not AppMode.TEST and resolved.blob_root is not None:
            app.state.blob_store = LocalFileBlobStore(resolved.blob_root)
        if ciphertext_stager is not None:
            app.state.ciphertext_stager = ciphertext_stager
        elif resolved.mode is not AppMode.TEST and resolved.blob_root is not None:
            app.state.ciphertext_stager = CiphertextStager(resolved.blob_root / ".staging")
        yield
        if engine is not None:
            engine.dispose()

    app = FastAPI(title="Remanence API", version="0.1.0", lifespan=lifespan)
    app.add_middleware(RequestIdMiddleware)
    app.state.settings = resolved
    if session_factory is not None:
        app.state.session_factory = session_factory
    if blob_store is not None:
        app.state.blob_store = blob_store
    if ciphertext_stager is not None:
        app.state.ciphertext_stager = ciphertext_stager

    @app.exception_handler(DatabaseUnavailableError)
    def _database_unavailable(request: Request, exc: DatabaseUnavailableError) -> JSONResponse:
        return problem_response(request, "INTERNAL_UNAVAILABLE")

    @app.exception_handler(AuthenticationRequiredError)
    def _authentication_required(request: Request, exc: AuthenticationRequiredError) -> JSONResponse:
        return problem_response(request, "AUTH_INVALID", www_authenticate=True)

    @app.exception_handler(StorageUnavailableError)
    def _storage_unavailable(request: Request, exc: StorageUnavailableError) -> JSONResponse:
        return problem_response(request, "INTERNAL_UNAVAILABLE")

    @app.exception_handler(RequestValidationError)
    def _validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        return problem_response(request, "VALIDATION_FAILED")

    def _http_exception(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        if exc.status_code == 404:
            return problem_response(request, "ROUTE_NOT_FOUND")
        if exc.status_code == 405:
            allow = None
            if exc.headers:
                allow = exc.headers.get("allow") or exc.headers.get("Allow")
            extra = {"Allow": allow} if allow else None
            return problem_response(request, "METHOD_NOT_ALLOWED", extra_headers=extra)
        return problem_response(request, "INTERNAL_ERROR")

    app.add_exception_handler(StarletteHTTPException, _http_exception)
    app.add_exception_handler(FastAPIHTTPException, _http_exception)

    @app.exception_handler(Exception)
    def _unhandled(request: Request, exc: Exception) -> JSONResponse:
        return problem_response(request, "INTERNAL_ERROR")

    app.include_router(health_router)
    app.include_router(auth_router)
    app.include_router(users_router)
    app.include_router(directory_router)
    app.include_router(capsules_router)
    return app
