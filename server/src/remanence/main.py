"""FastAPI application factory."""

from collections.abc import Iterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException as FastAPIHTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy import Engine
from starlette.exceptions import HTTPException as StarletteHTTPException

from remanence.api.auth import router as auth_router
from remanence.api.google_auth import router as google_auth_router
from remanence.api.dependencies import (
    AuthenticationRequiredError,
    DatabaseUnavailableError,
    StorageUnavailableError,
)
from remanence.api.directory import router as directory_router
from remanence.api.health import router as health_router
from remanence.api.capsules import router as capsules_router
from remanence.api.music import router as music_router
from remanence.api.problems import RequestIdMiddleware, problem_response
from remanence.api.users import router as users_router
from remanence.api.recovery import router as recovery_router
from remanence.api.safety import router as safety_router
from remanence.api.legal import router as legal_router
from remanence.capsules.upload_reservations import (
    UploadReservationManager,
    build_upload_reservation_manager,
)
from remanence.db.session import build_engine, build_session_factory
from remanence.music.search.composition import build_music_search
from remanence.music.search.in_memory import InMemoryMusicSearch
from remanence.music.search.staging import PostgresStagingSearch
from remanence.settings import AppMode, MusicSearchBackend, Settings
from remanence.storage import BlobStore, CiphertextStager, LocalFileBlobStore
from remanence.telemetry.ingress import TelemetryIngress, install_access_filter, reserved_path
from remanence.telemetry.store import TelemetryStore


