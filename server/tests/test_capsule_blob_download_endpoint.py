"""Tests for GET /v1/capsules/{capsule_id}/blobs/{blob_id}."""

from __future__ import annotations

import hashlib
from contextlib import contextmanager
from io import BytesIO
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.capsules import (
    _CACHE_CONTROL_PRIVATE_NO_STORE,
    _OwnedBlobBody,
    _ciphertext_etag,
    iter_ready_blob_chunks,
)
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind
from remanence.capsules.models import CapsuleState
from remanence.capsules.recipient_blob_query_service import (
    RecipientBlobQueryError,
    RecipientBlobQueryService,
    RecipientBlobSnapshot,
)
from remanence.main import create_app
from remanence.settings import AppMode, Settings
from remanence.storage import (
    BlobInfo,
    BlobIntegrityError,
    BlobNotFoundError,
    BlobStoreError,
    InvalidBlobKeyError,
    LocalFileBlobStore,
)
from remanence.users.key_models import UserKeyBundle
from remanence.users.models import User

from test_capsule_abort_service import _NOW, _add_draft
from test_capsule_draft_endpoint import _assert_problem, _register
from test_incoming_query_service import _add_incoming_ready
from test_recipient_blob_query_service import _blobs


_OBJECT_KEY_CANARY = "secret-object-key"
_KIND_BODY = {
    CapsuleBlobKind.RECOGNITION_MANIFEST: b"recognition",
    CapsuleBlobKind.CONTENT_MANIFEST: b"content",
    0: b"photo-0",
    1: b"photo-1",
    2: b"photo-2",
}


