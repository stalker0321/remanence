import asyncio
import hashlib
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path
from threading import Barrier
from typing import Any
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select, text, update
from sqlalchemy.exc import SQLAlchemyError

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.locking import blob_promotion_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.promotion_service import (
    CapsuleBlobPromotionError,
    CapsuleBlobPromotionResult,
    CapsuleBlobPromotionService,
)
from remanence.storage import (
    BlobInfo,
    BlobStoreError,
    CiphertextStager,
    LocalFileBlobStore,
    StagedBlob,
)
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


_NOW = datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone.utc)


def _digest(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


async def _chunks(payload: bytes):
    yield payload
    yield b""


def _stage(root: Path, payload: bytes) -> StagedBlob:
    return asyncio.run(
        CiphertextStager(root).stage(
            _chunks(payload),
            expected_size=len(payload),
            expected_sha256=_digest(payload).hex(),
            max_bytes=len(payload),
        )
    )


def _temp_files(root: Path) -> list[Path]:
    return list(root.glob(".remanence-staging-*") if root.exists() else [])


def _new_user(session, label: str) -> tuple[User, UserKeyBundle]:
    suffix = uuid4().hex
    user = User(
        id=uuid4(),
        email_normalized=f"{label}-{suffix}@example.com",
        handle_normalized=f"{label}{suffix[:20]}",
        handle_display=f"{label}{suffix[:20]}",
    )
    session.add(user)
    session.flush()
    bundle = UserKeyBundle(
        id=uuid4(),
        user_id=user.id,
        encryption_public_keyset=b"encryption-public",
        signing_public_keyset=b"signing-public",
        suite="test-suite",
        protocol_version=1,
        status=KeyBundleStatus.ACTIVE,
    )
    session.add(bundle)
    session.flush()
    return user, bundle


def _add_draft(
    session,
    sender: User,
    sender_bundle: UserKeyBundle,
    payload: bytes,
    *,
    capsule_id: UUID | None = None,
    blob_id: UUID | None = None,
    state: CapsuleState = CapsuleState.DRAFT,
) -> tuple[Capsule, CapsuleBlob]:
    capsule_id = capsule_id or uuid4()
    blob_id = blob_id or uuid4()
    is_ready = state is CapsuleState.READY
    capsule = Capsule(
        id=capsule_id,
        sender_user_id=sender.id,
        recipient_user_id=sender.id,
        sender_key_bundle_id=sender_bundle.id,
        recipient_key_bundle_id=sender_bundle.id,
        protocol_version=1,
        state=state,
        signed_statement=b"signed-statement" if is_ready else None,
        signed_statement_sha256=_digest(b"signed-statement") if is_ready else None,
        publish_signature=b"p" * 69 if is_ready else None,
        created_at=_NOW,
        ready_at=_NOW if is_ready else None,
        draft_expires_at=_NOW + timedelta(days=7),
    )
    session.add(capsule)
    session.flush()
    blob = CapsuleBlob(
        id=blob_id,
        capsule_id=capsule.id,
        kind=CapsuleBlobKind.PHOTO,
        ordinal=0,
        object_key=f"capsules/{capsule.id}/{blob_id}.blob",
        expected_ciphertext_size=len(payload),
        expected_ciphertext_sha256=_digest(payload),
        state=CapsuleBlobState.DECLARED,
    )
    session.add(blob)
    session.flush()
    return capsule, blob


def _seed_draft(session, payload: bytes = b"declared-ciphertext") -> tuple[Capsule, CapsuleBlob, User]:
    sender, bundle = _new_user(session, "sender")
    capsule, blob = _add_draft(session, sender, bundle, payload)
    session.commit()
    return capsule, blob, sender


def _promote(
    session,
    store,
    *,
    sender_id: UUID,
    capsule_id: UUID,
    blob_id: UUID,
    staged: StagedBlob,
    now: datetime = _NOW,
) -> CapsuleBlobPromotionResult:
    return CapsuleBlobPromotionService(session, store).promote_blob(
        authenticated_sender_user_id=sender_id,
        capsule_id=capsule_id,
        blob_id=blob_id,
        staged_blob=staged,
        now=now,
    )


def _assert_error(call, code: str) -> CapsuleBlobPromotionError:
    with pytest.raises(CapsuleBlobPromotionError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "capsule blob promotion failed"
    assert repr(caught.value) == f"CapsuleBlobPromotionError(code={code!r})"
    return caught.value


class _CountingBlobStore(LocalFileBlobStore):
    def __init__(self, root: Path) -> None:
        super().__init__(root)
        self.put_calls = 0
        self.stat_calls = 0

    def put(self, *args: Any, **kwargs: Any) -> BlobInfo:
        self.put_calls += 1
        return super().put(*args, **kwargs)

    def stat(self, key: str) -> BlobInfo:
        self.stat_calls += 1
        return super().stat(key)


class _FailingPutStore:
    def __init__(self, delegate: LocalFileBlobStore) -> None:
        self._delegate = delegate

    @contextmanager
    def open_reader(self, key: str):
        with self._delegate.open_reader(key) as reader:
            yield reader

    def put(self, *args: Any, **kwargs: Any) -> BlobInfo:
        raise BlobStoreError("private storage failure")

    def stat(self, key: str) -> BlobInfo:
        return self._delegate.stat(key)


def test_lock_key_is_signed_and_domain_separated() -> None:
    blob_id = UUID("12345678-1234-5678-9abc-def012345678")
    key = blob_promotion_lock_key(blob_id)
    assert key == -7272179167192994983
    assert -2**63 <= key < 2**63
    assert key != blob_promotion_lock_key(uuid4())


def test_invalid_service_input_is_redacted_and_staged_blob_is_cleaned(tmp_path: Path) -> None:
    payload = b"private-ciphertext"
    staged = _stage(tmp_path / "staging", payload)
    error = _assert_error(
        lambda: CapsuleBlobPromotionService(None, None).promote_blob(  # type: ignore[arg-type]
            authenticated_sender_user_id="not-a-uuid",
            capsule_id=uuid4(),
            blob_id=uuid4(),
            staged_blob=staged,
            now=_NOW,
        ),
        "VALIDATION_FAILED",
    )
    assert "private-ciphertext" not in f"{error!s} {error!r}"
    assert str(tmp_path) not in f"{error!s} {error!r}"
    assert _temp_files(tmp_path / "staging") == []


def test_success_persists_stored_state_and_cleans_staging(session_factory, tmp_path: Path) -> None:
    payload = b"declared-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        store = _CountingBlobStore(tmp_path / "blobs")
        staged = _stage(tmp_path / "staging", payload)

        result = _promote(
            session,
            store,
            sender_id=sender.id,
            capsule_id=capsule.id,
            blob_id=blob.id,
            staged=staged,
        )

        assert result == CapsuleBlobPromotionResult(
            capsule_id=capsule.id,
            blob_id=blob.id,
            state=CapsuleBlobState.STORED,
            is_replay=False,
        )
        public_result = f"{result!s} {result!r}"
        assert str(capsule.id) not in public_result
        assert str(blob.id) not in public_result
        assert blob.object_key not in public_result
        assert _digest(payload).hex() not in public_result
        assert store.put_calls == 1
        assert _temp_files(tmp_path / "staging") == []
        session.commit()
        persisted = session.scalar(
            select(CapsuleBlob)
            .where(CapsuleBlob.id == blob.id)
            .execution_options(populate_existing=True)
        )
        assert persisted is not None
        assert persisted.state is CapsuleBlobState.STORED
        with store.open_reader(blob.object_key) as reader:
            assert reader.read() == payload


def test_wrong_sender_is_indistinguishable_from_missing_capsule(session_factory, tmp_path: Path) -> None:
    payload = b"private-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        other, _ = _new_user(session, "other")
        session.commit()
        store = _CountingBlobStore(tmp_path / "blobs")
        staged = _stage(tmp_path / "staging", payload)
        error = _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=other.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            "CAPSULE_NOT_FOUND",
        )
        assert str(capsule.id) not in f"{error!s} {error!r}"
        assert store.put_calls == 0
        assert _temp_files(tmp_path / "staging") == []


@pytest.mark.parametrize("state", [CapsuleState.ABORTED, CapsuleState.READY])
def test_non_draft_capsule_is_rejected_without_storage(session_factory, tmp_path: Path, state: CapsuleState) -> None:
    payload = b"private-ciphertext"
    with session_factory() as session:
        sender, bundle = _new_user(session, "sender")
        capsule, blob = _add_draft(session, sender, bundle, payload, state=state)
        session.commit()
        store = _CountingBlobStore(tmp_path / "blobs")
        staged = _stage(tmp_path / "staging", payload)
        _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            "CAPSULE_STATE_INVALID",
        )
        assert store.put_calls == 0
        assert _temp_files(tmp_path / "staging") == []


