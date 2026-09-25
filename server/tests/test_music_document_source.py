"""Staging document source: bounded keyset-ordered document stream.

SQLite-only fixture (schema_translate_map + StaticPool, same pattern as
the staging API tests): multi-credit ordering with homonym artists,
0/1/1001 rows, deterministic batches, hard batch cap, limit behavior,
and exact mapping parity with build_search_document. No live DB, no
Meili, no dump.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.music.domain import MusicTrack, normalize_text
from remanence.music.search.document import build_search_document
from remanence.music.search.document_source import (
    MAX_DOCUMENT_BATCH_SIZE,
    StagingSearchDocumentSource,
)
from remanence.music.staging.models import (
    MusicBase,
    StagedArtist,
    StagedTrack,
    StagedTrackArtist,
    TrackStatus,
)


def _engine():
    engine = create_engine(
        "sqlite://",
        execution_options={"schema_translate_map": {"music": None}},
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    MusicBase.metadata.create_all(engine)
    return engine


def _add_artist(session: Session, name: str) -> uuid.UUID:
    artist_id = uuid.uuid4()
    session.add(
        StagedArtist(id=artist_id, name=name, normalized_name=normalize_text(name))
    )
    return artist_id


def _add_track(
    session: Session,
    title: str,
    artist_ids: list[uuid.UUID],
    *,
    track_id: uuid.UUID | None = None,
    variant: str | None = None,
    duration_ms: int | None = 200000,
    year: int | None = 2007,
) -> uuid.UUID:
    row_id = track_id or uuid.uuid4()
    session.add(
        StagedTrack(
            id=row_id,
            title=title,
            normalized_title=normalize_text(title),
            duration_ms=duration_ms,
            variant=variant,
            first_release_year=year,
            status=TrackStatus.ACTIVE,
        )
    )
    for position, artist_id in enumerate(artist_ids):
        session.add(
            StagedTrackArtist(track_id=row_id, artist_id=artist_id, position=position)
        )
    return row_id


def _source(engine, **kwargs) -> StagingSearchDocumentSource:
    return StagingSearchDocumentSource(lambda: Session(engine), **kwargs)


def test_empty_table_yields_nothing() -> None:
    engine = _engine()
    try:
        source = _source(engine)
        assert source.total_expected() == 0
        assert list(source.iter_documents()) == []
        assert list(source.iter_documents(limit=10)) == []
    finally:
        engine.dispose()


def test_single_track_multi_credit_ordering_with_homonyms() -> None:
    engine = _engine()
    try:
        session = Session(engine)
        kate_a = _add_artist(session, "Kate")
        kate_b = _add_artist(session, "Kate")
        assert kate_a != kate_b
        monkeys = _add_artist(session, "Arctic Monkeys")
        t1 = _add_track(session, "Song", [kate_a])
        t2 = _add_track(session, "Song", [kate_b])
        t3 = _add_track(
            session, "505", [monkeys, kate_a], variant="Live", duration_ms=None, year=None
        )
        session.commit()
        session.close()

        docs = {doc["id"]: doc for doc in _source(engine).iter_documents()}
        assert set(docs) == {str(t1), str(t2), str(t3)}
        assert docs[str(t1)]["artists"] == ["Kate"]
        assert docs[str(t2)]["artists"] == ["Kate"]
        assert docs[str(t1)]["id"] != docs[str(t2)]["id"]
        live = docs[str(t3)]
        assert live["artists"] == ["Arctic Monkeys", "Kate"]
        assert live["version"] == "Live"
        assert live["isCanonical"] is False
        assert live["canonicalRank"] == 1
        assert live["durationMs"] is None
        assert live["release"] is None
        assert live["hasArtwork"] is False
        studio = docs[str(t1)]
        assert studio["isCanonical"] is True
        assert studio["canonicalRank"] == 0
    finally:
        engine.dispose()


def test_mapping_parity_with_build_search_document() -> None:
    engine = _engine()
    try:
        session = Session(engine)
        artist = _add_artist(session, "Кино")
        track_id = _add_track(session, "Группа крови", [artist], duration_ms=None)
        session.commit()
        session.close()

        (doc,) = list(_source(engine).iter_documents())
        expected = build_search_document(
            MusicTrack.create(
                title="Группа крови",
                artists=("Кино",),
                track_id=track_id,
                year=2007,
                duration_ms=None,
                version=None,
                is_canonical=True,
            )
        )
        assert doc == expected
    finally:
        engine.dispose()


def test_1001_rows_deterministic_batches_and_limit() -> None:
    engine = _engine()
    try:
        session = Session(engine)
        artist = _add_artist(session, "Artist")
        ids = [
            _add_track(session, f"Track {i:04d}", [artist], year=2000 + (i % 25))
            for i in range(1001)
        ]
        session.commit()
        session.close()

        first = list(_source(engine, batch_size=500).iter_documents())
        second = list(_source(engine, batch_size=1000).iter_documents())
        assert len(first) == len(second) == 1001
        assert _source(engine).total_expected() == 1001
        assert [doc["id"] for doc in first] == [doc["id"] for doc in second]
        assert [doc["id"] for doc in first] == sorted(str(i) for i in ids)

        capped = list(_source(engine).iter_documents(limit=1000))
        assert len(capped) == 1001 - 1
        assert [doc["id"] for doc in capped] == sorted(str(i) for i in ids)[:1000]

        assert list(_source(engine).iter_documents(limit=0)) == []
        assert len(list(_source(engine).iter_documents(limit=5000))) == 1001
    finally:
        engine.dispose()


def test_invalid_config_fails_closed() -> None:
    engine = _engine()
    try:
        with pytest.raises(TypeError):
            StagingSearchDocumentSource(None)  # type: ignore[arg-type]
        with pytest.raises(TypeError):
            StagingSearchDocumentSource("not-a-factory")  # type: ignore[arg-type]
        for bad_batch in (0, -1, MAX_DOCUMENT_BATCH_SIZE + 1, "100"):
            with pytest.raises(ValueError):
                StagingSearchDocumentSource(lambda: Session(engine), batch_size=bad_batch)  # type: ignore[arg-type]
        source = _source(engine)
        with pytest.raises(ValueError):
            list(source.iter_documents(limit=-1))
        with pytest.raises(ValueError):
            list(source.iter_documents(limit="10"))  # type: ignore[arg-type]
    finally:
        engine.dispose()


def test_track_without_credits_fails_closed() -> None:
    engine = _engine()
    try:
        session = Session(engine)
        session.add(
            StagedTrack(
                id=uuid.uuid4(),
                title="Orphan",
                normalized_title=normalize_text("Orphan"),
                duration_ms=1000,
                variant=None,
                first_release_year=None,
                status=TrackStatus.ACTIVE,
            )
        )
        session.commit()
        session.close()
        with pytest.raises(ValueError):
            list(_source(engine).iter_documents())
    finally:
        engine.dispose()
