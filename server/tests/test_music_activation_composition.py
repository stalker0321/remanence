"""Workflow composition: fake manager/store/ops/lock/search + SQLite.

DEV/TEST happy CONFIRMED, PROD/disabled zero calls, wrong-bound
refusal, over-cap zero mutation, build failure never swaps. No live
DB/Meili, no CLI.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.music.domain import normalize_text
from remanence.music.search.activation import MusicActivationError, StableProbeSearch
from remanence.music.search.activation_composition import compose_and_activate_revision
from remanence.music.search.document import meilisearch_index_settings
from remanence.music.search.meilisearch import canonical_settings_hash
from remanence.music.search.revision_store import RevisionStateStore
from remanence.music.search.revisions import MusicIndexRevision, RevisionStatus
from remanence.music.staging.models import (
    MusicBase,
    StagedArtist,
    StagedTrack,
    StagedTrackArtist,
    TrackStatus,
)
from remanence.settings import AppMode, MusicSearchBackend, Settings

_READBACK_SETTINGS = {
    **meilisearch_index_settings(),
    "typoTolerance": {"enabled": True},
}
_READBACK_HASH = canonical_settings_hash(_READBACK_SETTINGS)


class FakeManager:
    def __init__(self, fail_with: Exception | None = None) -> None:
        self.calls: list[tuple] = []
        self.fail_with = fail_with

    def build_revision(self, base_uid: str, rev: int) -> MusicIndexRevision:
        self.calls.append(("build", base_uid, rev))
        if self.fail_with is not None:
            raise self.fail_with
        return MusicIndexRevision(
            rev=rev,
            index_uid=f"{base_uid}_rev{rev:04d}",
            documents_pushed=2,
            expected_count=2,
            status=RevisionStatus.VALIDATED,
        )


class FakeLock:
    def __init__(self, acquired: bool = True) -> None:
        self._acquired = acquired
        self.releases = 0

    def acquire(self) -> bool:
        return self._acquired

    def release(self) -> bool:
        self.releases += 1
        return True


class FakeHit:
    def __init__(self, hit_id: str) -> None:
        self.id = hit_id


class FakeSearch:
    def __init__(self, script: list, index_uid: str) -> None:
        self._script = list(script)
        self.index_uid = index_uid
        self.calls: list[tuple] = []

    @property
    def config(self):
        parent = self

        class _Config:
            @property
            def index_uid(self) -> str:
                return parent.index_uid

        return _Config()

    def search_with_total(self, query: str, limit: int, offset: int = 0):
        self.calls.append((query, limit, offset))
        action = self._script.pop(0) if self._script else ([], 0)
        if isinstance(action, Exception):
            raise action
        return action


class FakeOps:
    def __init__(
        self,
        counts_by_uid: dict[str, int] | None = None,
        task_statuses: list[str] | None = None,
    ) -> None:
        self.calls: list[tuple] = []
        self._counts_by_uid = dict(counts_by_uid or {})
        self._statuses = list(task_statuses or ["succeeded"])

    def _count_for(self, index_uid: str) -> int:
        if index_uid in self._counts_by_uid:
            return self._counts_by_uid[index_uid]
        return 2

    def index_exists(self, index_uid: str) -> bool:
        self.calls.append(("exists", index_uid))
        return True

    def swap_indexes(self, uid_a: str, uid_b: str) -> int:
        self.calls.append(("swap", uid_a, uid_b))
        self._counts_by_uid[uid_a] = self._count_for(uid_b)
        return 900

    def task_status(self, task_uid: int) -> str:
        self.calls.append(("task", task_uid))
        if self._statuses:
            return self._statuses.pop(0)
        return "succeeded"

    def get_settings_for(self, index_uid: str) -> dict:
        self.calls.append(("settings", index_uid))
        return dict(_READBACK_SETTINGS)

    def index_document_count(self, index_uid: str) -> int:
        self.calls.append(("count", index_uid))
        return self._count_for(index_uid)


def _engine():
    engine = create_engine(
        "sqlite://",
        execution_options={"schema_translate_map": {"music": None}},
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    MusicBase.metadata.create_all(engine)
    return engine


def _staging_track(session, title: str, artists: list[str]) -> None:
    artist_ids = []
    for name in artists:
        artist_id = uuid.uuid4()
        session.add(
            StagedArtist(id=artist_id, name=name, normalized_name=normalize_text(name))
        )
        artist_ids.append(artist_id)
    track_id = uuid.uuid4()
    session.add(
        StagedTrack(
            id=track_id,
            title=title,
            normalized_title=normalize_text(title),
            duration_ms=200000,
            variant=None,
            first_release_year=2007,
            status=TrackStatus.ACTIVE,
        )
    )
    for position, artist_id in enumerate(artist_ids):
        session.add(
            StagedTrackArtist(track_id=track_id, artist_id=artist_id, position=position)
        )


def _settings(mode: AppMode, backend=MusicSearchBackend.POSTGRES_STAGING) -> Settings:
    return Settings(
        mode=mode,
        database_url="postgresql+psycopg://u:p@127.0.0.1:1/db",
        blob_root="/tmp/opencode-music-blobs",
        music_search_backend=backend,
    )


def _compose(engine, manager=None, ops=None, lock=None, search=None, **kwargs):
    from remanence.music.search.activation import derive_query_probes

    store = RevisionStateStore(lambda: Session(engine))
    session = Session(engine)
    _staging_track(session, "Abyss", ["KSLV"])
    _staging_track(session, "Song", ["Kate"])
    session.commit()
    session.close()
    if search is None:
        derived = derive_query_probes(lambda: Session(engine))
        port = FakeSearch(
            [([FakeHit(p.expected_top_id)], p.min_total) for p in derived],
            "remanence_tracks_v1",
        )
    else:
        port = search
    bound = StableProbeSearch(port, getattr(port, "index_uid", "remanence_tracks_v1"))
    params = {
        "settings": _settings(AppMode.DEV),
        "session_factory": lambda: Session(engine),
        "manager": manager if manager is not None else FakeManager(),
        "store": store,
        "ops": ops if ops is not None else FakeOps(),
        "lock": lock if lock is not None else FakeLock(),
        "search": bound,
        "stable_uid": "remanence_tracks_v1",
        "base_uid": "remanence_tracks_v1",
        "rev": 7,
        "task_timeout_s": 5.0,
        "poll_interval_s": 0.0,
    }
    params.update(kwargs)
    return compose_and_activate_revision(**params), store


def _latest(engine) -> int | None:
    from sqlalchemy import select as sa_select

    from remanence.music.search.revision_store import IndexActivation

    with Session(engine) as session:
        return session.scalars(
            sa_select(IndexActivation.id)
            .where(IndexActivation.stable_uid == "remanence_tracks_v1")
            .order_by(IndexActivation.id.desc())
            .limit(1)
        ).first()


def test_dev_and_test_happy_confirmed() -> None:
    from remanence.music.search.activation import derive_query_probes

    for mode in (AppMode.DEV, AppMode.TEST):
        engine = _engine()
        try:
            settings = _settings(mode)
            store = RevisionStateStore(lambda: Session(engine))
            session = Session(engine)
            _staging_track(session, "Abyss", ["KSLV"])
            session.commit()
            session.close()
            probes = derive_query_probes(lambda: Session(engine))
            assert probes
            manager, lock = FakeManager(), FakeLock()
            ops = FakeOps(
                counts_by_uid={
                    "remanence_tracks_v1": 0,
                    "remanence_tracks_v1_rev0007": 2,
                }
            )
            port = FakeSearch(
                [([FakeHit(p.expected_top_id)], p.min_total) for p in probes],
                "remanence_tracks_v1",
            )
            built, record = compose_and_activate_revision(
                settings=settings,
                session_factory=lambda: Session(engine),
                manager=manager,
                store=store,
                ops=ops,
                lock=lock,
                search=StableProbeSearch(port, "remanence_tracks_v1"),
                stable_uid="remanence_tracks_v1",
                base_uid="remanence_tracks_v1",
                rev=7,
                task_timeout_s=5.0,
                poll_interval_s=0.0,
            )
            assert built.rev == 7
            assert record.state == "CONFIRMED"
            assert manager.calls == [("build", "remanence_tracks_v1", 7)]
            assert lock.releases == 1
            fresh = RevisionStateStore(lambda: Session(engine))
            assert fresh.find_active_revision().rev == 7  # type: ignore[union-attr]
        finally:
            engine.dispose()


def test_prod_and_disabled_backend_zero_calls() -> None:
    for settings in (
        _settings(AppMode.PROD),
        _settings(AppMode.DEV, MusicSearchBackend.DISABLED),
    ):
        engine = _engine()
        try:
            manager, ops = FakeManager(), FakeOps()
            with pytest.raises(MusicActivationError):
                compose_and_activate_revision(
                    settings=settings,
                    session_factory=lambda: Session(engine),
                    manager=manager,
                    store=RevisionStateStore(lambda: Session(engine)),
                    ops=ops,
                    lock=FakeLock(),
                    search=StableProbeSearch(
                        FakeSearch([], "remanence_tracks_v1"), "remanence_tracks_v1"
                    ),
                    stable_uid="remanence_tracks_v1",
                    base_uid="remanence_tracks_v1",
                    rev=7,
                )
            assert manager.calls == []
            assert ops.calls == []
        finally:
            engine.dispose()


def test_invalid_args_refused_before_any_call() -> None:
    import math as math_module

    engine = _engine()
    try:
        manager, ops = FakeManager(), FakeOps()
        base = {
            "settings": _settings(AppMode.DEV),
            "session_factory": lambda: Session(engine),
            "manager": manager,
            "store": RevisionStateStore(lambda: Session(engine)),
            "ops": ops,
            "lock": FakeLock(),
            "search": StableProbeSearch(
                FakeSearch([], "remanence_tracks_v1"), "remanence_tracks_v1"
            ),
            "stable_uid": "remanence_tracks_v1",
            "base_uid": "remanence_tracks_v1",
            "rev": 7,
        }
        for bad in ({"rev": True}, {"rev": "7"}, {"rev": -1}):
            with pytest.raises(MusicActivationError):
                compose_and_activate_revision(**{**base, **bad})
        for bad in (
            {"task_timeout_s": math_module.inf},
            {"task_timeout_s": float("nan")},
            {"task_timeout_s": -1.0},
            {"poll_interval_s": math_module.inf},
            {"poll_interval_s": True},
        ):
            with pytest.raises(MusicActivationError):
                compose_and_activate_revision(**{**base, **bad})
        assert manager.calls == []
        assert ops.calls == []
        assert RevisionStateStore(lambda: Session(engine)).read_open_activation() is None
    finally:
        engine.dispose()


def test_idempotent_rerun_skips_build_and_persist() -> None:
    engine = _engine()
    try:
        manager = FakeManager()
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 0,
                "remanence_tracks_v1_rev0007": 2,
            }
        )
        first_pair, _ = _compose(engine, manager=manager, ops=ops)
        first_built, first_record = first_pair
        assert first_record.state == "CONFIRMED"
        latest_before = _latest(engine)
        manager.calls.clear()
        ops.calls.clear()
        from remanence.music.search.activation import derive_query_probes

        rerun_probes = derive_query_probes(lambda: Session(engine))
        port = FakeSearch(
            [([FakeHit(p.expected_top_id)], p.min_total) for p in rerun_probes],
            "remanence_tracks_v1",
        )
        second_built, second_record = compose_and_activate_revision(
            settings=_settings(AppMode.DEV),
            session_factory=lambda: Session(engine),
            manager=manager,
            store=RevisionStateStore(lambda: Session(engine)),
            ops=FakeOps(
                counts_by_uid={
                    "remanence_tracks_v1": 2,
                    "remanence_tracks_v1_rev0007": 2,
                }
            ),
            lock=FakeLock(),
            search=StableProbeSearch(port, "remanence_tracks_v1"),
            stable_uid="remanence_tracks_v1",
            base_uid="remanence_tracks_v1",
            rev=7,
            task_timeout_s=5.0,
            poll_interval_s=0.0,
        )
        assert second_built is None
        assert second_record == first_record
        assert manager.calls == []
        assert "swap" not in [call[0] for call in ops.calls]
        assert _latest(engine) == latest_before
    finally:
        engine.dispose()


def test_ready_rerun_refuses_without_rebuild() -> None:
    engine = _engine()
    try:
        store = RevisionStateStore(lambda: Session(engine))
        store.create_revision(
            rev=7,
            candidate_uid="remanence_tracks_v1_rev0007",
            doc_count=2,
            settings_hash="ab" * 32,
        )
        manager, ops = FakeManager(), FakeOps()
        with pytest.raises(MusicActivationError):
            _compose(engine, manager=manager, ops=ops)
        assert manager.calls == []
        assert "swap" not in [call[0] for call in ops.calls]
    finally:
        engine.dispose()


def test_wrong_bound_search_refused_before_build() -> None:
    engine = _engine()
    try:
        manager, ops = FakeManager(), FakeOps()
        port = FakeSearch([], "other-index")
        with pytest.raises(MusicActivationError):
            compose_and_activate_revision(
                settings=_settings(AppMode.DEV),
                session_factory=lambda: Session(engine),
                manager=manager,
                store=RevisionStateStore(lambda: Session(engine)),
                ops=ops,
                lock=FakeLock(),
                    search=StableProbeSearch(port, "other-index"),
                    stable_uid="remanence_tracks_v1",
                    base_uid="remanence_tracks_v1",
                    rev=7,
                )
        assert manager.calls == []
        assert ops.calls == []
    finally:
        engine.dispose()


def test_source_over_cap_zero_mutation(monkeypatch) -> None:
    import remanence.music.search.activation as activation_module

    monkeypatch.setattr(activation_module, "MAX_PROBE_SOURCE_ROWS", 1)
    engine = _engine()
    try:
        manager, ops = FakeManager(), FakeOps()
        with pytest.raises(MusicActivationError):
            _compose(engine, manager=manager, ops=ops)
        assert manager.calls == []
        assert ops.calls == []
        assert RevisionStateStore(lambda: Session(engine)).read_open_activation() is None
    finally:
        engine.dispose()


def test_build_failure_never_invokes_swap() -> None:
    engine = _engine()
    try:
        manager = FakeManager(fail_with=RuntimeError("build blew up"))
        ops = FakeOps()
        with pytest.raises(RuntimeError):
            _compose(engine, manager=manager, ops=ops)
        assert "swap" not in [call[0] for call in ops.calls]
        assert RevisionStateStore(lambda: Session(engine)).read_open_activation() is None
    finally:
        engine.dispose()


def test_bad_types_rejected() -> None:
    engine = _engine()
    try:
        with pytest.raises(TypeError):
            compose_and_activate_revision(
                settings="dev",  # type: ignore[arg-type]
                session_factory=lambda: Session(engine),
                manager=FakeManager(),
                store=RevisionStateStore(lambda: Session(engine)),
                ops=FakeOps(),
                lock=FakeLock(),
                search=StableProbeSearch(
                    FakeSearch([], "remanence_tracks_v1"), "remanence_tracks_v1"
                ),
                    stable_uid="remanence_tracks_v1",
                    base_uid="remanence_tracks_v1",
                    rev=7,
                )
    finally:
        engine.dispose()


def test_garbage_dependencies_rejected_with_zero_calls() -> None:
    engine = _engine()
    try:
        manager, ops = FakeManager(), FakeOps()
        good_search = StableProbeSearch(
            FakeSearch([], "remanence_tracks_v1"), "remanence_tracks_v1"
        )
        base = {
            "settings": _settings(AppMode.DEV),
            "session_factory": lambda: Session(engine),
            "manager": manager,
            "store": RevisionStateStore(lambda: Session(engine)),
            "ops": ops,
            "lock": FakeLock(),
            "search": good_search,
            "stable_uid": "remanence_tracks_v1",
            "base_uid": "remanence_tracks_v1",
            "rev": 7,
        }
        with pytest.raises(TypeError):
            compose_and_activate_revision(**{**base, "search": object()})
        with pytest.raises(TypeError):
            compose_and_activate_revision(**{**base, "manager": object()})
        with pytest.raises(TypeError):
            compose_and_activate_revision(**{**base, "store": object()})
        assert manager.calls == []
        assert ops.calls == []
    finally:
        engine.dispose()


def test_base_must_equal_stable_with_zero_calls() -> None:
    engine = _engine()
    try:
        manager, ops = FakeManager(), FakeOps()
        with pytest.raises(MusicActivationError):
            compose_and_activate_revision(
                settings=_settings(AppMode.DEV),
                session_factory=lambda: Session(engine),
                manager=manager,
                store=RevisionStateStore(lambda: Session(engine)),
                ops=ops,
                lock=FakeLock(),
                search=StableProbeSearch(
                    FakeSearch([], "remanence_tracks_v1"), "remanence_tracks_v1"
                ),
                stable_uid="remanence_tracks_v1",
                base_uid="other_base",
                rev=7,
            )
        assert manager.calls == []
        assert ops.calls == []
    finally:
        engine.dispose()


def test_port_config_mismatch_refused_with_zero_calls() -> None:
    engine = _engine()
    try:
        manager, ops = FakeManager(), FakeOps()
        sneaky = StableProbeSearch(
            FakeSearch([], "other-index"), "remanence_tracks_v1"
        )
        with pytest.raises(MusicActivationError):
            compose_and_activate_revision(
                settings=_settings(AppMode.DEV),
                session_factory=lambda: Session(engine),
                manager=manager,
                store=RevisionStateStore(lambda: Session(engine)),
                ops=ops,
                lock=FakeLock(),
                search=sneaky,
                stable_uid="remanence_tracks_v1",
                base_uid="remanence_tracks_v1",
                rev=7,
            )
        assert manager.calls == []
        assert ops.calls == []
    finally:
        engine.dispose()
