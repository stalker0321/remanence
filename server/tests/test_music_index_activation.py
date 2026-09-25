"""Forward activation: fake lock + fake ops + real SQLite store.

Order, DEV/TEST-only guard, READY/open-flight gates, durable intent +
sentinel + single task write, conservative poll (timeout/failed ->
UNKNOWN/FAILED, no retry), read-back count + settings-hash verify,
atomic confirm, and crash windows. No live DB/Meili, no rollback.
"""

from __future__ import annotations

import pytest
import uuid
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.music.domain import normalize_text
from remanence.music.search.activation import (
    MusicActivationError,
    QueryProbe,
    activate_revision,
)
from remanence.music.search.document import meilisearch_index_settings
from remanence.music.search.meilisearch import TaskTimeoutError, canonical_settings_hash
from remanence.music.search.revision_lock import RevisionLockBusyError
from remanence.music.search.revision_store import RevisionStateStore, RevisionStoreConflictError
from remanence.music.staging.models import MusicBase
from remanence.settings import AppMode

_SETTINGS = meilisearch_index_settings()
_READBACK_EXTRA = {
    "typoTolerance": {"enabled": True},
    "pagination": {"maxTotalHits": 1000},
}
_READBACK_SETTINGS = {**_SETTINGS, **_READBACK_EXTRA}
# Revision rows MUST store the full read-back hash (backends add
# defaults): hashing the write payload would pin an incomplete picture
# and every fingerprint check would false-fail. The explicit
# write-vs-read divergence is pinned by a dedicated test below.
_READBACK_HASH = canonical_settings_hash(_READBACK_SETTINGS)


class FakeLock:
    def __init__(self, acquired: bool = True) -> None:
        self._acquired = acquired
        self.releases = 0
        self.held = False

    def acquire(self) -> bool:
        if not self._acquired:
            return False
        self.held = True
        return True

    def release(self) -> bool:
        self.releases += 1
        self.held = False
        return True


class FakeOps:
    def __init__(
        self,
        task_statuses: list[str] | None = None,
        count: int = 3,
        settings: dict | None = None,
        exists: bool = True,
        counts_by_uid: dict[str, int] | None = None,
        settings_by_uid: dict[str, dict] | None = None,
        existing_uids: set[str] | None = None,
        fail_with: Exception | None = None,
        mirror_swap: bool = True,
    ) -> None:
        self.calls: list[tuple] = []
        self._statuses = list(task_statuses or ["succeeded"])
        self._count = count
        self._settings = settings if settings is not None else dict(_READBACK_SETTINGS)
        self._exists = exists
        self._counts_by_uid = dict(counts_by_uid or {})
        self._settings_by_uid = {k: dict(v) for k, v in (settings_by_uid or {}).items()}
        self._existing_uids = set(existing_uids) if existing_uids is not None else None
        self._fail_with = fail_with
        self._mirror_swap = mirror_swap
        self.post_swap_count: int | None = None
        self.post_swap_settings: dict | None = None
        self._swapped: list[tuple[str, str]] = []

    def _count_for(self, index_uid: str) -> int:
        if index_uid in self._counts_by_uid:
            return self._counts_by_uid[index_uid]
        return self._count

    def _settings_for(self, index_uid: str) -> dict:
        if index_uid in self._settings_by_uid:
            return dict(self._settings_by_uid[index_uid])
        return dict(self._settings)

    def index_exists(self, index_uid: str) -> bool:
        self.calls.append(("exists", index_uid))
        if self._fail_with is not None:
            raise self._fail_with
        if self._existing_uids is not None:
            return index_uid in self._existing_uids
        return self._exists

    def swap_indexes(self, uid_a: str, uid_b: str) -> int:
        self.calls.append(("swap", uid_a, uid_b))
        if self._mirror_swap:
            # Mirror Meilisearch semantics for the fake: the stable UID
            # afterwards serves the candidate's content. Disabled for
            # mismatch tests, where post-swap reads stay divergent.
            self._counts_by_uid[uid_a] = self._count_for(uid_b)
            self._settings_by_uid[uid_a] = self._settings_for(uid_b)
        self._swapped.append((uid_a, uid_b))
        return 900

    def task_status(self, task_uid: int) -> str:
        self.calls.append(("task", task_uid))
        if self._statuses:
            return self._statuses.pop(0)
        return "succeeded"

    def _swapped_to(self, index_uid: str) -> bool:
        return any(a == index_uid for a, _ in self._swapped)

    def get_settings_for(self, index_uid: str) -> dict:
        self.calls.append(("settings", index_uid))
        if self.post_swap_settings is not None and self._swapped_to(index_uid):
            return dict(self.post_swap_settings)
        return self._settings_for(index_uid)

    def index_document_count(self, index_uid: str) -> int:
        self.calls.append(("count", index_uid))
        if self.post_swap_count is not None and self._swapped_to(index_uid):
            return self.post_swap_count
        return self._count_for(index_uid)


