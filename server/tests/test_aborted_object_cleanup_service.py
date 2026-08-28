"""PostgreSQL tests for aborted-object BlobStore cleanup."""

from __future__ import annotations

import hashlib
import inspect
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
from threading import Barrier
from typing import Any
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select, text
from sqlalchemy.orm import object_session

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.aborted_object_cleanup_service import (
    ABORTED_OBJECT_CLEANUP_BATCH_MAX,
    ABORTED_OBJECT_CLEANUP_BATCH_MIN,
    AbortedObjectCleanupError,
    AbortedObjectCleanupResult,
    AbortedObjectCleanupService,
)
from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobState
from remanence.capsules.finalize_service import CapsuleFinalizeError, CapsuleFinalizeService
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.capsules.models import Capsule, CapsuleState
from remanence.storage import BlobNotFoundError, BlobStoreError, InvalidBlobKeyError, LocalFileBlobStore

from test_capsule_abort_service import (
    _NOW,
    _add_draft,
    _blob_snapshot,
    _capsule_shape,
    _seed_user,
)


def _sha(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _assert_error(call, code: str) -> AbortedObjectCleanupError:
    with pytest.raises(AbortedObjectCleanupError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "aborted object cleanup failed"
    assert repr(caught.value) == f"AbortedObjectCleanupError(code={code!r})"
    return caught.value


def _forbid_commit_rollback(session, monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("aborted object cleanup must not commit or rollback")

    monkeypatch.setattr(session, "commit", forbidden)
    monkeypatch.setattr(session, "rollback", forbidden)


class _RecordingStore:
    def __init__(self, delegate: LocalFileBlobStore | None = None) -> None:
        self.delegate = delegate
        self.deleted: list[str] = []
        self.fail_keys: set[str] = set()
        self.missing_keys: set[str] = set()
        self.invalid_keys: set[str] = set()
        self.unexpected_keys: set[str] = set()

    def delete(self, key: str) -> None:
        self.deleted.append(key)
        if key in self.invalid_keys:
            raise InvalidBlobKeyError(f"secret-invalid:{key}")
        if key in self.fail_keys:
            raise BlobStoreError(f"secret-store:{key}")
        if key in self.unexpected_keys:
            raise RuntimeError(f"secret-boom:{key}")
        if key in self.missing_keys:
            raise BlobNotFoundError(key)
        if self.delegate is not None:
            self.delegate.delete(key)

    def list(self, *args: Any, **kwargs: Any) -> None:
        raise AssertionError("cleanup must not list storage")

    def rglob(self, *args: Any, **kwargs: Any) -> None:
        raise AssertionError("cleanup must not scan storage")


def _put(store: LocalFileBlobStore, blob: CapsuleBlob, payload: bytes) -> None:
    store.put(
        blob.object_key,
        BytesIO(payload),
        expected_size=len(payload),
        expected_sha256=_sha(payload),
    )


def _exists(store: LocalFileBlobStore, key: str) -> bool:
    try:
        store.stat(key)
    except Exception:
        return False
    return True


def _blobs(session, capsule_id: UUID) -> list[CapsuleBlob]:
    return list(
        session.scalars(
            select(CapsuleBlob)
            .where(CapsuleBlob.capsule_id == capsule_id)
            .order_by(CapsuleBlob.id)
        )
    )


def _clean(session, store, *, limit: int = ABORTED_OBJECT_CLEANUP_BATCH_MAX):
    return AbortedObjectCleanupService(session, store).clean_aborted_objects(limit=limit)


def test_invalid_limit_fail_closed_without_database() -> None:
    service = AbortedObjectCleanupService(None, None)
    for limit in (0, 101, -1, True, False, 1.0, "1"):
        _assert_error(lambda limit=limit: service.clean_aborted_objects(limit=limit), "VALIDATION_FAILED")
    assert ABORTED_OBJECT_CLEANUP_BATCH_MIN == 1
    assert ABORTED_OBJECT_CLEANUP_BATCH_MAX == 100
    parameters = inspect.signature(AbortedObjectCleanupService.__init__).parameters
    assert set(parameters) == {"self", "session", "blob_store"}
    import remanence.capsules.aborted_object_cleanup_service as cleanup_module

    source = inspect.getsource(cleanup_module)
    assert "with_for_update" not in source
    assert "CiphertextStager" not in source
    assert "rglob" not in source
    assert "listdir" not in source
    assert "list_dir" not in source


def test_aborted_declared_and_stored_keys_are_deleted_and_rows_kept(session_factory, tmp_path: Path, monkeypatch):
    store = LocalFileBlobStore(tmp_path / "blobs")
    recorder = _RecordingStore(store)
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        aborted = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        live = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        aborted.state = CapsuleState.ABORTED
        blobs = _blobs(session, aborted.id)
        live_blobs = _blobs(session, live.id)
        stored = blobs[0]
        declared = blobs[1]
        live_blob = live_blobs[0]
        stored.state = CapsuleBlobState.STORED
        _put(store, stored, b"stored-ciphertext")
        _put(store, live_blob, b"live-ciphertext")
        session.commit()
        aborted_shape = _capsule_shape(session.get(Capsule, aborted.id))
        live_shape = _capsule_shape(session.get(Capsule, live.id))
        aborted_blobs = _blob_snapshot(session, aborted.id)
        live_blob_snap = _blob_snapshot(session, live.id)
        idempotency = session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord))
        _forbid_commit_rollback(session, monkeypatch)
        result = _clean(session, recorder, limit=10)
        monkeypatch.undo()
        assert isinstance(result, AbortedObjectCleanupResult)
        assert result.examined_count == 5
        assert result.deleted_or_missing_count == 5
        assert result.failed_count == 0
        assert result.skipped_count == 0
        assert repr(result) == "AbortedObjectCleanupResult(<redacted>)"
        assert stored.object_key in recorder.deleted
        assert declared.object_key in recorder.deleted
        assert live_blob.object_key not in recorder.deleted
        assert not _exists(store, stored.object_key)
        assert _exists(store, live_blob.object_key)
        assert _capsule_shape(session.get(Capsule, aborted.id)) == aborted_shape
        assert _capsule_shape(session.get(Capsule, live.id)) == live_shape
        assert _blob_snapshot(session, aborted.id) == aborted_blobs
        assert _blob_snapshot(session, live.id) == live_blob_snap
        assert session.get(CapsuleBlob, stored.id).state is CapsuleBlobState.STORED
        assert session.get(CapsuleBlob, declared.id).state is CapsuleBlobState.DECLARED
        assert session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord)) == idempotency
        session.rollback()
        assert _capsule_shape(session.get(Capsule, aborted.id)) == aborted_shape
        assert _blob_snapshot(session, aborted.id) == aborted_blobs
        assert not _exists(store, stored.object_key)


