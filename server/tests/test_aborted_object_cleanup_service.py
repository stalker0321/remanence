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

from remanence.capsules.abort_service import CapsuleAbortService
from remanence.capsules.aborted_object_cleanup_service import (
    ABORTED_OBJECT_CLEANUP_BATCH_MAX,
    ABORTED_OBJECT_CLEANUP_BATCH_MIN,
    AbortedObjectCleanupCursor,
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
        raise AssertionError("caller session must not be committed or rolled back by cleanup")

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


def _clean(session_factory, store, *, limit: int = ABORTED_OBJECT_CLEANUP_BATCH_MAX, after_cursor=None):
    return AbortedObjectCleanupService(session_factory, store).clean_aborted_objects(
        limit=limit, after_cursor=after_cursor
    )


def _sweep(session_factory, store, *, limit: int) -> list[AbortedObjectCleanupResult]:
    pages: list[AbortedObjectCleanupResult] = []
    cursor = None
    while True:
        page = _clean(session_factory, store, limit=limit, after_cursor=cursor)
        pages.append(page)
        if page.has_more is False:
            assert page.next_cursor is None
            return pages
        assert page.next_cursor is not None
        cursor = page.next_cursor


def test_invalid_limit_and_cursor_fail_closed_without_database() -> None:
    def boom():
        raise AssertionError("invalid inputs must not open a session")

    service = AbortedObjectCleanupService(boom, None)
    for limit in (0, 101, -1, True, False, 1.0, "1"):
        _assert_error(lambda limit=limit: service.clean_aborted_objects(limit=limit), "VALIDATION_FAILED")
    _assert_error(
        lambda: service.clean_aborted_objects(limit=1, after_cursor="not-a-cursor"),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.clean_aborted_objects(
            limit=1,
            after_cursor=AbortedObjectCleanupCursor(capsule_id="x", blob_id=uuid4()),  # type: ignore[arg-type]
        ),
        "VALIDATION_FAILED",
    )
    assert ABORTED_OBJECT_CLEANUP_BATCH_MIN == 1
    assert ABORTED_OBJECT_CLEANUP_BATCH_MAX == 100
    parameters = inspect.signature(AbortedObjectCleanupService.__init__).parameters
    assert set(parameters) == {"self", "session_factory", "blob_store"}
    import remanence.capsules.aborted_object_cleanup_service as cleanup_module

    source = inspect.getsource(cleanup_module)
    assert "with_for_update" not in source
    assert "CiphertextStager" not in source
    assert "rglob" not in source
    assert "listdir" not in source
    assert "offset(" not in source


def test_uncommitted_abort_is_invisible_until_commit(session_factory, tmp_path: Path, monkeypatch):
    store = LocalFileBlobStore(tmp_path / "blobs")
    recorder = _RecordingStore(store)
    with session_factory() as writer:
        sender, sender_bundle = _seed_user(writer, "sender")
        recipient, recipient_bundle = _seed_user(writer, "recipient")
        capsule = _add_draft(
            writer,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        blob = _blobs(writer, capsule.id)[0]
        blob.state = CapsuleBlobState.STORED
        _put(store, blob, b"uncommitted")
        writer.commit()
        capsule.state = CapsuleState.ABORTED
        writer.flush()
        _forbid_commit_rollback(writer, monkeypatch)
        unseen = _clean(session_factory, recorder, limit=10)
        assert unseen.examined_count == 0
        assert recorder.deleted == []
        assert _exists(store, blob.object_key)
        monkeypatch.undo()
        writer.rollback()
        assert writer.get(Capsule, capsule.id).state is CapsuleState.DRAFT
        assert _exists(store, blob.object_key)
        still_unseen = _clean(session_factory, recorder, limit=10)
        assert still_unseen.examined_count == 0
        assert _exists(store, blob.object_key)
        writer.get(Capsule, capsule.id).state = CapsuleState.ABORTED
        writer.commit()
        seen = _clean(session_factory, recorder, limit=10)
        assert seen.deleted_or_missing_count == 5
        assert not _exists(store, blob.object_key)


def test_aborted_declared_and_stored_keys_are_deleted_and_rows_kept(session_factory, tmp_path: Path):
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
        result = _clean(session_factory, recorder, limit=10)
        assert isinstance(result, AbortedObjectCleanupResult)
        assert result.examined_count == 5
        assert result.deleted_or_missing_count == 5
        assert result.failed_count == 0
        assert result.skipped_count == 0
        assert result.has_more is False
        assert result.next_cursor is None
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


def test_keyset_pages_examine_every_object_once_without_starvation(session_factory):
    recorder = _RecordingStore()
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        first = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        second = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        first.state = CapsuleState.ABORTED
        second.state = CapsuleState.ABORTED
        session.commit()
        expected = [
            (blob.capsule_id, blob.id, blob.object_key)
            for capsule_id in sorted([first.id, second.id])
            for blob in _blobs(session, capsule_id)
        ]
    pages = _sweep(session_factory, recorder, limit=3)
    assert pages[0].has_more is True
    assert pages[0].next_cursor is not None
    assert pages[-1].has_more is False
    assert pages[-1].next_cursor is None
    assert sum(page.examined_count for page in pages) == 10
    assert sum(page.deleted_or_missing_count for page in pages) == 10
    assert [key for _, _, key in expected] == recorder.deleted
    cursors = [page.next_cursor for page in pages[:-1]]
    assert len({(cursor.capsule_id, cursor.blob_id) for cursor in cursors}) == len(cursors)


def test_failed_key_is_retried_on_the_next_sweep(session_factory, tmp_path: Path):
    store = LocalFileBlobStore(tmp_path / "blobs")
    recorder = _RecordingStore(store)
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        aborted = _add_draft(session, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        aborted.state = CapsuleState.ABORTED
        blobs = _blobs(session, aborted.id)
        first = blobs[0]
        first.state = CapsuleBlobState.STORED
        _put(store, first, b"retry-me")
        session.commit()
        first_key = first.object_key
    recorder.fail_keys.add(first_key)
    pages = _sweep(session_factory, recorder, limit=2)
    assert sum(page.failed_count for page in pages) == 1
    assert sum(page.examined_count for page in pages) == 5
    assert pages[-1].has_more is False
    assert _exists(store, first_key)
    recorder.fail_keys.clear()
    retry_pages = _sweep(session_factory, recorder, limit=2)
    assert sum(page.examined_count for page in retry_pages) == 5
    assert sum(page.failed_count for page in retry_pages) == 0
    assert not _exists(store, first_key)


def test_missing_object_counts_as_success_and_failures_are_redacted(
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
        first_snap = _blob_snapshot(session, aborted.id)
        recorder.missing_keys.add(third.object_key)
        recorder.fail_keys.add(first.object_key)
        secret = first.object_key
        result = _clean(session_factory, recorder, limit=10)
        assert result.examined_count == 5
        assert result.deleted_or_missing_count == 4
        assert result.failed_count == 1
        assert result.has_more is False
        assert _exists(store, first.object_key)
        assert not _exists(store, second.object_key)
        assert secret not in repr(result)
        assert "secret-store" not in repr(result)
        assert _blob_snapshot(session, aborted.id) == first_snap


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
        result = _clean(session_factory, recorder, limit=10)
        assert result.failed_count == 2
        assert result.deleted_or_missing_count == 3
        leaked = repr(result) + str(result)
        assert blobs[0].object_key not in leaked
        assert "secret-invalid" not in leaked
        assert "secret-boom" not in leaked


def test_attached_stale_identity_map_skips_after_committed_unabort(
    session_factory, tmp_path: Path, monkeypatch
):
    store = LocalFileBlobStore(tmp_path / "blobs")
    recorder = _RecordingStore(store)
    with session_factory() as setup:
        sender, sender_bundle = _seed_user(setup, "sender")
        recipient, recipient_bundle = _seed_user(setup, "recipient")
        aborted = _add_draft(setup, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        other = _add_draft(setup, sender=sender, sender_bundle=sender_bundle, recipient=recipient, recipient_bundle=recipient_bundle)
        aborted.state = CapsuleState.ABORTED
        other.state = CapsuleState.ABORTED
        aborted_blob = _blobs(setup, aborted.id)[0]
        other_blob = _blobs(setup, other.id)[0]
        aborted_blob.state = CapsuleBlobState.STORED
        other_blob.state = CapsuleBlobState.STORED
        _put(store, aborted_blob, b"aborted-body")
        _put(store, other_blob, b"other-body")
        setup.commit()
        aborted_id = aborted.id
        aborted_blob_id = aborted_blob.id
        other_id = other.id
        other_blob_id = other_blob.id
        aborted_key = aborted_blob.object_key
        other_key = other_blob.object_key

    original = AbortedObjectCleanupService._candidate_blob_ids

    def load_then_unabort(self, session, *, limit, after_cursor):
        attached = session.get(Capsule, aborted_id)
        assert object_session(attached) is session
        assert attached.state is CapsuleState.ABORTED
        discovered = original(self, session, limit=limit, after_cursor=after_cursor)
        with session_factory() as writer:
            row = writer.get(Capsule, aborted_id)
            row.state = CapsuleState.DRAFT
            writer.commit()
        assert attached.state is CapsuleState.ABORTED
        return discovered

    monkeypatch.setattr(AbortedObjectCleanupService, "_candidate_blob_ids", load_then_unabort)
    result = _clean(session_factory, recorder, limit=10)
    assert result.examined_count == 10
    assert result.skipped_count == 5
    assert result.deleted_or_missing_count == 5
    assert aborted_key not in recorder.deleted
    assert other_key in recorder.deleted
    assert _exists(store, aborted_key)
    assert not _exists(store, other_key)


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
        barrier.wait(timeout=10)
        result = _clean(session_factory, store, limit=10)
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


def test_cleanup_versus_stale_finalize_stays_aborted(session_factory, tmp_path: Path):
    from tink import tink_config

    from test_capsule_finalize_service import _ready_world

    tink_config.register()
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        capsule_id = world["capsule"].id
        sender_id = world["sender"].id
        CapsuleAbortService(session).abort(
            authenticated_sender_user_id=sender_id,
            capsule_id=capsule_id,
            now=_NOW,
        )
        session.commit()
        keys = [blob.object_key for blob in _blobs(session, capsule_id)]
    recorder = _RecordingStore(world["store"])
    barrier = Barrier(2)

    def cleanup_worker():
        barrier.wait(timeout=10)
        return _clean(session_factory, recorder, limit=10)

    def finalize_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                CapsuleFinalizeService(session, world["store"]).finalize(
                    authenticated_sender_user_id=sender_id,
                    capsule_id=capsule_id,
                    statement=world["statement"],
                    signature=world["signature"],
                    sender_key_bundle_id=world["sender_bundle"].id,
                    envelope=world["envelope"],
                    now=_NOW,
                )
                session.commit()
                return ("ready", True)
            except CapsuleFinalizeError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        cleanup_future = executor.submit(cleanup_worker)
        finalize_future = executor.submit(finalize_worker)
        cleanup_result = cleanup_future.result(timeout=20)
        finalize_outcome = finalize_future.result(timeout=20)

    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        assert capsule.state is CapsuleState.ABORTED
        assert finalize_outcome == ("error", "CAPSULE_STATE_INVALID")
        assert cleanup_result.examined_count == 5
        assert cleanup_result.skipped_count == 0
        assert cleanup_result.deleted_or_missing_count == 5
        assert set(recorder.deleted) == set(keys)
