"""FastAPI application factory."""

from fastapi import FastAPI

from postmark.api.health import router as health_router
from postmark.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = Settings() if settings is None else settings
    app = FastAPI(title="Postmark API", version="0.1.0")
    app.state.settings = resolved
    app.include_router(health_router)
    return app