class FakeSearch:
    """Scripted probe search port (bound to stable at composition)."""

    def __init__(self, script: list, index_uid: str = "remanence_tracks_v1") -> None:
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


def _engine():
    engine = create_engine(
        "sqlite://",
        execution_options={"schema_translate_map": {"music": None}},
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    MusicBase.metadata.create_all(engine)
    return engine


def _ready(engine, revs=(6, 7), count: int = 3, counts: dict[int, int] | None = None):
    store = RevisionStateStore(lambda: Session(engine))
    for rev in revs:
        store.create_revision(
            rev=rev,
            candidate_uid=f"remanence_tracks_v1_rev{rev:04d}",
            doc_count=counts[rev] if counts is not None else count,
            settings_hash=_READBACK_HASH,
        )
    return store


def _bootstrap(store, rev: int = 6):
    """Directly confirm rev ACTIVE (precondition helper, no swap)."""
    uid = f"remanence_tracks_v1_rev{rev:04d}"
    boot = store.open_activation(
        stable_uid="remanence_tracks_v1", partner_uid=uid, from_rev=None, to_rev=rev
    )
    store.transition(boot.id, "PENDING", "SWAPPED")
    return store.confirm_activation(boot.id)


def _probe_hit(hit_id: str):
    from collections import namedtuple

    return namedtuple("Hit", ["id"])(hit_id)


def _default_probes() -> list:
    return [QueryProbe(query="abyss", expected_top_id="tid-1", min_total=1)]


def _default_search() -> FakeSearch:
    return FakeSearch([([_probe_hit("tid-1")], 34)])


def _activate(store, ops, lock=None, to_rev=7, **kwargs):
    from remanence.music.search.activation import StableProbeSearch

    params = {"mode": AppMode.DEV, "task_timeout_s": 5.0, "poll_interval_s": 0.0}
    if "query_probes" not in kwargs and "search" not in kwargs:
        params["query_probes"] = _default_probes()
        params["search"] = StableProbeSearch(
            _default_search(), "remanence_tracks_v1"
        )
    params.update(kwargs)
    search = params.get("search")
    if search is not None and not isinstance(search, StableProbeSearch):
        params["search"] = StableProbeSearch(search, search.index_uid)
    return activate_revision(
        lock if lock is not None else FakeLock(),
        store,
        ops,
        "remanence_tracks_v1",
        to_rev,
        **params,
    )


def test_happy_path_order_and_confirm() -> None:
    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 2,
                "remanence_tracks_v1_rev0007": 3,
            }
        )
        lock = FakeLock()
        record = _activate(store, ops, lock)
        assert record.state == "CONFIRMED"
        assert record.to_rev == 7
        kinds = [call[0] for call in ops.calls]
        assert kinds[0] == "exists"
        assert "swap" in kinds
        swap_at = kinds.index("swap")
        assert kinds[swap_at + 1] == "task"
        assert "settings" in kinds[kinds.index("task"):]
        assert "count" in kinds[kinds.index("task"):]
        assert lock.releases == 1 and lock.held is False
        fresh = RevisionStateStore(lambda: Session(engine))
        assert fresh.find_active_revision().rev == 7  # type: ignore[union-attr]
        assert fresh.get_revision(6).status == "SUPERSEDED"  # type: ignore[union-attr]
    finally:
        engine.dispose()