def create_app(
    settings: Settings | None = None,
    session_factory=None,
    blob_store: BlobStore | None = None,
    ciphertext_stager: CiphertextStager | None = None,
    upload_reservations: UploadReservationManager | None = None,
    music_search=None,
    pending_ctx: dict | None = None,
) -> FastAPI:
    resolved = Settings() if settings is None else settings
    if resolved.pending_protocol_enabled and pending_ctx is None:
        # M4 gated composition: the master flag alone never enables the
        # experimental router — explicit custody/keys/providers context is
        # required, otherwise startup fails closed. Flag OFF ignores ctx.
        raise ValueError("pending master flag requires explicit pending_ctx")
    if resolved.pending_protocol_enabled and pending_ctx is not None:
        # Single authoritative registry key: the deployment secret in
        # settings is the ONLY source. A ctx carrying a different HMAC
        # key would tag/verify addresses in a parallel namespace and
        # silently bypass historical reservations, so refuse at startup.
        import hmac as _hmac

        secret = resolved.pending_hmac_secret
        configured = secret.get_secret_value().encode() if secret else b""
        provided = bytes(pending_ctx.get("hmac_key") or b"")
        if not configured or not _hmac.compare_digest(configured, provided):
            raise ValueError("pending ctx hmac_key must equal pending_hmac_secret")
    if resolved.pending_protocol_enabled and pending_ctx is not None and resolved.mode in (
        AppMode.PROD,
        AppMode.BETA,
    ):
        # Fail closed in production-likes: dev vaults and mock/test
        # providers must never be injectable where real custody and
        # delivery are required.
        from remanence.pending.vault import ProductionKmsVault as _KmsVault

        vault = pending_ctx.get("vault")
        if not isinstance(vault, _KmsVault):
            raise ValueError("prod/beta pending requires KMS vault custody")
        for key in ("email_sender", "sms_provider"):
            provider = pending_ctx.get(key)
            if provider is not None and type(provider).__name__.startswith(("Mock", "Fake")):
                raise ValueError(f"prod/beta pending forbids test {key}")
    engine: Engine | None = None
    if music_search is None and resolved.music_search_backend is MusicSearchBackend.FULL_CATALOG:
        # Full catalog is independent of the account DB. Validate before startup;
        # never substitute fixtures/staging when a revision is missing or invalid.
        music_search = build_music_search(resolved)
    if resolved.mode is AppMode.PROD and isinstance(music_search, InMemoryMusicSearch):
        # R2: close the fixture bypass at the single choke point. Neither
        # build_fixture_music_search() with a forgotten mode nor a directly
        # constructed InMemoryMusicSearch may silently serve fixture tracks
        # in production. Unwired PROD (None) stays fail-closed 503 at the
        # endpoint; only a real port (e.g. MeilisearchMusicSearch) is allowed.
        raise ValueError("fixture music search must never be wired in PROD")
    if resolved.mode is AppMode.PROD and isinstance(
        music_search, PostgresStagingSearch
    ):
        # M-S1: staging is a DEV/TEST sample harness, never a PROD catalog.
        raise ValueError("staging music search must never be wired in PROD")
    if (
        resolved.mode is AppMode.PROD
        and resolved.music_search_backend is MusicSearchBackend.POSTGRES_STAGING
    ):
        # M-S1: explicit refusal even when unwired — PROD config must fail
        # at startup, never silently serve staging or fall back to 503.
        raise ValueError("staging music search backend is DEV/TEST only, never PROD")
    if (
        music_search is None
        and resolved.music_search_backend is MusicSearchBackend.POSTGRES_STAGING
        and session_factory is not None
    ):
        # M-S1 opt-in composition (explicit factory, e.g. tests): DEV/TEST
        # only, PROD already refused above. DISABLED (default) stays unwired
        # → endpoint fail-closed 503. Live DEV (create_app() with no factory
        # param) defers to lifespan below, which wires from the newly built
        # app.state.session_factory.
        music_search = build_music_search(resolved, session_factory)
    if (
        music_search is None
        and resolved.music_search_backend is MusicSearchBackend.POSTGRES_STAGING
        and session_factory is None
        and resolved.mode is AppMode.TEST
    ):
        # TEST without a factory is invalid config: fail closed now rather
        # than serving a silent 503. DEV defers to lifespan (live entrypoint
        # builds its factory there); PROD already refused above.
        build_music_search(resolved, None)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> Iterator[None]:
        nonlocal engine, music_search
        if session_factory is not None:
            app.state.session_factory = session_factory
        elif resolved.mode is not AppMode.TEST:
            engine = build_engine(resolved)
            app.state.session_factory = build_session_factory(engine)
        if (
            music_search is None
            and resolved.music_search_backend is MusicSearchBackend.POSTGRES_STAGING
        ):
            # DEV runtime composition for the live entrypoint
            # (uvicorn remanence.main:create_app --factory, no factory param):
            # wire from the newly built app.state.session_factory only.
            # PROD stays refused; missing factory fails closed (no listen
            # with half-wired search).
            live_factory = getattr(app.state, "session_factory", None)
            music_search = build_music_search(resolved, live_factory)
            app.state.music_search = music_search
        if blob_store is not None:
            app.state.blob_store = blob_store
        elif resolved.mode is not AppMode.TEST and resolved.blob_root is not None:
            app.state.blob_store = LocalFileBlobStore(resolved.blob_root)
        if ciphertext_stager is not None:
            app.state.ciphertext_stager = ciphertext_stager
        elif resolved.mode is not AppMode.TEST and resolved.blob_root is not None:
            app.state.ciphertext_stager = CiphertextStager(resolved.blob_root / ".staging")
        app.state.pending_registry_ready = False
        if resolved.pending_protocol_enabled and pending_ctx is not None:
            # Registry bootstrap: backfill actual users-table emails as
            # RESERVED rows before any send/claim is admitted. Bounded,
            # idempotent, RESERVED-only; configured outside-DB HMAC only.
            # No live writes: this composition never runs for live beta
            # (master flag OFF there).
            try:
                from remanence.pending.registry import backfill_from_users

                factory = getattr(app.state, "session_factory", None)
                if factory is not None:
                    hmac_secret = resolved.pending_hmac_secret
                    key = hmac_secret.get_secret_value().encode() if hmac_secret else b""
                    with factory() as session:
                        outcome = backfill_from_users(session, hmac_key=key)
                        session.commit()
                    app.state.pending_registry_ready = True
                    app.state.pending_backfill_outcome = outcome
            except Exception:
                app.state.pending_registry_ready = False
        yield
        if engine is not None:
            engine.dispose()

    app = FastAPI(title="Remanence API", version="0.1.0", lifespan=lifespan)
    install_access_filter()
    telemetry = TelemetryIngress(TelemetryStore(lambda: getattr(app.state, "session_factory", None)))
    app.state.telemetry_ingress = telemetry
    app.add_middleware(RequestIdMiddleware, telemetry_app=telemetry)
    app.state.settings = resolved
    app.state.upload_reservations = (
        upload_reservations if upload_reservations is not None else build_upload_reservation_manager()
    )
    if session_factory is not None:
        app.state.session_factory = session_factory
    if blob_store is not None:
        app.state.blob_store = blob_store
    if ciphertext_stager is not None:
        app.state.ciphertext_stager = ciphertext_stager
    if music_search is not None:
        app.state.music_search = music_search

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
        # Starlette's outer ServerErrorMiddleware may see a failed ASGI send.
        # Even that fallback must not allocate/echo a normal request identifier.
        if reserved_path(request.scope.get("path", "")):
            return JSONResponse({"status": "REJECTED"}, status_code=503,
                                headers={"Cache-Control": "no-store"})
        return problem_response(request, "INTERNAL_ERROR")

    app.include_router(health_router)
    app.include_router(auth_router)
    app.include_router(google_auth_router)
    app.include_router(users_router)
    app.include_router(directory_router)
    app.include_router(capsules_router)
    app.include_router(music_router)
    app.include_router(recovery_router)
    app.include_router(safety_router)
    app.include_router(legal_router)
    if resolved.pending_protocol_enabled and pending_ctx is not None:
        # Experimental pending router ONLY under the master flag with
        # explicit ctx. Existing handlers/auth/recovery above are
        # untouched; flag OFF (default) mounts nothing new.
        from remanence.pending.api import router as pending_router

        app.include_router(pending_router)
        app.state.pending_ctx = pending_ctx
    return app