def test_missing_object_counts_as_success_and_failures_are_redacted_then_retried(
    session_factory, tmp_path: Path
):
    store = LocalFileBlobStore(tmp_path / "blobs")
    recorder = _RecordingStore(store)
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        aborted = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        aborted.state = CapsuleState.ABORTED
        blobs = _blobs(session, aborted.id)
        first, second, third = blobs[0], blobs[1], blobs[2]
        first.state = CapsuleBlobState.STORED
        second.state = CapsuleBlobState.STORED
        _put(store, first, b"one")
        _put(store, second, b"two")
        session.commit()
        recorder.missing_keys.add(third.object_key)
        recorder.fail_keys.add(first.object_key)
        secret = first.object_key
        first_snap = _blob_snapshot(session, aborted.id)
        result = _clean(session, recorder, limit=10)
        assert result.examined_count == 5
        assert result.deleted_or_missing_count == 4
        assert result.failed_count == 1
        assert result.skipped_count == 0
        assert _exists(store, first.object_key)
        assert not _exists(store, second.object_key)
        assert secret not in repr(result)
        assert "secret-store" not in repr(result)
        assert _blob_snapshot(session, aborted.id) == first_snap
        recorder.fail_keys.clear()
        retry = _clean(session, recorder, limit=10)
        assert retry.deleted_or_missing_count == 5
        assert retry.failed_count == 0
        assert not _exists(store, first.object_key)
        assert session.get(CapsuleBlob, first.id).object_key == first.object_key


def test_invalid_key_and_unexpected_errors_do_not_leak(session_factory):
    recorder = _RecordingStore()
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        aborted = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        aborted.state = CapsuleState.ABORTED
        blobs = _blobs(session, aborted.id)
        recorder.invalid_keys.add(blobs[0].object_key)
        recorder.unexpected_keys.add(blobs[1].object_key)
        session.commit()
        result = _clean(session, recorder, limit=10)
        assert result.failed_count == 2
        assert result.deleted_or_missing_count == 3
        leaked = repr(result) + str(result)
        assert blobs[0].object_key not in leaked
        assert "secret-invalid" not in leaked
        assert "secret-boom" not in leaked


def test_cleanup_limit_is_deterministic(session_factory):
    recorder = _RecordingStore()
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        aborted = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        aborted.state = CapsuleState.ABORTED
        session.commit()
        ordered = [blob.id for blob in _blobs(session, aborted.id)]
        result = _clean(session, recorder, limit=2)
        assert result.examined_count == 2
        assert result.deleted_or_missing_count == 2
        expected = [
            session.get(CapsuleBlob, ordered[0]).object_key,
            session.get(CapsuleBlob, ordered[1]).object_key,
        ]
        assert recorder.deleted == expected