def test_mode_guard_default_deny() -> None:
    engine = _engine()
    try:
        store = _ready(engine)
        ops = FakeOps()
        for mode in (AppMode.PROD, "", None, 123, "dev"):
            with pytest.raises(MusicActivationError):
                _activate(store, ops, mode=mode)  # type: ignore[arg-type]
        assert ops.calls == []
    finally:
        engine.dispose()


def test_candidate_must_be_ready_and_candidate_index_must_exist() -> None:
    engine = _engine()
    try:
        store = _ready(engine, revs=(7,))
        with pytest.raises(MusicActivationError):
            _activate(store, FakeOps(), mode=AppMode.DEV, to_rev=99)
        with pytest.raises(TypeError):
            _activate(FakeOps(), FakeOps(), mode=AppMode.DEV)  # type: ignore[arg-type]
        ops = FakeOps(exists=False)
        with pytest.raises(MusicActivationError):
            _activate(store, ops)
        assert [call[0] for call in ops.calls] == ["exists"]
    finally:
        engine.dispose()


def test_open_flight_and_busy_lock_refuse_before_writes() -> None:
    engine = _engine()
    try:
        store = _ready(engine)
        store.open_activation(
            stable_uid="remanence_tracks_v1",
            partner_uid="remanence_tracks_v1_rev0006",
            from_rev=None,
            to_rev=6,
        )
        with pytest.raises(MusicActivationError):
            _activate(store, FakeOps())
        assert store.read_open_activation("remanence_tracks_v1").state == "PENDING"  # type: ignore[union-attr]
    finally:
        engine.dispose()
    engine = _engine()
    try:
        store = _ready(engine)
        lock = FakeLock(acquired=False)
        with pytest.raises(MusicActivationError):
            _activate(store, FakeOps(), lock)
        assert store.read_open_activation("remanence_tracks_v1") is None
    finally:
        engine.dispose()


def _activation_state(engine, activation_id: int) -> str | None:
    from remanence.music.search.revision_store import IndexActivation

    with Session(engine) as session:
        row = session.get(IndexActivation, activation_id)
        return row.state if row is not None else None


def _latest_activation_id(engine, stable_uid: str) -> int | None:
    from sqlalchemy import select as sa_select

    from remanence.music.search.revision_store import IndexActivation

    with Session(engine) as session:
        return session.scalars(
            sa_select(IndexActivation.id)
            .where(IndexActivation.stable_uid == stable_uid)
            .order_by(IndexActivation.id.desc())
            .limit(1)
        ).first()


def test_swap_task_failed_leaves_failed_without_confirm() -> None:
    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        ops = FakeOps(
            task_statuses=["failed"],
            counts_by_uid={
                "remanence_tracks_v1": 2,
                "remanence_tracks_v1_rev0007": 3,
            },
        )
        with pytest.raises(MusicActivationError):
            _activate(store, ops)
        assert store.read_open_activation("remanence_tracks_v1") is None
        latest = _latest_activation_id(engine, "remanence_tracks_v1")
        assert latest is not None
        assert _activation_state(engine, latest) == "FAILED"
        fresh = RevisionStateStore(lambda: Session(engine))
        assert fresh.find_active_revision().rev == 6  # type: ignore[union-attr]
    finally:
        engine.dispose()