def test_expired_draft_is_rejected_without_storage(session_factory, tmp_path: Path) -> None:
    payload = b"private-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        session.execute(
            update(Capsule)
            .where(Capsule.id == capsule.id)
            .values(
                created_at=_NOW - timedelta(days=2),
                draft_expires_at=_NOW - timedelta(seconds=1),
            )
        )
        session.commit()
        store = _CountingBlobStore(tmp_path / "blobs")
        staged = _stage(tmp_path / "staging", payload)
        _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            "DRAFT_EXPIRED",
        )
        assert store.put_calls == 0
        assert _temp_files(tmp_path / "staging") == []


def test_missing_blob_and_wrong_capsule_are_not_storage_operations(session_factory, tmp_path: Path) -> None:
    payload = b"private-ciphertext"
    with session_factory() as session:
        sender, bundle = _new_user(session, "sender")
        capsule, blob = _add_draft(session, sender, bundle, payload)
        other_capsule, _ = _add_draft(session, sender, bundle, payload)
        session.commit()
        store = _CountingBlobStore(tmp_path / "blobs")

        missing_staged = _stage(tmp_path / "staging-missing", payload)
        _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=uuid4(),
                staged=missing_staged,
            ),
            "BLOB_NOT_DECLARED",
        )
        wrong_capsule_staged = _stage(tmp_path / "staging-wrong", payload)
        _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=other_capsule.id,
                blob_id=blob.id,
                staged=wrong_capsule_staged,
            ),
            "BLOB_NOT_DECLARED",
        )
        assert store.put_calls == 0
        assert _temp_files(tmp_path / "staging-missing") == []
        assert _temp_files(tmp_path / "staging-wrong") == []