def test_attached_stale_identity_map_skips_non_aborted(session_factory, tmp_path: Path, monkeypatch):
    store = LocalFileBlobStore(tmp_path / "blobs")
    recorder = _RecordingStore(store)
    with session_factory() as setup:
        sender, sender_bundle = _seed_user(setup, "sender")
        recipient, recipient_bundle = _seed_user(setup, "recipient")
        aborted = _add_draft(setup, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        draft = _add_draft(setup, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        aborted.state = CapsuleState.ABORTED
        aborted_blob = _blobs(setup, aborted.id)[0]
        draft_blob = _blobs(setup, draft.id)[0]
        aborted_blob.state = CapsuleBlobState.STORED
        draft_blob.state = CapsuleBlobState.STORED
        _put(store, aborted_blob, b"aborted-body")
        _put(store, draft_blob, b"draft-body")
        setup.commit()
        aborted_id = aborted.id
        draft_id = draft.id
        aborted_blob_id = aborted_blob.id
        draft_blob_id = draft_blob.id
        aborted_key = aborted_blob.object_key
        draft_key = draft_blob.object_key

    gc_session = session_factory()
    other_session = session_factory()
    try:
        attached = gc_session.get(Capsule, aborted_id)
        attached_blob = gc_session.get(CapsuleBlob, aborted_blob_id)
        assert gc_session.autoflush is False
        assert object_session(attached) is gc_session
        assert attached.state is CapsuleState.ABORTED
        discovered = AbortedObjectCleanupService(gc_session, recorder)._candidate_blob_ids(limit=10)
        assert (aborted_id, aborted_blob_id) in discovered
        monkeypatch.setattr(
            AbortedObjectCleanupService,
            "_candidate_blob_ids",
            lambda self, *, limit: [(aborted_id, aborted_blob_id), (draft_id, draft_blob_id)],
        )
        other = other_session.get(Capsule, aborted_id)
        other.state = CapsuleState.DRAFT
        other_session.commit()
        assert attached.state is CapsuleState.ABORTED
        result = AbortedObjectCleanupService(gc_session, recorder).clean_aborted_objects(limit=10)
        assert result.examined_count == 2
        assert result.skipped_count == 2
        assert result.deleted_or_missing_count == 0
        assert attached.state is CapsuleState.DRAFT
        assert attached_blob.object_key == aborted_key
        assert _exists(store, aborted_key)
        assert _exists(store, draft_key)
        gc_session.commit()
    finally:
        gc_session.close()
        other_session.close()


def test_two_concurrent_cleaners_are_idempotent(session_factory, tmp_path: Path):
    store = LocalFileBlobStore(tmp_path / "blobs")
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        aborted = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        aborted.state = CapsuleState.ABORTED
        blob = _blobs(session, aborted.id)[0]
        blob.state = CapsuleBlobState.STORED
        _put(store, blob, b"shared")
        session.commit()
        capsule_id = aborted.id
        blob_id = blob.id
        key = blob.object_key
    barrier = Barrier(2)

    def worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            result = _clean(session, store, limit=10)
            session.commit()
            return (result.deleted_or_missing_count, result.failed_count)

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = [future.result(timeout=20) for future in (executor.submit(worker), executor.submit(worker))]
    assert all(failed == 0 for _, failed in outcomes)
    assert sum(deleted for deleted, _ in outcomes) >= 1
    with session_factory() as session:
        assert session.get(Capsule, capsule_id).state is CapsuleState.ABORTED
        assert session.get(CapsuleBlob, blob_id).object_key == key
        assert session.get(CapsuleBlob, blob_id).state is CapsuleBlobState.STORED
    assert not _exists(store, key)


def test_cleanup_versus_finalize_does_not_delete_draft_or_ready_objects(session_factory, tmp_path: Path):
    from tink import tink_config

    from test_capsule_finalize_service import _ready_world

    tink_config.register()
    recorder = _RecordingStore()
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        capsule_id = world["capsule"].id
        sender_id = world["sender"].id
        keys = [blob.object_key for blob in _blobs(session, capsule_id)]
        session.commit()
    barrier = Barrier(2)

    def cleanup_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            result = _clean(session, recorder, limit=10)
            session.commit()
            return result.examined_count, result.deleted_or_missing_count

    def finalize_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = CapsuleFinalizeService(session, world["store"]).finalize(
                    authenticated_sender_user_id=sender_id,
                    capsule_id=capsule_id,
                    statement=world["statement"],
                    signature=world["signature"],
                    sender_key_bundle_id=world["sender_bundle"].id,
                    envelope=world["envelope"],
                    now=_NOW,
                )
                session.commit()
                return ("ready", result.is_replay)
            except CapsuleFinalizeError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        cleanup_future = executor.submit(cleanup_worker)
        finalize_future = executor.submit(finalize_worker)
        examined, deleted = cleanup_future.result(timeout=20)
        finalize_outcome = finalize_future.result(timeout=20)

    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        assert capsule.state in {CapsuleState.DRAFT, CapsuleState.READY}
        assert capsule.state is not CapsuleState.ABORTED
        assert examined == 0
        assert deleted == 0
        assert recorder.deleted == []
        if finalize_outcome[0] == "ready":
            assert capsule.state is CapsuleState.READY
        for key in keys:
            assert _exists(world["store"], key)