def test_select_probes_sees_duplicates_beyond_window() -> None:
    from remanence.music.search.activation import _select_probes

    candidates = [
        ("id-1", "Song", None),
        ("id-2", "Abyss", None),
        ("id-3", "505", "Live"),
    ]
    # "song" collides 42 times outside the candidate window: skipped even
    # though it leads the window; the rest still qualify.
    probes = _select_probes(candidates, {"song": 42, "abyss": 1, "505": 1})
    by_query = {probe.query: probe.expected_top_id for probe in probes}
    assert "Song" not in by_query
    assert by_query["Abyss"] == "id-2"
    assert by_query["505 live"] == "id-3"


def test_select_probes_variant_collision_and_tokenless_skip() -> None:
    from remanence.music.search.activation import _select_probes

    candidates = [
        ("id-1", "Song", None),
        ("id-2", "Song", "Deluxe Edition"),
        ("id-3", "Song", "Live"),
    ]
    probes = _select_probes(candidates, {"song": 3})
    assert probes == []
    candidates = [
        ("id-1", "Alone", None),
        ("id-2", "Alone", "Remix"),
    ]
    probes = _select_probes(candidates, {"alone": 2})
    assert probes == []


def test_derive_refuses_over_cap_without_sample_fallback(monkeypatch) -> None:
    import remanence.music.search.activation as activation_module
    from remanence.music.search.activation import derive_query_probes

    monkeypatch.setattr(activation_module, "MAX_PROBE_SOURCE_ROWS", 2)
    engine = _engine()
    try:
        session = Session(engine)
        _staging_track(session, "Abyss", ["KSLV"])
        _staging_track(session, "Song", ["Kate"])
        _staging_track(session, "505", ["Arctic Monkeys"])
        session.commit()
        session.close()
        with pytest.raises(MusicActivationError):
            derive_query_probes(lambda: Session(engine))
    finally:
        engine.dispose()


def _staging_track(
    session, title: str, artists: list[str], variant: str | None = None, track_id=None
):
    import uuid as uuid_module

    from remanence.music.staging.models import (
        StagedArtist,
        StagedTrack,
        StagedTrackArtist,
        TrackStatus,
    )

    artist_ids = []
    for name in artists:
        artist_id = uuid_module.uuid4()
        session.add(
            StagedArtist(
                id=artist_id, name=name, normalized_name=normalize_text(name)
            )
        )
        artist_ids.append(artist_id)
    track_id = track_id or uuid_module.uuid4()
    session.add(
        StagedTrack(
            id=track_id,
            title=title,
            normalized_title=normalize_text(title),
            duration_ms=200000,
            variant=variant,
            first_release_year=2007,
            status=TrackStatus.ACTIVE,
        )
    )
    for position, artist_id in enumerate(artist_ids):
        session.add(
            StagedTrackArtist(track_id=track_id, artist_id=artist_id, position=position)
        )
    return track_id


def test_derive_query_probes_exact_cyrillic_variant() -> None:
    from remanence.music.search.activation import derive_query_probes

    engine = _engine()
    try:
        session = Session(engine)
        plain = _staging_track(
            session,
            "Abyss",
            ["KSLV"],
            track_id=uuid.UUID("11111111-1111-4111-8111-111111111111"),
        )
        cyrillic = _staging_track(
            session,
            "Группа крови",
            ["Кино"],
            track_id=uuid.UUID("22222222-2222-4222-8222-222222222222"),
        )
        live = _staging_track(
            session,
            "505",
            ["Arctic Monkeys"],
            variant="Live",
            track_id=uuid.UUID("33333333-3333-4333-8333-333333333333"),
        )
        session.commit()
        session.close()
        probes = derive_query_probes(lambda: Session(engine))
        by_query = {probe.query: probe.expected_top_id for probe in probes}
        assert by_query["Abyss"] == str(plain)
        assert by_query["Группа крови"] == str(cyrillic)
        assert by_query["505 live"] == str(live)
        assert len(probes) == 3
        again = derive_query_probes(lambda: Session(engine))
        assert [(p.query, p.expected_top_id) for p in again] == [
            (p.query, p.expected_top_id) for p in probes
        ]
    finally:
        engine.dispose()


