"""Postgres-staging-backed search (sample milestone, no Meili needed).

Implements :class:`MusicSearchPort` over the ARCH section 7 staging
tables (``StagedTrack``/``StagedArtist``/``StagedTrackArtist``) through an
injected SQLAlchemy session factory — one fresh session per call, never
shared, never committed (read-only).

Matching mirrors the in-memory intent ranking: normalized AND-tokens
against title/artist, exact > prefix > substring, variant-aware
canonical preference, stable ``track_id``-hex tiebreak so pages are
deterministic. MBID-safe by construction: one track row yields exactly
one hit; external identities are never read, so ISRC collisions cannot
merge or duplicate results.

Scale note: SQL prefilters candidates, Python ranks them — O(n log n)
per query, ample for the ~10k-row sample. Production scale (1M+ rows)
wants keyset pagination and/or full-text search instead; offset is
capped accordingly.
"""

from __future__ import annotations

from collections.abc import Callable
from uuid import UUID

from sqlalchemy import and_, func, or_, select
from sqlalchemy.orm import Session

from remanence.music.domain import TrackSearchResult, normalize_text
from remanence.music.ports import (
    SEARCH_LIMIT_MAX,
    SEARCH_OFFSET_MAX,
    MusicSearchError,
)
from remanence.music.search.document import VARIANT_TOKENS
from remanence.music.staging.models import (
    StagedArtist,
    StagedTrack,
    StagedTrackArtist,
)


def _tokens(query: str) -> tuple[str, ...]:
    return tuple(t for t in normalize_text(query).split(" ") if t)


def _like_escape(token: str) -> str:
    return (
        token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    )


class PostgresStagingSearch:
    """Deterministic ranked search over staging tables."""

    def __init__(self, session_factory: Callable[[], Session]) -> None:
        if not callable(session_factory):
            raise TypeError("PostgresStagingSearch requires a session factory")
        self._sessions = session_factory

    def search(
        self, query: str, limit: int, offset: int = 0
    ) -> list[TrackSearchResult]:
        """Port contract: one ranked page (see ``search_with_total``)."""
        hits, _total = self.search_with_total(query, limit, offset)
        return hits

    def search_with_total(
        self, query: str, limit: int, offset: int = 0
    ) -> tuple[list[TrackSearchResult], int]:
        """Ranked page plus total match count (for endpoint pagination)."""
        if type(query) is not str or not query.strip():
            raise MusicSearchError("invalid query")
        if type(limit) is not int or not 1 <= limit <= SEARCH_LIMIT_MAX:
            raise MusicSearchError("invalid limit")
        if type(offset) is not int or not 0 <= offset <= SEARCH_OFFSET_MAX:
            raise MusicSearchError("invalid offset")
        tokens = _tokens(query)
        if not tokens:
            raise MusicSearchError("invalid query")
        with self._sessions() as session:
            candidate_ids = self._prefilter_ids(session, tokens)
            if not candidate_ids:
                return [], 0
            tracks = self._load_tracks(session, candidate_ids)
            credits = self._load_credits(session, candidate_ids)
        ranked = self._rank(tracks, credits, tokens)
        total = len(ranked)
        return ranked[offset : offset + limit], total

    @staticmethod
    def _prefilter_ids(session: Session, tokens: tuple[str, ...]) -> list[UUID]:
        """Distinct track ids where every token hits title, artist, or variant.

        Variant is included so queries naming a version ("505 live") can
        match; NULL variants simply never satisfy their clause.
        """
        clauses = [
            or_(
                func.lower(StagedTrack.normalized_title).like(
                    f"%{_like_escape(tok)}%", escape="\\"
                ),
                func.lower(StagedArtist.normalized_name).like(
                    f"%{_like_escape(tok)}%", escape="\\"
                ),
                # Variant is stored raw; lower at query time so SQLite and
                # Postgres (case-sensitive LIKE) behave identically.
                func.lower(StagedTrack.variant).like(
                    f"%{_like_escape(tok)}%", escape="\\"
                ),
            )
            for tok in tokens
        ]
        statement = (
            select(StagedTrack.id)
            .distinct()
            .join(
                StagedTrackArtist,
                StagedTrackArtist.track_id == StagedTrack.id,
            )
            .join(StagedArtist, StagedArtist.id == StagedTrackArtist.artist_id)
            .where(and_(*clauses))
        )
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

    @staticmethod
    def _rank(
        tracks: dict[UUID, StagedTrack],
        credits: dict[UUID, list[str]],
        tokens: tuple[str, ...],
    ) -> list[TrackSearchResult]:
        """Intent ranking identical in spirit to the in-memory ranker.

        Sort key ``(-exact, -prefix, demotion, track_hex)``: exact and
        prefix wins first, versioned variants demoted unless the query
        names a variant, ties broken by stable track-id order.
        """
        wants_variant = any(tok in tokens for tok in VARIANT_TOKENS)
        scored: list[tuple[tuple[int, int, int, str], TrackSearchResult]] = []
        for track_id, track in tracks.items():
            names = credits.get(track_id, [])
            title = track.normalized_title
            artists = [normalize_text(name) for name in names]
            version = normalize_text(track.variant) if track.variant else ""
            haystack = " ".join([title, *artists, version])
            if any(tok not in haystack for tok in tokens):
                continue
            joined = " ".join(tokens)
            exact = 0
            if joined == title or any(joined == artist for artist in artists):
                exact = 3
            elif title == tokens[0] or any(artist == tokens[0] for artist in artists):
                exact = 2
            elif artists and (
                f"{artists[0]} {title}" == joined or f"{title} {artists[0]}" == joined
            ):
                exact = 2
            prefix = 0
            if title.startswith(tokens[0]) or any(
                artist.startswith(tokens[0]) for artist in artists
            ):
                prefix = 1
            demotion = 0
            if track.variant is not None and not wants_variant:
                demotion = 1
            hit = TrackSearchResult(
                id=track_id,
                title=track.title,
                artists=tuple(names),
                version=track.variant,
                release=None,
                year=track.first_release_year,
                duration_ms=track.duration_ms,
                artwork_available=False,
            )
            scored.append(((-exact, -prefix, demotion, str(track_id)), hit))
        scored.sort(key=lambda item: item[0])
        return [hit for _, hit in scored]
