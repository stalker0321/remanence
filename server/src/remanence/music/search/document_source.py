"""Staging-to-search-document source for index (re)builds (D4-A).

Streams ``StagedTrack`` rows plus their position-ordered artist credits
through an injected SQLAlchemy session factory and yields
:func:`build_search_document`-shaped mappings — the exact payload a
revision builder pushes to the search backend. One fresh session per
batch (never shared, never committed — read-only).

Scale contract (40M-row full dump in mind):
- stable keyset order (track id ascending): every run emits the same
  sequence, so builds are resumable and diffable;
- bounded batches (1..1000 rows per DB round-trip): at most one batch
  plus its credits is ever retained — no whole-table materialization;
- no N+1: each batch costs exactly three bounded queries (one track-id
  page, one track-row fetch, one credit join), regardless of batch size;
- explicit empty/limit behavior: an empty table yields zero documents
  (no error); ``limit`` caps the total emitted (``None`` means all).

Staging-to-document mapping (sample slice; no Meili writes here):
- ``id`` is the staged own ``RemanenceTrackId`` (never an MBID/ISRC);
- ``artists`` are credit-position-ordered display names (homonyms stay
  separate artist rows upstream, so shared names never merge here);
- ``version`` is the raw variant (``None`` when the track has none);
- ``is_canonical``/``canonicalRank`` derive from variant absence, mirroring
  the staging ranker's variant demotion;
- ``release`` is ``None`` and ``artworkAvailable`` is ``False``: staging
  carries neither (no invented catalog data);
- a track row without credits violates the loader invariant and fails
  closed (loud abort, never a silently truncated index).
"""

from __future__ import annotations

from collections.abc import Callable, Iterator
from typing import Any
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.music.domain import MusicTrack
from remanence.music.search.document import build_search_document
from remanence.music.staging.models import (
    StagedArtist,
    StagedTrack,
    StagedTrackArtist,
)

DEFAULT_DOCUMENT_BATCH_SIZE = 1000
MAX_DOCUMENT_BATCH_SIZE = 1000


class StagingSearchDocumentSource:
    """Deterministic bounded stream of search documents over staging."""

    def __init__(
        self,
        session_factory: Callable[[], Session],
        batch_size: int = DEFAULT_DOCUMENT_BATCH_SIZE,
    ) -> None:
        if not callable(session_factory):
            raise TypeError("StagingSearchDocumentSource requires a session factory")
        if type(batch_size) is not int or not 1 <= batch_size <= MAX_DOCUMENT_BATCH_SIZE:
            raise ValueError(
                f"batch_size must be an int in 1..{MAX_DOCUMENT_BATCH_SIZE}"
            )
        self._sessions = session_factory
        self._batch_size = batch_size

    @property
    def batch_size(self) -> int:
        return self._batch_size

    def total_expected(self) -> int:
        """Read-only staged track count (revision-validation hook).

        Cheap ``COUNT(*)`` for progress reporting and build-vs-source
        reconciliation. Never modified by iteration.
        """
        with self._sessions() as session:
            return int(
                session.scalar(select(func.count()).select_from(StagedTrack)) or 0
            )

    def iter_documents(self, limit: int | None = None) -> Iterator[dict[str, Any]]:
        """Yield search documents in stable track-id order.

        ``limit`` bounds the total emitted (``None`` = entire table);
        anything else must be an int >= 0. An empty staging table yields
        zero documents.
        """
        if limit is not None and (type(limit) is not int or limit < 0):
            raise ValueError("limit must be None or an int >= 0")
        remaining = limit
        last_id: UUID | None = None
        while True:
            if remaining is not None and remaining <= 0:
                return
            page_size = self._batch_size
            if remaining is not None:
                page_size = min(page_size, remaining)
            # Materialize at most one batch of plain mappings INSIDE the
            # session: ORM rows are never touched after close (no detached
            # access), yet no session is held across yields and no more
            # than one batch is ever retained.
            with self._sessions() as session:
                track_ids = self._page_ids(session, last_id, page_size)
                if not track_ids:
                    return
                tracks = self._load_tracks(session, track_ids)
                credits = self._load_credits(session, track_ids)
                documents = [
                    self._to_document(tracks.get(track_id), credits.get(track_id))
                    for track_id in track_ids
                ]
            for track_id, document in zip(track_ids, documents):
                yield document
                last_id = track_id
                if remaining is not None:
                    remaining -= 1

    @staticmethod
    def _to_document(
        track: StagedTrack | None, names: list[str] | None
    ) -> dict[str, Any]:
        if track is None or not names:
            raise ValueError("staged track has no loadable credits")
        return build_search_document(
            MusicTrack.create(
                title=track.title,
                artists=tuple(names),
                track_id=track.id,
                year=track.first_release_year,
                duration_ms=track.duration_ms,
                version=track.variant,
                is_canonical=track.variant is None,
            )
        )

    @staticmethod
    def _page_ids(
        session: Session, after: UUID | None, page_size: int
    ) -> list[UUID]:
        """One track-id page strictly after ``after`` (keyset order)."""
        statement = select(StagedTrack.id).order_by(StagedTrack.id).limit(page_size)
        if after is not None:
            statement = statement.where(StagedTrack.id > after)
        return list(session.scalars(statement).all())

    @staticmethod
    def _load_tracks(
        session: Session, track_ids: list[UUID]
    ) -> dict[UUID, StagedTrack]:
        rows = session.scalars(
            select(StagedTrack).where(StagedTrack.id.in_(track_ids))
        ).all()
        return {row.id: row for row in rows}

    @staticmethod
    def _load_credits(
        session: Session, track_ids: list[UUID]
    ) -> dict[UUID, list[str]]:
        """Track id -> artist display names in credit position order."""
        rows = session.execute(
            select(StagedTrackArtist.track_id, StagedArtist.name)
            .join(StagedArtist, StagedArtist.id == StagedTrackArtist.artist_id)
            .where(StagedTrackArtist.track_id.in_(track_ids))
            .order_by(StagedTrackArtist.track_id, StagedTrackArtist.position)
        ).all()
        credits: dict[UUID, list[str]] = {}
        for track_id, name in rows:
            credits.setdefault(track_id, []).append(name)
        return credits