def test_derive_sees_duplicates_beyond_window(monkeypatch) -> None:
    import remanence.music.search.activation as activation_module
    from remanence.music.search.activation import derive_query_probes

    monkeypatch.setattr(activation_module, "MAX_PROBE_CANDIDATES", 2)
    engine = _engine()
    try:
        session = Session(engine)
        _staging_track(
            session,
            "Abyss",
            ["KSLV"],
            track_id=uuid.UUID("11111111-1111-4111-8111-111111111111"),
        )
        zephyr = _staging_track(
            session,
            "Zephyr",
            ["W Sailor"],
            track_id=uuid.UUID("22222222-2222-4222-8222-222222222222"),
        )
        _staging_track(
            session,
            "Abyss",
            ["Cover Band"],
            track_id=uuid.UUID("33333333-3333-4333-8333-333333333333"),
        )
        session.commit()
        session.close()
        # Window holds [Abyss, Zephyr]; the second Abyss sits beyond it.
        # The SQL global GROUP BY must still see the duplicate and refuse
        # the ambiguous title while keeping the unique one.
        probes = derive_query_probes(lambda: Session(engine))
        by_query = {probe.query: probe.expected_top_id for probe in probes}
        assert "Abyss" not in by_query
        assert by_query["Zephyr"] == str(zephyr)
    finally:
        engine.dispose()


def test_derive_skips_duplicate_bare_titles() -> None:
    from remanence.music.search.activation import derive_query_probes

    engine = _engine()
    try:
        session = Session(engine)
        _staging_track(
            session,
            "Song",
            ["Kate A"],
            track_id=uuid.UUID("11111111-1111-4111-8111-111111111111"),
        )
        _staging_track(
            session,
            "Song",
            ["Kate B"],
            track_id=uuid.UUID("22222222-2222-4222-8222-222222222222"),
        )
        unique = _staging_track(
            session,
            "Abyss",
            ["KSLV"],
            track_id=uuid.UUID("33333333-3333-4333-8333-333333333333"),
        )
        session.commit()
        session.close()
        probes = derive_query_probes(lambda: Session(engine))
        assert [probe.query for probe in probes] == ["Abyss"]
        assert probes[0].expected_top_id == str(unique)
    finally:
        engine.dispose()


def test_derive_canonical_vs_variant_pair() -> None:
    from remanence.music.search.activation import derive_query_probes

    engine = _engine()
    try:
        session = Session(engine)
        _staging_track(
            session,
            "505",
            ["Arctic Monkeys"],
            track_id=uuid.UUID("11111111-1111-4111-8111-111111111111"),
        )
        _staging_track(
            session,
            "505",
            ["Arctic Monkeys"],
            variant="Live at the Apollo",
            track_id=uuid.UUID("22222222-2222-4222-8222-222222222222"),
        )
        remix = _staging_track(
            session,
            "Café del Mar",
            ["Energy 52"],
            variant="Three N One Remix",
            track_id=uuid.UUID("33333333-3333-4333-8333-333333333333"),
        )
        session.commit()
        session.close()
        probes = derive_query_probes(lambda: Session(engine))
        by_query = {probe.query: probe.expected_top_id for probe in probes}
        # Bare "505" is ambiguous (two rows share it) and must not be an
        # exact probe; the only variant probe names a globally-unique
        # title plus its kind token.
        assert "505" not in by_query
        assert "505 live" not in by_query
        assert by_query["Café del Mar"] == str(remix)
        assert by_query["Café del Mar remix"] == str(remix)
    finally:
        engine.dispose()


