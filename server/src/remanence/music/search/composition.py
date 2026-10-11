"""Opt-in search composition: read-only full catalog or DEV/TEST staging.

Default is unwired (``DISABLED`` → ``None`` → endpoint 503). Setting
``POSTGRES_STAGING`` in DEV/TEST wires :class:`PostgresStagingSearch` over
the caller's existing session factory. PROD always refuses: staging is a
sample harness, never a production catalog. Fixtures stay TEST/DEV-only
via ``build_fixture_music_search``; this module never touches them.
"""

from __future__ import annotations

from collections.abc import Callable

from sqlalchemy.orm import Session

from remanence.music.search.staging import PostgresStagingSearch
from remanence.music.search.full_catalog import FullCatalogSearch
from remanence.settings import AppMode, MusicSearchBackend, Settings


def build_music_search(
    settings: Settings,
    session_factory: Callable[[], Session] | None = None,
) -> PostgresStagingSearch | FullCatalogSearch | None:
    """Compose the music search port from settings + session factory.

    Returns ``None`` when disabled (endpoint stays fail-closed 503).
    Raises ``ValueError`` fail-closed for staging in PROD, staging without
    a session factory, or any unknown backend value.
    """
    backend = settings.music_search_backend
    if backend is MusicSearchBackend.DISABLED:
        return None
    if backend is MusicSearchBackend.FULL_CATALOG:
        if settings.music_catalog_path is None:
            raise ValueError("full catalog search requires music_catalog_path")
        search = FullCatalogSearch(
            settings.music_catalog_path,
            timeout_seconds=settings.music_search_timeout_seconds,
        )
        search.validate_ready()
        return search
    if backend is not MusicSearchBackend.POSTGRES_STAGING:
        raise ValueError(f"unknown music search backend: {backend!r}")
    if settings.mode is AppMode.PROD:
        raise ValueError("staging music search is DEV/TEST only, never PROD")
    if session_factory is None or not callable(session_factory):
        raise ValueError("staging music search requires a session factory")
    return PostgresStagingSearch(session_factory)
