"""Revision producer: B2a VALIDATED build -> ONE READY ledger row.

Fake index ops + isolated SQLite store (no live Meili/DB): read-back
hash (with backend defaults) persisted and distinguished from the
write payload, count equality pushed == expected > 0, and every
mismatch/transport/status/duplicate failure leaves zero new rows.
"""

from __future__ import annotations

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.music.ports import MusicSearchError, MusicSearchUnavailableError
from remanence.music.search.document import meilisearch_index_settings
from remanence.music.search.meilisearch import canonical_settings_hash
from remanence.music.search.revisions import (
    MusicIndexRevision,
    RevisionBuildError,
    RevisionStatus,
    persist_validated_revision,
)
from remanence.music.search.revision_store import RevisionStateStore, RevisionStoreConflictError
from remanence.music.staging.models import MusicBase

_WRITE_SETTINGS = meilisearch_index_settings()
_READBACK_SETTINGS = {
    **_WRITE_SETTINGS,
    "typoTolerance": {"enabled": True},
    "pagination": {"maxTotalHits": 1000},
}
_READBACK_HASH = canonical_settings_hash(_READBACK_SETTINGS)


class FakeOps:
    def __init__(self, count=5, settings=None) -> None:
        self.calls: list[tuple] = []
        self._count = count
        self._settings = settings if settings is not None else dict(_READBACK_SETTINGS)

    def index_document_count(self, index_uid: str) -> int:
        self.calls.append(("count", index_uid))
        if isinstance(self._count, Exception):
            raise self._count
        return self._count

    def get_settings_for(self, index_uid: str) -> dict:
        self.calls.append(("settings", index_uid))
        if isinstance(self._settings, Exception):
            raise self._settings
        return dict(self._settings)


def _engine():
    engine = create_engine(
        "sqlite://",
        execution_options={"schema_translate_map": {"music": None}},
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    MusicBase.metadata.create_all(engine)
    return engine


def _built(rev: int = 7, pushed: int = 5, expected: int = 5) -> MusicIndexRevision:
    return MusicIndexRevision(
        rev=rev,
        index_uid=f"remanence_tracks_v1_rev{rev:04d}",
        documents_pushed=pushed,
        expected_count=expected,
        status=RevisionStatus.VALIDATED,
    )


def _rows(engine) -> int:
    with Session(engine) as session:
        from sqlalchemy import func, select

        from remanence.music.search.revision_store import IndexRevision

        return int(
            session.scalar(select(func.count()).select_from(IndexRevision)) or 0
        )


def test_happy_persists_read_back_hash_once() -> None:
    engine = _engine()
    try:
        store = RevisionStateStore(lambda: Session(engine))
        ops = FakeOps()
        record = persist_validated_revision(store, ops, _built(), "remanence_tracks_v1")
        assert record.status == "READY"
        assert record.rev == 7
        assert record.doc_count == 5
        assert record.settings_hash == _READBACK_HASH
        assert record.settings_hash != canonical_settings_hash(_WRITE_SETTINGS)
        assert [call[0] for call in ops.calls] == ["count", "settings"]
        assert store.get_revision(7) == record
    finally:
        engine.dispose()


def test_count_mismatch_and_zero_fail_with_no_row() -> None:
    for count in (4, 6, 0):
        engine = _engine()
        try:
            store = RevisionStateStore(lambda: Session(engine))
            with pytest.raises(RevisionBuildError):
                persist_validated_revision(store, FakeOps(count=count), _built(), "remanence_tracks_v1")
            assert _rows(engine) == 0
        finally:
            engine.dispose()


def test_inconsistent_build_object_fails() -> None:
    engine = _engine()
    try:
        store = RevisionStateStore(lambda: Session(engine))
        with pytest.raises(RevisionBuildError):
            persist_validated_revision(store, FakeOps(count=5), _built(pushed=5, expected=4), "remanence_tracks_v1")
        with pytest.raises(RevisionBuildError):
            persist_validated_revision(
                store,
                FakeOps(count=5),
                MusicIndexRevision(
                    rev=7,
                    index_uid="remanence_tracks_v1_rev0007",
                    documents_pushed=5,
                    expected_count=5,
                    status="building",
                ),
                "remanence_tracks_v1",
            )
        assert _rows(engine) == 0
    finally:
        engine.dispose()


def test_transport_errors_leave_no_row() -> None:
    for ops in (
        FakeOps(count=MusicSearchUnavailableError("down")),
        FakeOps(settings=MusicSearchError("bad")),
    ):
        engine = _engine()
        try:
            store = RevisionStateStore(lambda: Session(engine))
            with pytest.raises((MusicSearchError, MusicSearchUnavailableError)):
                persist_validated_revision(store, ops, _built(), "remanence_tracks_v1")
            assert _rows(engine) == 0
        finally:
            engine.dispose()


def test_duplicate_rev_and_uid_conflict() -> None:
    engine = _engine()
    try:
        store = RevisionStateStore(lambda: Session(engine))
        persist_validated_revision(store, FakeOps(), _built(), "remanence_tracks_v1")
        with pytest.raises(RevisionStoreConflictError):
            persist_validated_revision(store, FakeOps(), _built(), "remanence_tracks_v1")
        with pytest.raises(RevisionBuildError):
            # Same UID under another rev can never match its own base/rev
            # derivation: refused before any I/O (the store UNIQUE stays
            # as backstop, covered in the state tests).
            persist_validated_revision(
                store,
                FakeOps(),
                MusicIndexRevision(
                    rev=8,
                    index_uid="remanence_tracks_v1_rev0007",
                    documents_pushed=5,
                    expected_count=5,
                    status=RevisionStatus.VALIDATED,
                ),
                "remanence_tracks_v1",
            )
        assert _rows(engine) == 1
    finally:
        engine.dispose()


def test_rejects_non_revision_input_before_io() -> None:
    engine = _engine()
    try:
        store = RevisionStateStore(lambda: Session(engine))
        ops = FakeOps()
        with pytest.raises(RevisionBuildError):
            persist_validated_revision(store, ops, "not-a-revision", "remanence_tracks_v1")  # type: ignore[arg-type]
        assert ops.calls == []
        assert _rows(engine) == 0
    finally:
        engine.dispose()


def test_mismatched_rev_uid_refused_on_empty_store() -> None:
    engine = _engine()
    try:
        store = RevisionStateStore(lambda: Session(engine))
        ops = FakeOps()
        wrong_uid = MusicIndexRevision(
            rev=7,
            index_uid="remanence_tracks_v1_rev0008",
            documents_pushed=5,
            expected_count=5,
            status=RevisionStatus.VALIDATED,
        )
        with pytest.raises(RevisionBuildError):
            persist_validated_revision(store, ops, wrong_uid, "remanence_tracks_v1")
        with pytest.raises(RevisionBuildError):
            persist_validated_revision(store, ops, _built(), "other_base")
        with pytest.raises(RevisionBuildError):
            persist_validated_revision(store, ops, _built(), "")
        assert ops.calls == []
        assert _rows(engine) == 0
    finally:
        engine.dispose()


def test_ops_capability_enforced_at_boundary() -> None:
    engine = _engine()
    try:
        store = RevisionStateStore(lambda: Session(engine))

        class _NoCount:
            def get_settings_for(self, index_uid: str) -> dict:
                raise AssertionError("must not be called")

        with pytest.raises(TypeError):
            persist_validated_revision(
                store, _NoCount(), _built(), "remanence_tracks_v1"  # type: ignore[arg-type]
            )
        assert _rows(engine) == 0
    finally:
        engine.dispose()