def _digest(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


def _snapshot(*, object_key: str, payload: bytes, capsule_id: UUID | None = None, blob_id: UUID | None = None):
    return RecipientBlobSnapshot(
        capsule_id=capsule_id or uuid4(),
        blob_id=blob_id or uuid4(),
        kind=CapsuleBlobKind.PHOTO,
        ordinal=0,
        object_key=object_key,
        expected_ciphertext_size=len(payload),
        expected_ciphertext_sha256=_digest(payload),
    )


class _ScriptedReader:
    def __init__(self, script: list[object]) -> None:
        self.script = list(script)
        self.closed = False

    def read(self, n: int) -> object:
        if self.closed:
            raise AssertionError("read after close")
        if not self.script:
            return b""
        item = self.script.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item

    def close(self) -> None:
        self.closed = True


class _TrackingCM:
    def __init__(self, reader: _ScriptedReader, *, enter_error: BaseException | None = None) -> None:
        self.reader = reader
        self.enter_error = enter_error
        self.entered = False
        self.exited = False

    def __enter__(self) -> _ScriptedReader:
        if self.enter_error is not None:
            raise self.enter_error
        self.entered = True
        return self.reader

    def __exit__(self, exc_type, exc, tb) -> bool:
        self.exited = True
        try:
            self.reader.close()
        except Exception:
            pass
        return False


class _RecordingStore:
    def __init__(
        self,
        *,
        payload: bytes,
        object_key: str,
        size: int | None = None,
        sha256_hex: str | None = None,
        stat_error: BaseException | None = None,
        open_error: BaseException | None = None,
        open_enter_error: BaseException | None = None,
    ) -> None:
        self.payload = payload
        self.object_key = object_key
        self.size = len(payload) if size is None else size
        self.sha256_hex = _digest(payload).hex() if sha256_hex is None else sha256_hex
        self.stat_error = stat_error
        self.open_error = open_error
        self.open_enter_error = open_enter_error
        self.stat_keys: list[str] = []
        self.open_keys: list[str] = []
        self.contexts: list[_TrackingCM] = []

    def stat(self, key: str) -> BlobInfo:
        self.stat_keys.append(key)
        if self.stat_error is not None:
            raise self.stat_error
        return BlobInfo(key=key, size=self.size, sha256_hex=self.sha256_hex)

    def open_reader(self, key: str):
        self.open_keys.append(key)
        if self.open_error is not None:
            raise self.open_error
        reader = _ScriptedReader([self.payload, b""])
        context = _TrackingCM(reader, enter_error=self.open_enter_error)
        self.contexts.append(context)
        return context


def _session_begin(rolled: dict | None = None):
    class _Session:
        def begin(self):
            @contextmanager
            def _begin():
                try:
                    yield self
                except Exception:
                    if rolled is not None:
                        rolled["value"] = True
                    raise

            return _begin()

    return _Session()


def _mocked_app(*, user_id: UUID, session, blob_store) -> TestClient:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=user_id, session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: session
    app.state.blob_store = blob_store
    return TestClient(app)


def _get(client: TestClient, token: str, capsule_id, blob_id, extra_headers: dict | None = None):
    headers = {"Authorization": f"Bearer {token}"}
    if extra_headers:
        headers.update(extra_headers)
    return client.get(f"/v1/capsules/{capsule_id}/blobs/{blob_id}", headers=headers)


def test_iter_ready_blob_chunks_bounds_and_closes() -> None:
    payload = b"abcdefghij"
    reader = _ScriptedReader([b"abc", b"def", b"ghij", b""])
    chunks = list(iter_ready_blob_chunks(reader, expected_size=len(payload), chunk_size=3))
    assert b"".join(chunks) == payload
    assert sum(len(chunk) for chunk in chunks) == len(payload)
    assert reader.closed is True


def test_iter_ready_blob_chunks_short_extra_and_read_errors_close() -> None:
    short = _ScriptedReader([b"ab", b""])
    with pytest.raises(BlobIntegrityError):
        list(iter_ready_blob_chunks(short, expected_size=4, chunk_size=4))
    assert short.closed is True

    extra = _ScriptedReader([b"abcd", b"x"])
    with pytest.raises(BlobIntegrityError):
        list(iter_ready_blob_chunks(extra, expected_size=4, chunk_size=4))
    assert extra.closed is True

    oversized = _ScriptedReader([b"abcde"])
    yielded: list[bytes] = []
    with pytest.raises(BlobIntegrityError):
        for chunk in iter_ready_blob_chunks(oversized, expected_size=4, chunk_size=4):
            yielded.append(chunk)
    assert yielded == []
    assert oversized.closed is True

    exploding = _ScriptedReader([OSError("secret-read-fail")])
    with pytest.raises(BlobStoreError) as caught:
        list(iter_ready_blob_chunks(exploding, expected_size=4, chunk_size=4))
    assert exploding.closed is True
    assert "secret-read-fail" not in str(caught.value)
    assert str(caught.value) == "blob read failed"

    for sentinel in (None, bytearray(b""), "", b"x"):
        reader = _ScriptedReader([b"ab", sentinel])
        with pytest.raises(BlobIntegrityError):
            list(iter_ready_blob_chunks(reader, expected_size=2, chunk_size=2))
        assert reader.closed is True


def test_owned_blob_body_closes_reader_and_context() -> None:
    payload = b"ab"
    reader = _ScriptedReader([payload, b""])
    context = _TrackingCM(reader)
    body = _OwnedBlobBody(context, reader, expected_size=2)
    assert b"".join(body) == payload
    assert reader.closed is True
    assert context.exited is True

    short_reader = _ScriptedReader([b"a", b""])
    short_context = _TrackingCM(short_reader)
    failing = _OwnedBlobBody(short_context, short_reader, expected_size=2)
    with pytest.raises(BlobIntegrityError):
        list(failing)
    assert short_reader.closed is True
    assert short_context.exited is True

    cancelled_reader = _ScriptedReader([payload, b""])
    cancelled_context = _TrackingCM(cancelled_reader)
    cancelled = _OwnedBlobBody(cancelled_context, cancelled_reader, expected_size=2)
    cancelled.close()
    assert cancelled_reader.closed is True
    assert cancelled_context.exited is True


def test_auth_precedes_path_and_range_without_transaction() -> None:
    missing_auth = create_app(settings=Settings(mode=AppMode.TEST))
    missing_auth.dependency_overrides[get_db_session] = lambda: object()
    unauth = TestClient(missing_auth)
    missing = unauth.get(f"/v1/capsules/NOT-A-UUID/blobs/{uuid4()}")
    _assert_problem(missing, status=401, code="AUTH_INVALID")
    assert missing.headers["www-authenticate"] == "Bearer"
    basic = unauth.get(
        f"/v1/capsules/{uuid4()}/blobs/{uuid4()}",
        headers={"Authorization": "Basic abc"},
    )
    _assert_problem(basic, status=401, code="AUTH_INVALID")

    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )

    class _Session:
        def begin(self):
            raise AssertionError("invalid path or range must not open a transaction")

    app.dependency_overrides[get_db_session] = lambda: _Session()
    app.state.blob_store = object()
    client = TestClient(app)
    for bad in ("NOT-A-UUID", str(uuid4()).upper(), "{" + str(uuid4()) + "}"):
        response = client.get(f"/v1/capsules/{bad}/blobs/{uuid4()}")
        _assert_problem(response, status=422, code="VALIDATION_FAILED")
        other = client.get(f"/v1/capsules/{uuid4()}/blobs/{bad}")
        _assert_problem(other, status=422, code="VALIDATION_FAILED")
    ranged = client.get(
        f"/v1/capsules/{uuid4()}/blobs/{uuid4()}",
        headers={"Range": "bytes=0-1"},
    )
    _assert_problem(ranged, status=422, code="VALIDATION_FAILED")
    content_range = client.get(
        f"/v1/capsules/{uuid4()}/blobs/{uuid4()}",
        headers={"Content-Range": "bytes 0-1/2"},
    )
    _assert_problem(content_range, status=422, code="VALIDATION_FAILED")