def test_derive_query_probes_plain_only_and_empty() -> None:
    from remanence.music.search.activation import derive_query_probes

    engine = _engine()
    try:
        session = Session(engine)
        _staging_track(session, "Abyss", ["KSLV"])
        session.commit()
        session.close()
        probes = derive_query_probes(lambda: Session(engine))
        assert len(probes) == 1 and probes[0].query == "Abyss"
    finally:
        engine.dispose()
    engine = _engine()
    try:
        with pytest.raises(MusicActivationError):
            derive_query_probes(lambda: Session(engine))
        with pytest.raises(MusicActivationError):
            derive_query_probes(None)  # type: ignore[arg-type]
    finally:
        engine.dispose()


def test_swap_timeout_records_unknown_with_intent_and_task() -> None:
    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        ops = FakeOps(
            task_statuses=["enqueued"] * 50,
            counts_by_uid={
                "remanence_tracks_v1": 2,
                "remanence_tracks_v1_rev0007": 3,
            },
        )
        with pytest.raises(MusicActivationError):
            _activate(store, ops, task_timeout_s=0.0)
        fresh = RevisionStateStore(lambda: Session(engine))
        seen = fresh.read_open_activation("remanence_tracks_v1")
        assert seen is not None
        assert seen.state == "UNKNOWN"
        assert seen.post_attempted is True
        assert seen.task_uid == 900
    finally:
        engine.dispose()


def test_count_and_hash_mismatch_go_unknown() -> None:
    for count, settings in ((2, None), (3, {"rankingRules": ["typo"]})):
        engine = _engine()
        try:
            store = _ready(engine, counts={6: 2, 7: 3})
            _bootstrap(store, rev=6)
            ops = FakeOps(
                counts_by_uid={
                    "remanence_tracks_v1": 2,
                    "remanence_tracks_v1_rev0007": 3,
                },
                mirror_swap=False,
            )
            ops.post_swap_count = count
            ops.post_swap_settings = settings
            with pytest.raises(MusicActivationError):
                _activate(store, ops)
            fresh = RevisionStateStore(lambda: Session(engine))
            assert fresh.read_open_activation("remanence_tracks_v1").state == "UNKNOWN"  # type: ignore[union-attr]
            with pytest.raises(RevisionStoreConflictError):
                fresh.find_active_revision()
            latest = _latest_activation_id(engine, "remanence_tracks_v1")
            assert latest is not None
            fresh.transition(latest, "UNKNOWN", "FAILED")
            assert fresh.find_active_revision().rev == 6  # type: ignore[union-attr]
        finally:
            engine.dispose()


def test_invalid_args_fail_before_any_call() -> None:
    engine = _engine()
    try:
        store = _ready(engine)
        ops = FakeOps()
        with pytest.raises(MusicActivationError):
            _activate(store, ops, to_rev=-1)
        with pytest.raises(MusicActivationError):
            activate_revision(
                FakeLock(), store, ops, "", 7, mode=AppMode.DEV, query_probes=[]
            )
        with pytest.raises(MusicActivationError):
            _activate(store, ops, poll_interval_s=-1.0)
        assert ops.calls == []
        assert store.read_open_activation("remanence_tracks_v1") is None
    finally:
        engine.dispose()


def test_task_timeout_error_is_typed() -> None:
    from remanence.music.ports import MusicSearchError

    assert issubclass(TaskTimeoutError, MusicSearchError)


def test_stable_missing_fails_before_intent() -> None:
    engine = _engine()
    try:
        store = _ready(engine)
        ops = FakeOps(existing_uids={"remanence_tracks_v1_rev0007"})
        with pytest.raises(MusicActivationError):
            _activate(store, ops)
        assert store.read_open_activation("remanence_tracks_v1") is None
        assert [call[0] for call in ops.calls] == ["exists"]
    finally:
        engine.dispose()


def test_bootstrap_requires_empty_stable() -> None:
    engine = _engine()
    try:
        store = _ready(engine, revs=(7,), counts={7: 3})
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 5,
                "remanence_tracks_v1_rev0007": 3,
            }
        )
        with pytest.raises(MusicActivationError):
            _activate(store, ops)
        assert [call[0] for call in ops.calls] == [
            "exists",
            "count",
            "settings",
            "exists",
            "count",
            "settings",
        ]
        assert "swap" not in [call[0] for call in ops.calls]
        assert store.read_open_activation("remanence_tracks_v1") is None
    finally:
        engine.dispose()


