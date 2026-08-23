"""FastAPI shared dependencies."""

from collections.abc import Iterator

from fastapi import Request
from sqlalchemy.orm import Session


class DatabaseUnavailableError(RuntimeError):
    pass


def get_db_session(request: Request) -> Iterator[Session]:
    factory = getattr(request.app.state, "session_factory", None)
    if factory is None:
        raise DatabaseUnavailableError()
    session = factory()
    try:
        yield session
    finally:
        session.close()