def test_download_uses_snapshot_object_key_and_returns_exact_headers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    payload = b"photo-ciphertext"
    object_key = f"{_OBJECT_KEY_CANARY}/internal.blob"
    snapshot = _snapshot(object_key=object_key, payload=payload)
    reconstructed = f"capsules/{snapshot.capsule_id}/{snapshot.blob_id}.blob"
    store = _RecordingStore(payload=payload, object_key=object_key)

    def fake_get(self, **kwargs):
        return snapshot

    monkeypatch.setattr(RecipientBlobQueryService, "get_ready_blob", fake_get)
    client = _mocked_app(user_id=uuid4(), session=_session_begin(), blob_store=store)
    response = client.get(f"/v1/capsules/{snapshot.capsule_id}/blobs/{snapshot.blob_id}")
    assert response.status_code == 200, response.text
    assert response.content == payload
    assert response.headers["content-type"].startswith("application/octet-stream")
    assert response.headers["content-length"] == str(len(payload))
    assert response.headers["etag"] == _ciphertext_etag(_digest(payload))
    assert response.headers["etag"] == f'"{_digest(payload).hex()}"'
    assert response.headers["cache-control"] == _CACHE_CONTROL_PRIVATE_NO_STORE
    assert "content-disposition" not in {key.lower() for key in response.headers}
    assert store.stat_keys == [object_key]
    assert store.open_keys == [object_key]
    assert reconstructed not in store.stat_keys
    assert reconstructed not in store.open_keys
    assert _OBJECT_KEY_CANARY not in response.text
    assert object_key not in response.text
    assert "Content-Disposition" not in response.headers
    assert store.contexts[0].entered is True
    assert store.contexts[0].exited is True