def test_active_fingerprint_mismatch_fails_before_intent() -> None:
    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 99,
                "remanence_tracks_v1_rev0007": 3,
            }
        )
        with pytest.raises(MusicActivationError):
            _activate(store, ops)
        assert "swap" not in [call[0] for call in ops.calls]
        assert store.read_open_activation("remanence_tracks_v1") is None
    finally:
        engine.dispose()


def test_candidate_precheck_fails_before_intent() -> None:
    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 2,
                "remanence_tracks_v1_rev0007": 777,
            }
        )
        with pytest.raises(MusicActivationError):
            _activate(store, ops)
        assert "swap" not in [call[0] for call in ops.calls]
        assert store.read_open_activation("remanence_tracks_v1") is None
    finally:
        engine.dispose()


def test_idempotent_returns_prior_confirmation_without_writes() -> None:
    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        first = _activate(
            store,
            FakeOps(
                counts_by_uid={
                    "remanence_tracks_v1": 2,
                    "remanence_tracks_v1_rev0007": 3,
                }
            ),
        )
        assert first.state == "CONFIRMED"
        latest_before = _latest_activation_id(engine, "remanence_tracks_v1")
        second_ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 3,
                "remanence_tracks_v1_rev0007": 3,
            }
        )
        again = _activate(store, second_ops)
        assert again == first
        assert "swap" not in [call[0] for call in second_ops.calls]
        assert _latest_activation_id(engine, "remanence_tracks_v1") == latest_before
    finally:
        engine.dispose()


def test_idempotent_without_prior_confirmation_fails_closed() -> None:
    engine = _engine()
    try:
        store = _ready(engine, revs=(7,), counts={7: 3})
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 3,
                "remanence_tracks_v1_rev0007": 3,
            }
        )
        with pytest.raises(MusicActivationError):
            _activate(store, ops)
        assert "swap" not in [call[0] for call in ops.calls]
    finally:
        engine.dispose()


def test_empty_probes_refused_before_any_call() -> None:
    engine = _engine()
    try:
        store = _ready(engine)
        ops = FakeOps()
        with pytest.raises(MusicActivationError):
            _activate(store, ops, query_probes=[])
        assert ops.calls == []
        assert store.read_open_activation("remanence_tracks_v1") is None
    finally:
        engine.dispose()


def test_wrong_bound_search_refused() -> None:
    from remanence.music.search.activation import StableProbeSearch

    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 2,
                "remanence_tracks_v1_rev0007": 3,
            }
        )
        mislabeled = FakeSearch([([_probe_hit("tid-1")], 34)], index_uid="other-index")
        with pytest.raises(MusicActivationError):
            _activate(store, ops, search=mislabeled, query_probes=_default_probes())
        assert "swap" not in [call[0] for call in ops.calls]
        sneaky = StableProbeSearch(
            FakeSearch([([_probe_hit("tid-1")], 34)], index_uid="other-index"),
            "remanence_tracks_v1",
        )
        with pytest.raises(MusicActivationError):
            _activate(store, ops, search=sneaky, query_probes=_default_probes())
        assert "swap" not in [call[0] for call in ops.calls]
    finally:
        engine.dispose()


def test_revision_hash_is_read_back_not_write_payload() -> None:
    assert canonical_settings_hash(_SETTINGS) != _READBACK_HASH
    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 2,
                "remanence_tracks_v1_rev0007": 3,
            },
            settings_by_uid={
                "remanence_tracks_v1": dict(_READBACK_SETTINGS),
                "remanence_tracks_v1_rev0007": dict(_SETTINGS),
            },
            mirror_swap=False,
        )
        with pytest.raises(MusicActivationError):
            _activate(store, ops)
    finally:
        engine.dispose()


