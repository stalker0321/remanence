"""Engine and session-factory construction. No global connection."""

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from remanence.settings import Settings


class DatabaseConfigurationError(RuntimeError):
    """Raised when Settings cannot produce a database engine."""


def build_engine(settings: Settings) -> Engine:
    if settings.database_url is None:
        raise DatabaseConfigurationError("database_url is required")
    return create_engine(
        settings.database_url.get_secret_value(),
        pool_pre_ping=True,
    )


def build_session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(
        bind=engine,
        autoflush=False,
        expire_on_commit=False,
    )