def test_missing_store_stat_mismatch_invalid_key_and_not_found(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    payload = b"body"
    snapshot = _snapshot(object_key=_OBJECT_KEY_CANARY, payload=payload)
    monkeypatch.setattr(
        RecipientBlobQueryService,
        "get_ready_blob",
        lambda self, **kwargs: snapshot,
    )
    user_id = uuid4()
    path = f"/v1/capsules/{snapshot.capsule_id}/blobs/{snapshot.blob_id}"

    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=user_id, session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: _session_begin()
    missing_store = TestClient(app).get(path)
    _assert_problem(missing_store, status=503, code="INTERNAL_ERROR")
    assert missing_store.json()["retryable"] is True
    assert _OBJECT_KEY_CANARY not in missing_store.text

    invalid = _RecordingStore(
        payload=payload,
        object_key=_OBJECT_KEY_CANARY,
        stat_error=InvalidBlobKeyError(f"bad:{_OBJECT_KEY_CANARY}"),
    )
    invalid_response = _mocked_app(user_id=user_id, session=_session_begin(), blob_store=invalid).get(path)
    _assert_problem(invalid_response, status=500, code="INTERNAL_ERROR")
    assert _OBJECT_KEY_CANARY not in invalid_response.text
    assert "bad:" not in invalid_response.text

    missing_blob = _RecordingStore(
        payload=payload,
        object_key=_OBJECT_KEY_CANARY,
        stat_error=BlobNotFoundError(_OBJECT_KEY_CANARY),
    )
    missing_response = _mocked_app(user_id=user_id, session=_session_begin(), blob_store=missing_blob).get(path)
    _assert_problem(missing_response, status=503, code="INTERNAL_ERROR")
    assert missing_response.json()["retryable"] is True
    assert _OBJECT_KEY_CANARY not in missing_response.text

    size_mismatch = _RecordingStore(payload=payload, object_key=_OBJECT_KEY_CANARY, size=len(payload) + 1)
    size_response = _mocked_app(user_id=user_id, session=_session_begin(), blob_store=size_mismatch).get(path)
    _assert_problem(size_response, status=500, code="INTERNAL_ERROR")
    assert size_mismatch.open_keys == []

    hash_mismatch = _RecordingStore(
        payload=payload,
        object_key=_OBJECT_KEY_CANARY,
        sha256_hex=_digest(b"other").hex(),
    )
    hash_response = _mocked_app(user_id=user_id, session=_session_begin(), blob_store=hash_mismatch).get(path)
    _assert_problem(hash_response, status=500, code="INTERNAL_ERROR")
    assert hash_mismatch.open_keys == []
    assert _OBJECT_KEY_CANARY not in hash_response.text


def test_stat_success_open_reader_failure_is_redacted_problem(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    payload = b"body"
    snapshot = _snapshot(object_key=_OBJECT_KEY_CANARY, payload=payload)
    monkeypatch.setattr(
        RecipientBlobQueryService,
        "get_ready_blob",
        lambda self, **kwargs: snapshot,
    )
    user_id = uuid4()
    path = f"/v1/capsules/{snapshot.capsule_id}/blobs/{snapshot.blob_id}"

    missing = _RecordingStore(
        payload=payload,
        object_key=_OBJECT_KEY_CANARY,
        open_error=BlobNotFoundError(_OBJECT_KEY_CANARY),
    )
    missing_response = _mocked_app(user_id=user_id, session=_session_begin(), blob_store=missing).get(path)
    _assert_problem(missing_response, status=503, code="INTERNAL_ERROR")
    assert missing_response.json()["retryable"] is True
    assert missing.stat_keys == [_OBJECT_KEY_CANARY]
    assert missing.open_keys == [_OBJECT_KEY_CANARY]
    assert missing_response.status_code != 200
    assert _OBJECT_KEY_CANARY not in missing_response.text

    invalid = _RecordingStore(
        payload=payload,
        object_key=_OBJECT_KEY_CANARY,
        open_enter_error=InvalidBlobKeyError(f"bad:{_OBJECT_KEY_CANARY}"),
    )
    invalid_response = _mocked_app(user_id=user_id, session=_session_begin(), blob_store=invalid).get(path)
    _assert_problem(invalid_response, status=500, code="INTERNAL_ERROR")
    assert invalid.stat_keys == [_OBJECT_KEY_CANARY]
    assert invalid.open_keys == [_OBJECT_KEY_CANARY]
    assert invalid.contexts[0].entered is False
    assert _OBJECT_KEY_CANARY not in invalid_response.text
    assert "bad:" not in invalid_response.text

    io_error = _RecordingStore(
        payload=payload,
        object_key=_OBJECT_KEY_CANARY,
        open_error=BlobStoreError("secret-open-io"),
    )
    io_response = _mocked_app(user_id=user_id, session=_session_begin(), blob_store=io_error).get(path)
    _assert_problem(io_response, status=503, code="INTERNAL_ERROR")
    assert io_response.json()["retryable"] is True
    assert io_error.open_keys == [_OBJECT_KEY_CANARY]
    assert "secret-open-io" not in io_response.text
    assert _OBJECT_KEY_CANARY not in io_response.text


def test_service_errors_map_through_problem_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_get(self, **kwargs):
        raise RecipientBlobQueryError("CAPSULE_NOT_FOUND")

    monkeypatch.setattr(RecipientBlobQueryService, "get_ready_blob", fake_get)
    client = _mocked_app(user_id=uuid4(), session=_session_begin(), blob_store=_RecordingStore(payload=b"x", object_key="k"))
    response = client.get(f"/v1/capsules/{uuid4()}/blobs/{uuid4()}")
    _assert_problem(response, status=404, code="CAPSULE_NOT_FOUND")

    def fake_blob(self, **kwargs):
        raise RecipientBlobQueryError("BLOB_NOT_DECLARED")

    monkeypatch.setattr(RecipientBlobQueryService, "get_ready_blob", fake_blob)
    blob = _mocked_app(user_id=uuid4(), session=_session_begin(), blob_store=_RecordingStore(payload=b"x", object_key="k"))
    declared = blob.get(f"/v1/capsules/{uuid4()}/blobs/{uuid4()}")
    _assert_problem(declared, status=404, code="BLOB_NOT_DECLARED")


def _put_blob(store: LocalFileBlobStore, blob: CapsuleBlob) -> bytes:
    body = _KIND_BODY[blob.kind if blob.ordinal is None else blob.ordinal]
    store.put(
        blob.object_key,
        BytesIO(body),
        expected_size=len(body),
        expected_sha256=_digest(body).hex(),
    )
    return body


def test_live_recipient_manifest_and_photo_exact_bytes(client_factory, tmp_path: Path) -> None:
    client, factory = client_factory
    sender_reg = _register(client, email=f"snd-{uuid4().hex[:8]}@example.com", handle=f"snd{uuid4().hex[:8]}")
    recipient_reg = _register(client, email=f"rec-{uuid4().hex[:8]}@example.com", handle=f"rec{uuid4().hex[:8]}")
    other_reg = _register(client, email=f"oth-{uuid4().hex[:8]}@example.com", handle=f"oth{uuid4().hex[:8]}")
    store = LocalFileBlobStore(tmp_path / "blobs")
    client.app.state.blob_store = store
    with factory() as session:
        sender = session.get(User, UUID(sender_reg["user"]["user_id"]))
        sender_bundle = session.get(UserKeyBundle, UUID(sender_reg["active_key_bundle_id"]))
        recipient = session.get(User, UUID(recipient_reg["user"]["user_id"]))
        recipient_bundle = session.get(UserKeyBundle, UUID(recipient_reg["active_key_bundle_id"]))
        other = session.get(User, UUID(other_reg["user"]["user_id"]))
        other_bundle = session.get(UserKeyBundle, UUID(other_reg["active_key_bundle_id"]))
        capsule = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        other_capsule = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=other,
            recipient_bundle=other_bundle,
            ready_at=_NOW,
        )
        draft = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            with_idempotency=False,
        )
        aborted = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            with_idempotency=False,
        )
        aborted.state = CapsuleState.ABORTED
        session.commit()
        blobs = _blobs(session, capsule.id)
        recognition = next(blob for blob in blobs if blob.kind is CapsuleBlobKind.RECOGNITION_MANIFEST)
        photo = next(blob for blob in blobs if blob.kind is CapsuleBlobKind.PHOTO and blob.ordinal == 0)
        other_blob = _blobs(session, other_capsule.id)[0]
        draft_blob = _blobs(session, draft.id)[0]
        aborted_blob = _blobs(session, aborted.id)[0]
        rec_body = _put_blob(store, recognition)
        photo_body = _put_blob(store, photo)
        capsule_id = capsule.id
        draft_id = draft.id
        aborted_id = aborted.id
        recognition_id = recognition.id
        photo_id = photo.id
        other_blob_id = other_blob.id
        draft_blob_id = draft_blob.id
        aborted_blob_id = aborted_blob.id
        rec_key = recognition.object_key
        photo_key = photo.object_key

    rec = _get(client, recipient_reg["access_token"], capsule_id, recognition_id)
    assert rec.status_code == 200, rec.text
    assert rec.content == rec_body
    assert rec.headers["content-length"] == str(len(rec_body))
    assert rec.headers["etag"] == f'"{_digest(rec_body).hex()}"'
    assert rec.headers["cache-control"] == "private, no-store"
    assert rec.headers["content-type"].startswith("application/octet-stream")
    assert "content-disposition" not in {key.lower() for key in rec.headers}
    assert rec_key not in rec.text
    assert rec_key not in rec.headers.get("etag", "")

    photo_resp = _get(client, recipient_reg["access_token"], capsule_id, photo_id)
    assert photo_resp.status_code == 200, photo_resp.text
    assert photo_resp.content == photo_body
    assert photo_resp.headers["etag"] == f'"{_digest(photo_body).hex()}"'
    assert photo_key not in photo_resp.text

    sender_resp = _get(client, sender_reg["access_token"], capsule_id, recognition_id)
    _assert_problem(sender_resp, status=404, code="CAPSULE_NOT_FOUND")
    other_resp = _get(client, other_reg["access_token"], capsule_id, recognition_id)
    _assert_problem(other_resp, status=404, code="CAPSULE_NOT_FOUND")
    draft_resp = _get(client, recipient_reg["access_token"], draft_id, draft_blob_id)
    _assert_problem(draft_resp, status=404, code="CAPSULE_NOT_FOUND")
    aborted_resp = _get(client, recipient_reg["access_token"], aborted_id, aborted_blob_id)
    _assert_problem(aborted_resp, status=404, code="CAPSULE_NOT_FOUND")
    unknown = _get(client, recipient_reg["access_token"], capsule_id, uuid4())
    _assert_problem(unknown, status=404, code="BLOB_NOT_DECLARED")
    cross = _get(client, recipient_reg["access_token"], capsule_id, other_blob_id)
    _assert_problem(cross, status=404, code="BLOB_NOT_DECLARED")
    ranged = _get(
        client,
        recipient_reg["access_token"],
        capsule_id,
        recognition_id,
        extra_headers={"Range": "bytes=0-1"},
    )
    _assert_problem(ranged, status=422, code="VALIDATION_FAILED")
    leaked = "".join(
        response.text
        for response in (sender_resp, other_resp, draft_resp, aborted_resp, unknown, cross, ranged)
    )
    assert rec_key not in leaked
    assert photo_key not in leaked
