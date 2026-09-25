"""Revision ledger store: isolated SQLite (schema_translate_map/StaticPool).

Duplicate rev/UID, zero-doc rows, CAS conflicts, sentinel visibility
across sessions, the open-flight gate (including the DB-level partial
unique index), and allowed-vs-forbidden transitions. No live DB, no
migration apply, no Meili.
"""

from __future__ import annotations

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.music.search.revision_store import (
    ActivationRecord,
    RevisionRecord,
    RevisionStateStore,
    RevisionStoreConflictError,
)
from remanence.music.staging.models import MusicBase


def _engine():
    engine = create_engine(
        "sqlite://",
        execution_options={"schema_translate_map": {"music": None}},
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    MusicBase.metadata.create_all(engine)
    return engine


def _store(engine) -> RevisionStateStore:
    return RevisionStateStore(lambda: Session(engine))


def _revision(store: RevisionStateStore, rev: int = 7) -> RevisionRecord:
    return store.create_revision(
        rev=rev,
        candidate_uid=f"remanence_tracks_v1_rev{rev:04d}",
        doc_count=111328,
        settings_hash="a" * 64,
    )


def _activation(store: RevisionStateStore) -> ActivationRecord:
    return store.open_activation(
        stable_uid="remanence_tracks_v1",
        partner_uid="remanence_tracks_v1_rev0007",
        from_rev=None,
        to_rev=7,
    )


def test_create_and_get_revision() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        assert store.get_revision(7) is None
        record = _revision(store)
        assert record.status == "READY"
        assert record.doc_count == 111328
        assert store.get_revision(7) == record
    finally:
        engine.dispose()


def test_zero_doc_revision_row_allowed() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        record = store.create_revision(
            rev=0,
            candidate_uid="remanence_tracks_v1_rev0000",
            doc_count=0,
            settings_hash="b" * 64,
        )
        assert record.doc_count == 0
        assert store.get_revision(0) == record
    finally:
        engine.dispose()


def test_duplicate_rev_and_uid_conflict() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        _revision(store, rev=7)
        with pytest.raises(RevisionStoreConflictError):
            _revision(store, rev=7)
        with pytest.raises(RevisionStoreConflictError):
            store.create_revision(
                rev=8,
                candidate_uid="remanence_tracks_v1_rev0007",
                doc_count=1,
                settings_hash="c" * 64,
            )
        with pytest.raises(ValueError):
            store.create_revision(rev=-1, candidate_uid="x", doc_count=0, settings_hash="y")
        with pytest.raises(TypeError):
            RevisionStateStore(None)  # type: ignore[arg-type]
    finally:
        engine.dispose()


def test_open_flight_gate_and_second_open_rejected() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        assert store.read_open_activation() is None
        first = _activation(store)
        assert first.state == "PENDING"
        assert first.post_attempted is False
        assert first.task_uid is None
        assert store.read_open_activation() == first
        with pytest.raises(RevisionStoreConflictError):
            _activation(store)
        store.transition(first.id, "PENDING", "SWAPPED")
        with pytest.raises(RevisionStoreConflictError):
            _activation(store)
        store.transition(first.id, "SWAPPED", "CONFIRMED")
        second = _activation(store)
        assert second.id != first.id
        assert store.read_open_activation() == second
    finally:
        engine.dispose()


def test_sentinel_and_task_visible_in_another_session() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        record = _activation(store)
        store.mark_attempted(record.id)
        store.store_task_uid(record.id, 4242)
        fresh = RevisionStateStore(lambda: Session(engine))
        seen = fresh.read_open_activation()
        assert seen is not None
        assert seen.post_attempted is True
        assert seen.task_uid == 4242
        assert seen.state == "PENDING"
        with pytest.raises(RevisionStoreConflictError):
            store.mark_attempted(999999)
        with pytest.raises(ValueError):
            store.store_task_uid(record.id, -1)
    finally:
        engine.dispose()


def test_duplicate_mark_and_terminal_marks_rejected() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        record = _activation(store)
        store.mark_attempted(record.id)
        with pytest.raises(RevisionStoreConflictError):
            # Re-arming the sentinel must never pass as POST permission.
            store.mark_attempted(record.id)
        store.transition(record.id, "PENDING", "SWAPPED")
        with pytest.raises(RevisionStoreConflictError):
            store.mark_attempted(record.id)
        store.transition(record.id, "SWAPPED", "CONFIRMED")
        with pytest.raises(RevisionStoreConflictError):
            store.mark_attempted(record.id)
    finally:
        engine.dispose()


def test_task_write_requires_attempted_pending_and_single_assignment() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        record = _activation(store)
        with pytest.raises(RevisionStoreConflictError):
            store.store_task_uid(record.id, 111)
        store.mark_attempted(record.id)
        store.store_task_uid(record.id, 111)
        with pytest.raises(RevisionStoreConflictError):
            store.store_task_uid(record.id, 222)
        store.transition(record.id, "PENDING", "SWAPPED")
        with pytest.raises(RevisionStoreConflictError):
            store.store_task_uid(record.id, 333)
    finally:
        engine.dispose()


def test_allowed_vs_forbidden_transitions() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        record = _activation(store)
        moved = store.transition(record.id, "PENDING", "SWAPPED")
        assert moved.state == "SWAPPED"
        with pytest.raises(RevisionStoreConflictError):
            store.transition(record.id, "PENDING", "CONFIRMED")
        with pytest.raises(RevisionStoreConflictError):
            store.transition(record.id, "SWAPPED", "PENDING")
        with pytest.raises(RevisionStoreConflictError):
            # SWAPPED content moved: only CONFIRMED (verified) or UNKNOWN
            # (operator path) may exit; FAILED would pretend otherwise.
            store.transition(record.id, "SWAPPED", "FAILED")
        with pytest.raises(RevisionStoreConflictError):
            store.transition(record.id, "BOGUS", "FAILED")
        confirmed = store.transition(record.id, "SWAPPED", "CONFIRMED")
        assert confirmed.state == "CONFIRMED"
        with pytest.raises(RevisionStoreConflictError):
            store.transition(record.id, "CONFIRMED", "FAILED")
        assert store.read_open_activation() is None
    finally:
        engine.dispose()

def test_unknown_reconciles_to_terminal() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        record = _activation(store)
        store.transition(record.id, "PENDING", "UNKNOWN")
        assert store.read_open_activation() is not None
        done = store.transition(record.id, "UNKNOWN", "FAILED")
        assert done.state == "FAILED"
        assert store.read_open_activation() is None
    finally:
        engine.dispose()


def test_partial_unique_index_exists_in_ddl() -> None:
    from sqlalchemy import text as sa_text

    engine = _engine()
    try:
        with Session(engine) as session:
            row = session.execute(
                sa_text(
                    "SELECT sql FROM sqlite_master "
                    "WHERE type = 'index' AND name = "
                    "'uq_music_index_activation_open_flight'"
                )
            ).scalar()
        assert row is not None, "open-flight partial index missing"
        assert "WHERE" in row and "PENDING" in row and "SWAPPED" in row and "UNKNOWN" in row
    finally:
        engine.dispose()


def _rev_row(store, rev: int, uid: str) -> None:
    store.create_revision(
        rev=rev, candidate_uid=uid, doc_count=10, settings_hash="d" * 64
    )


def _open(store, stable: str, partner: str, from_rev, to_rev: int):
    return store.open_activation(
        stable_uid=stable, partner_uid=partner, from_rev=from_rev, to_rev=to_rev
    )


def _fresh(engine):
    from remanence.music.search.revision_store import RevisionStateStore

    return RevisionStateStore(lambda: Session(engine))


def test_confirm_happy_flips_all_rows_atomically() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        _rev_row(store, 6, "remanence_tracks_v1_rev0006")
        _rev_row(store, 7, "remanence_tracks_v1_rev0007")
        assert store.find_active_revision() is None
        boot = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0006", None, 6)
        store.transition(boot.id, "PENDING", "SWAPPED")
        store.confirm_activation(boot.id)
        record = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0007", 6, 7)
        store.mark_attempted(record.id)
        store.transition(record.id, "PENDING", "SWAPPED")
        done = store.confirm_activation(record.id)
        assert done.state == "CONFIRMED"
        fresh = _fresh(engine)
        assert fresh.read_open_activation("remanence_tracks_v1") is None
        active = fresh.find_active_revision()
        assert active is not None and active.rev == 7
        assert fresh.get_revision(6).status == "SUPERSEDED"  # type: ignore[union-attr]
        assert fresh.get_revision(7).status == "ACTIVE"  # type: ignore[union-attr]
    finally:
        engine.dispose()


def test_confirm_rolls_back_when_from_rev_not_active() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        _rev_row(store, 6, "remanence_tracks_v1_rev0006")
        _rev_row(store, 7, "remanence_tracks_v1_rev0007")
        record = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0007", 6, 7)
        store.transition(record.id, "PENDING", "SWAPPED")
        with pytest.raises(RevisionStoreConflictError):
            store.confirm_activation(record.id)
        fresh = _fresh(engine)
        assert fresh.read_open_activation("remanence_tracks_v1") is not None
        assert fresh.read_open_activation("remanence_tracks_v1").state == "SWAPPED"  # type: ignore[union-attr]
        assert fresh.get_revision(7).status == "READY"  # type: ignore[union-attr]
        assert fresh.get_revision(6).status == "READY"  # type: ignore[union-attr]
    finally:
        engine.dispose()


def test_confirm_bootstrap_and_duplicate_and_active_guard() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        _rev_row(store, 0, "remanence_tracks_v1_rev0000")
        record = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0000", None, 0)
        store.transition(record.id, "PENDING", "SWAPPED")
        done = store.confirm_activation(record.id)
        assert done.state == "CONFIRMED"
        assert _fresh(engine).find_active_revision().rev == 0  # type: ignore[union-attr]
        _rev_row(store, 1, "remanence_tracks_v1_rev0001")
        clash = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0001", 1, 1)
        store.transition(clash.id, "PENDING", "SWAPPED")
        with pytest.raises(RevisionStoreConflictError):
            store.confirm_activation(clash.id)
        store.transition(clash.id, "SWAPPED", "UNKNOWN")
        store.transition(clash.id, "UNKNOWN", "FAILED")
        other = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0001", None, 1)
        store.transition(other.id, "PENDING", "SWAPPED")
        with pytest.raises(RevisionStoreConflictError):
            store.confirm_activation(other.id)
    finally:
        engine.dispose()


def test_confirm_idempotent_only_when_statuses_match() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        _rev_row(store, 6, "remanence_tracks_v1_rev0006")
        _rev_row(store, 7, "remanence_tracks_v1_rev0007")
        boot = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0006", None, 6)
        store.transition(boot.id, "PENDING", "SWAPPED")
        store.confirm_activation(boot.id)
        record = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0007", 6, 7)
        store.transition(record.id, "PENDING", "SWAPPED")
        first = store.confirm_activation(record.id)
        second = store.confirm_activation(record.id)
        assert first == second
        with pytest.raises(RevisionStoreConflictError):
            store.confirm_activation(999999)
        pending = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0007", 7, 7)
        with pytest.raises(RevisionStoreConflictError):
            store.confirm_activation(pending.id)
    finally:
        engine.dispose()


def test_scoped_and_global_open_reads_and_active_lookup() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        assert store.find_active_revision() is None
        first = _open(store, "stable_a", "stable_a_rev0001", None, 1)
        second = _open(store, "stable_b", "stable_b_rev0002", None, 2)
        assert store.read_open_activation("stable_a") == first
        assert store.read_open_activation("stable_b") == second
        assert store.read_open_activation() == second
        assert store.read_open_activation("stable_absent") is None
        with pytest.raises(ValueError):
            store.read_open_activation("")
        _rev_row(store, 1, "x_rev0001")
        _rev_row(store, 2, "x_rev0002")
        assert store.find_active_revision() is None
        from sqlalchemy import update as sa_update
        from remanence.music.search.revision_store import IndexRevision

        with Session(engine) as session:
            session.execute(
                sa_update(IndexRevision)
                .where(IndexRevision.rev.in_([1, 2]))
                .values(status="ACTIVE")
            )
            session.commit()
        with pytest.raises(RevisionStoreConflictError):
            store.find_active_revision()
    finally:
        engine.dispose()


def test_find_active_rejects_unsettled_flight_but_tolerates_pending() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        pending = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0007", None, 7)
        assert store.find_active_revision() is None
        store.transition(pending.id, "PENDING", "SWAPPED")
        with pytest.raises(RevisionStoreConflictError):
            store.find_active_revision()
        store.transition(pending.id, "SWAPPED", "UNKNOWN")
        with pytest.raises(RevisionStoreConflictError):
            store.find_active_revision()
        store.transition(pending.id, "UNKNOWN", "FAILED")
        assert store.find_active_revision() is None
    finally:
        engine.dispose()


def test_confirm_rejects_extra_active_with_no_writes() -> None:
    engine = _engine()
    try:
        store = _store(engine)
        _rev_row(store, 6, "remanence_tracks_v1_rev0006")
        _rev_row(store, 7, "remanence_tracks_v1_rev0007")
        _rev_row(store, 8, "remanence_tracks_v1_rev0008")
        boot = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0006", None, 6)
        store.transition(boot.id, "PENDING", "SWAPPED")
        store.confirm_activation(boot.id)
        from sqlalchemy import update as sa_update
        from remanence.music.search.revision_store import IndexRevision

        with Session(engine) as session:
            session.execute(
                sa_update(IndexRevision)
                .where(IndexRevision.rev == 8)
                .values(status="ACTIVE")
            )
            session.commit()
        record = _open(store, "remanence_tracks_v1", "remanence_tracks_v1_rev0007", 6, 7)
        store.transition(record.id, "PENDING", "SWAPPED")
        with pytest.raises(RevisionStoreConflictError):
            store.confirm_activation(record.id)
        fresh = _fresh(engine)
        assert fresh.read_open_activation("remanence_tracks_v1").state == "SWAPPED"  # type: ignore[union-attr]
        assert fresh.get_revision(7).status == "READY"  # type: ignore[union-attr]
        assert fresh.get_revision(6).status == "ACTIVE"  # type: ignore[union-attr]
        assert fresh.get_revision(8).status == "ACTIVE"  # type: ignore[union-attr]
    finally:
        engine.dispose()