@pytest.mark.parametrize(
    ("payload", "expected_code"),
    [
        (b"short", "BLOB_SIZE_INVALID"),
        (b"declared-ciphertexu", "BLOB_HASH_MISMATCH"),
    ],
)
def test_staged_metadata_mismatch_prevents_put(session_factory, tmp_path: Path, payload: bytes, expected_code: str) -> None:
    declared = b"declared-ciphertext"
    assert len(payload) != len(declared) or _digest(payload) != _digest(declared)
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, declared)
        store = _CountingBlobStore(tmp_path / "blobs")
        staged = _stage(tmp_path / "staging", payload)
        _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            expected_code,
        )
        assert store.put_calls == 0
        assert _temp_files(tmp_path / "staging") == []


def test_same_stored_blob_reconciles_without_rewriting(session_factory, tmp_path: Path) -> None:
    payload = b"declared-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        store = _CountingBlobStore(tmp_path / "blobs")
        first = _stage(tmp_path / "staging-first", payload)
        _promote(
            session,
            store,
            sender_id=sender.id,
            capsule_id=capsule.id,
            blob_id=blob.id,
            staged=first,
        )
        session.commit()
        second = _stage(tmp_path / "staging-second", payload)
        replay = _promote(
            session,
            store,
            sender_id=sender.id,
            capsule_id=capsule.id,
            blob_id=blob.id,
            staged=second,
        )
        assert replay.is_replay is True
        assert replay.state is CapsuleBlobState.STORED
        assert store.put_calls == 1
        assert store.stat_calls == 1
        assert _temp_files(tmp_path / "staging-second") == []


def test_declared_blob_conflicts_with_different_existing_object(session_factory, tmp_path: Path) -> None:
    payload = b"declared-ciphertext"
    other = b"different-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        store = _CountingBlobStore(tmp_path / "blobs")
        store.put(blob.object_key, BytesIO(other), expected_size=len(other), expected_sha256=_digest(other).hex())
        staged = _stage(tmp_path / "staging", payload)
        _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            "BLOB_CONFLICT",
        )
        assert store.put_calls == 2
        assert session.scalar(
            select(CapsuleBlob.state).where(CapsuleBlob.id == blob.id)
        ) is CapsuleBlobState.DECLARED
        assert _temp_files(tmp_path / "staging") == []


def test_stored_blob_with_different_object_fails_reconciliation_without_put(session_factory, tmp_path: Path) -> None:
    payload = b"declared-ciphertext"
    other = b"different-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        store = _CountingBlobStore(tmp_path / "blobs")
        store.put(blob.object_key, BytesIO(other), expected_size=len(other), expected_sha256=_digest(other).hex())
        session.execute(update(CapsuleBlob).where(CapsuleBlob.id == blob.id).values(state=CapsuleBlobState.STORED))
        session.commit()
        staged = _stage(tmp_path / "staging", payload)
        _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            "BLOB_CONFLICT",
        )
        assert store.put_calls == 1
        assert store.stat_calls == 1
        assert _temp_files(tmp_path / "staging") == []


def test_db_rollback_after_promotion_leaves_final_object_for_replay(session_factory, tmp_path: Path) -> None:
    payload = b"declared-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        store = _CountingBlobStore(tmp_path / "blobs")
        first = _stage(tmp_path / "staging-first", payload)
        _promote(
            session,
            store,
            sender_id=sender.id,
            capsule_id=capsule.id,
            blob_id=blob.id,
            staged=first,
        )
        session.rollback()
        assert session.scalar(
            select(CapsuleBlob.state)
            .where(CapsuleBlob.id == blob.id)
            .execution_options(populate_existing=True)
        ) is CapsuleBlobState.DECLARED
        with store.open_reader(blob.object_key) as reader:
            assert reader.read() == payload

        second = _stage(tmp_path / "staging-second", payload)
        replay = _promote(
            session,
            store,
            sender_id=sender.id,
            capsule_id=capsule.id,
            blob_id=blob.id,
            staged=second,
        )
        assert replay.is_replay is False
        assert store.put_calls == 2
        session.commit()
        assert session.scalar(select(CapsuleBlob.state).where(CapsuleBlob.id == blob.id)) is CapsuleBlobState.STORED