def _probe_hit(hit_id: str):
    from collections import namedtuple

    return namedtuple("Hit", ["id"])(hit_id)


def test_probes_enforced_and_mismatch_goes_unknown() -> None:
    from remanence.music.search.activation import QueryProbe

    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)
        probes = [QueryProbe(query="abyss", expected_top_id="tid-1", min_total=1)]
        search = FakeSearch(
            [
                ([_probe_hit("tid-1")], 34),
            ]
        )
        ops = FakeOps(
            counts_by_uid={
                "remanence_tracks_v1": 2,
                "remanence_tracks_v1_rev0007": 3,
            }
        )
        record = _activate(store, ops, search=search, query_probes=probes)
        assert record.state == "CONFIRMED"
        assert search.calls == [("abyss", 5, 0)]
    finally:
        engine.dispose()
    for script, label in (
        ([([], 0)], "empty"),
        ([([_probe_hit("other")], 34)], "wrong-top"),
        ([RuntimeError("down")], "error"),
    ):
        engine = _engine()
        try:
            store = _ready(engine, counts={6: 2, 7: 3})
            _bootstrap(store, rev=6)
            ops = FakeOps(
                counts_by_uid={
                    "remanence_tracks_v1": 2,
                    "remanence_tracks_v1_rev0007": 3,
                }
            )
            search = FakeSearch(script)
            with pytest.raises(MusicActivationError):
                _activate(store, ops, search=search, query_probes=probes)
            fresh = RevisionStateStore(lambda: Session(engine))
            assert fresh.read_open_activation("remanence_tracks_v1").state == "UNKNOWN", label  # type: ignore[union-attr]
        finally:
            engine.dispose()


def test_probes_require_search_port() -> None:
    from remanence.music.search.activation import QueryProbe

    engine = _engine()
    try:
        store = _ready(engine)
        with pytest.raises(MusicActivationError):
            _activate(
                store,
                FakeOps(),
                query_probes=[QueryProbe(query="x", expected_top_id="y")],
            )
    finally:
        engine.dispose()


def test_poll_transport_error_goes_unknown() -> None:
    from remanence.music.ports import MusicSearchUnavailableError

    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)

        class _Down(FakeOps):
            def task_status(self, task_uid: int) -> str:
                self.calls.append(("task", task_uid))
                raise MusicSearchUnavailableError("down")

        with pytest.raises(MusicActivationError):
            _activate(store, _Down(
                counts_by_uid={
                    "remanence_tracks_v1": 2,
                    "remanence_tracks_v1_rev0007": 3,
                }
            ))
        fresh = RevisionStateStore(lambda: Session(engine))
        assert fresh.read_open_activation("remanence_tracks_v1").state == "UNKNOWN"  # type: ignore[union-attr]
    finally:
        engine.dispose()


def test_post_swap_read_error_goes_unknown() -> None:
    engine = _engine()
    try:
        store = _ready(engine, counts={6: 2, 7: 3})
        _bootstrap(store, rev=6)

        class _Flaky(FakeOps):
            def index_document_count(self, index_uid: str) -> int:
                self.calls.append(("count", index_uid))
                if index_uid == "remanence_tracks_v1" and any(
                    call[0] == "swap" for call in self.calls
                ):
                    raise RuntimeError("read failed post-swap")
                return self._count_for(index_uid)

        with pytest.raises(MusicActivationError):
            _activate(
                store,
                _Flaky(
                    counts_by_uid={
                        "remanence_tracks_v1": 2,
                        "remanence_tracks_v1_rev0007": 3,
                    }
                ),
            )
        fresh = RevisionStateStore(lambda: Session(engine))
        assert fresh.read_open_activation("remanence_tracks_v1").state == "UNKNOWN"  # type: ignore[union-attr]
    finally:
        engine.dispose()