def test_blob_store_failure_is_redacted_and_does_not_change_db(session_factory, tmp_path: Path) -> None:
    payload = b"declared-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        delegate = LocalFileBlobStore(tmp_path / "blobs")
        staged = _stage(tmp_path / "staging", payload)
        error = _assert_error(
            lambda: _promote(
                session,
                _FailingPutStore(delegate),
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            "STORAGE_IO",
        )
        assert "private storage failure" not in f"{error!s} {error!r}"
        assert session.scalar(select(CapsuleBlob.state).where(CapsuleBlob.id == blob.id)) is CapsuleBlobState.DECLARED
        assert _temp_files(tmp_path / "staging") == []


def test_db_update_failure_keeps_promoted_object_and_requires_rollback(
    session_factory,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    payload = b"declared-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        store = LocalFileBlobStore(tmp_path / "blobs")
        original_execute = session.execute

        def fail_update(statement, *args: Any, **kwargs: Any):
            if getattr(statement, "is_update", False):
                raise SQLAlchemyError("private database detail")
            return original_execute(statement, *args, **kwargs)

        monkeypatch.setattr(session, "execute", fail_update)
        staged = _stage(tmp_path / "staging", payload)
        error = _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            "INTERNAL_ERROR",
        )
        assert "private database detail" not in f"{error!s} {error!r}"
        session.rollback()
        with store.open_reader(blob.object_key) as reader:
            assert reader.read() == payload
        assert _temp_files(tmp_path / "staging") == []


def test_db_flush_failure_keeps_promoted_object_and_state_rolls_back(
    session_factory,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    payload = b"declared-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        store = LocalFileBlobStore(tmp_path / "blobs")
        original_flush = session.flush

        def fail_flush(*args: Any, **kwargs: Any) -> None:
            raise SQLAlchemyError("private flush detail")

        monkeypatch.setattr(session, "flush", fail_flush)
        staged = _stage(tmp_path / "staging", payload)
        error = _assert_error(
            lambda: _promote(
                session,
                store,
                sender_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                staged=staged,
            ),
            "INTERNAL_ERROR",
        )
        assert "private flush detail" not in f"{error!s} {error!r}"
        monkeypatch.setattr(session, "flush", original_flush)
        session.rollback()
        with store.open_reader(blob.object_key) as reader:
            assert reader.read() == payload
        assert session.scalar(
            select(CapsuleBlob.state)
            .where(CapsuleBlob.id == blob.id)
            .execution_options(populate_existing=True)
        ) is CapsuleBlobState.DECLARED
        assert _temp_files(tmp_path / "staging") == []


def test_concurrent_same_blob_has_one_promotion_and_one_reconciliation(session_factory, tmp_path: Path) -> None:
    payload = b"declared-ciphertext"
    with session_factory() as session:
        capsule, blob, sender = _seed_draft(session, payload)
        session.commit()
        store = LocalFileBlobStore(tmp_path / "blobs")
        staged = [_stage(tmp_path / f"staging-{index}", payload) for index in range(2)]
        barrier = Barrier(2)

        def worker(handle: StagedBlob):
            with session_factory() as worker_session:
                worker_session.execute(text("SET LOCAL lock_timeout = '5s'"))
                barrier.wait(timeout=10)
                try:
                    result = _promote(
                        worker_session,
                        store,
                        sender_id=sender.id,
                        capsule_id=capsule.id,
                        blob_id=blob.id,
                        staged=handle,
                    )
                    worker_session.commit()
                    return ("result", result.is_replay)
                except CapsuleBlobPromotionError as error:
                    worker_session.rollback()
                    return ("error", error.code)

        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(worker, handle) for handle in staged]
            outcomes = [future.result(timeout=20) for future in futures]
        assert sorted(outcomes) == [("result", False), ("result", True)]
        with session_factory() as verify:
            assert verify.scalar(select(func.count()).select_from(CapsuleBlob).where(CapsuleBlob.id == blob.id)) == 1
            assert verify.scalar(
                select(CapsuleBlob.state).where(CapsuleBlob.id == blob.id)
            ) is CapsuleBlobState.STORED
        assert all(_temp_files(tmp_path / f"staging-{index}") == [] for index in range(2))
