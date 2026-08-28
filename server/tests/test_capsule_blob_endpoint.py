import base64
import hashlib
import inspect
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr
from sqlalchemy import select, update
from sqlalchemy.exc import SQLAlchemyError

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.capsules import (
    _canonical_path_uuid,
    _parse_upload_headers,
    upload_capsule_blob,
)
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobState
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.limits import LIMITS_V1
from remanence.main import create_app
from remanence.settings import AppMode, Settings
from remanence.storage import BlobInfo, BlobStoreError, CiphertextStager, LocalFileBlobStore
from remanence.capsules.schemas import CapsuleDraftValidationError

from test_capsule_draft_endpoint import (
    _assert_problem,
    _draft_payload,
    _post,
    _raw,
    _register,
)


def _sha256(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _wire_storage(client: TestClient, root: Path) -> LocalFileBlobStore:
    store = LocalFileBlobStore(root / "blobs")
    client.app.state.blob_store = store
    client.app.state.ciphertext_stager = CiphertextStager(root / "staging")
    return store


def _prepare(client: TestClient, root: Path) -> tuple[dict, dict, dict, bytes, LocalFileBlobStore]:
    sender = _register(client, email="alice@example.com", handle="alice")
    recipient = _register(client, email="bob@example.com", handle="bob")
    payload = b"upload-ciphertext"
    draft = _draft_payload(sender, recipient)
    draft["blobs"][0]["ciphertext_size"] = len(payload)
    draft["blobs"][0]["ciphertext_sha256"] = _b64(_sha256(payload))
    created = _post(client, sender["access_token"], _raw(draft))
    assert created.status_code == 201, created.text
    store = _wire_storage(client, root)
    return sender, recipient, draft, payload, store


def _upload_headers(
    payload: bytes,
    *,
    length: str | None = None,
    digest: bytes | None = None,
    idempotency_key: str | None = None,
    content_type: str = "application/octet-stream",
) -> dict[str, str]:
    return {
        "Content-Type": content_type,
        "Content-Length": str(len(payload)) if length is None else length,
        "X-Remanence-Ciphertext-SHA256": _b64(_sha256(payload) if digest is None else digest),
        "Idempotency-Key": idempotency_key or str(uuid4()),
    }


def _upload(client: TestClient, sender: dict, draft: dict, payload: bytes, **kwargs: Any):
    blob_id = draft["blobs"][0]["blob_id"]
    return client.put(
        f"/v1/capsules/{draft['capsule_id']}/blobs/{blob_id}",
        content=payload,
        headers={"Authorization": f"Bearer {sender['access_token']}", **_upload_headers(payload, **kwargs)},
    )


def _temp_files(root: Path) -> list[Path]:
    return list(root.glob(".remanence-staging-*") if root.exists() else [])


def _header_fixture(payload: bytes = b"body") -> list[tuple[bytes, bytes]]:
    return [
        (b"content-type", b"application/octet-stream"),
        (b"content-length", str(len(payload)).encode("ascii")),
        (b"x-remanence-ciphertext-sha256", _b64(_sha256(payload)).encode("ascii")),
        (b"idempotency-key", str(uuid4()).encode("ascii")),
    ]


def test_upload_header_parser_requires_exact_single_headers_and_canonical_values() -> None:
    headers = _header_fixture()
    parsed = _parse_upload_headers(headers)
    assert parsed.expected_size == 4
    assert parsed.expected_sha256_hex == _sha256(b"body").hex()

    for required in (b"content-type", b"content-length", b"x-remanence-ciphertext-sha256", b"idempotency-key"):
        missing = [item for item in headers if item[0] != required]
        with pytest.raises(CapsuleDraftValidationError) as caught:
            _parse_upload_headers(missing)
        assert str(caught.value) == "invalid capsule draft request"

    for required in (b"content-type", b"content-length", b"x-remanence-ciphertext-sha256", b"idempotency-key"):
        duplicate = headers + [(required, next(value for name, value in headers if name == required))]
        with pytest.raises(CapsuleDraftValidationError):
            _parse_upload_headers(duplicate)

    assert _parse_upload_headers(
        [(name, b" application/octet-stream " if name == b"content-type" else value) for name, value in headers]
    ).expected_size == 4
    for value in (b"0", b"01", b"+4", str(LIMITS_V1.encrypted_photo_max_ciphertext_bytes + 1).encode()):
        bad = [(name, value if name == b"content-length" else header_value) for name, header_value in headers]
        with pytest.raises(CapsuleDraftValidationError):
            _parse_upload_headers(bad)

    for value in (b"not-base64", (_b64(_sha256(b"body")) + "=").encode("ascii"), _b64(b"short").encode("ascii")):
        bad = [(name, value if name == b"x-remanence-ciphertext-sha256" else header_value) for name, header_value in headers]
        with pytest.raises(CapsuleDraftValidationError):
            _parse_upload_headers(bad)
    uppercase_uuid = str(uuid4()).upper().encode("ascii")
    bad = [(name, uppercase_uuid if name == b"idempotency-key" else header_value) for name, header_value in headers]
    with pytest.raises(CapsuleDraftValidationError):
        _parse_upload_headers(bad)
    for forbidden in (b"content-encoding", b"transfer-encoding", b"range", b"content-range"):
        with pytest.raises(CapsuleDraftValidationError):
            _parse_upload_headers(headers + [(forbidden, b"chunked")])


def test_path_uuid_parser_rejects_alternate_spellings() -> None:
    value = UUID("00112233-4455-6677-8899-aabbccddeeff")
    assert _canonical_path_uuid(str(value)) == value
    for alternate in (str(value).upper(), "{" + str(value) + "}", "not-a-uuid"):
        with pytest.raises(CapsuleDraftValidationError):
            _canonical_path_uuid(alternate)


def test_upload_missing_storage_wiring_fails_closed() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(uuid4(), uuid4())
    app.dependency_overrides[get_db_session] = lambda: None
    with TestClient(app) as client:
        response = client.put(
            f"/v1/capsules/{uuid4()}/blobs/{uuid4()}",
            content=b"body",
            headers=_upload_headers(b"body"),
        )
    _assert_problem(response, status=503, code="INTERNAL_ERROR")


def test_upload_authentication_and_transport_rejections(client_factory) -> None:
    client, _factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    payload = b"body"
    path = f"/v1/capsules/{uuid4()}/blobs/{uuid4()}"

    unauthenticated = client.put(path, content=payload, headers=_upload_headers(payload))
    _assert_problem(unauthenticated, status=401, code="AUTH_INVALID")

    for content_type in ("application/octet-stream; charset=utf-8", "text/plain"):
        response = client.put(
            path,
            content=payload,
            headers={"Authorization": f"Bearer {sender['access_token']}", **_upload_headers(payload, content_type=content_type)},
        )
        _assert_problem(response, status=422, code="VALIDATION_FAILED")

    for forbidden in ("Content-Encoding", "Transfer-Encoding"):
        headers = {"Authorization": f"Bearer {sender['access_token']}", **_upload_headers(payload), forbidden: "chunked"}
        response = client.put(path, content=payload, headers=headers)
        _assert_problem(response, status=422, code="VALIDATION_FAILED")


def test_upload_body_stream_bounds_and_hash_fail_before_db_service(client_factory, tmp_path: Path) -> None:
    client, _factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    _wire_storage(client, tmp_path)
    path = f"/v1/capsules/{uuid4()}/blobs/{uuid4()}"

    too_long = client.put(
        path,
        content=b"xx",
        headers={"Authorization": f"Bearer {sender['access_token']}", **_upload_headers(b"xx", length="1")},
    )
    _assert_problem(too_long, status=422, code="BLOB_SIZE_INVALID")
    truncated = client.put(
        path,
        content=b"x",
        headers={"Authorization": f"Bearer {sender['access_token']}", **_upload_headers(b"x", length="2")},
    )
    _assert_problem(truncated, status=422, code="BLOB_SIZE_INVALID")
    hash_bad = client.put(
        path,
        content=b"x",
        headers={"Authorization": f"Bearer {sender['access_token']}", **_upload_headers(b"x", digest=_sha256(b"y"))},
    )
    _assert_problem(hash_bad, status=422, code="BLOB_HASH_MISMATCH")
    assert _temp_files(tmp_path / "staging") == []


def test_success_returns_exact_empty_204_and_replay_does_not_rewrite(client_factory, tmp_path: Path) -> None:
    client, factory = client_factory
    sender, _recipient, draft, payload, store = _prepare(client, tmp_path)
    blob_id = UUID(draft["blobs"][0]["blob_id"])
    first = _upload(client, sender, draft, payload)
    assert first.status_code == 204
    assert first.content == b""
    assert "content-type" not in first.headers
    assert _temp_files(tmp_path / "staging") == []

    replay = _upload(client, sender, draft, payload)
    assert replay.status_code == 204
    assert replay.content == b""
    with factory() as session:
        blob = session.get(CapsuleBlob, blob_id)
        assert blob is not None
        assert blob.state is CapsuleBlobState.STORED
        assert session.scalar(select(Capsule).where(Capsule.id == UUID(draft["capsule_id"]))).state is CapsuleState.DRAFT
    with store.open_reader(f"capsules/{draft['capsule_id']}/{blob_id}.blob") as reader:
        assert reader.read() == payload


def test_upload_conflict_unrelated_sender_and_state_errors(client_factory, tmp_path: Path) -> None:
    client, factory = client_factory
    sender, _recipient, draft, payload, store = _prepare(client, tmp_path)
    other = _register(client, email="carol@example.com", handle="carol")
    object_key = f"capsules/{draft['capsule_id']}/{draft['blobs'][0]['blob_id']}.blob"
    different = b"different-upload"
    store.put(object_key, BytesIO(different), expected_size=len(different), expected_sha256=_sha256(different).hex())
    conflict = _upload(client, sender, draft, payload)
    _assert_problem(conflict, status=409, code="BLOB_CONFLICT")

    unrelated = _upload(client, other, draft, payload)
    _assert_problem(unrelated, status=404, code="CAPSULE_NOT_FOUND")

    with factory() as session:
        session.execute(update(Capsule).where(Capsule.id == UUID(draft["capsule_id"])).values(state=CapsuleState.ABORTED))
        session.commit()
    state_invalid = _upload(client, sender, draft, payload)
    _assert_problem(state_invalid, status=409, code="CAPSULE_STATE_INVALID")


def test_upload_expired_draft_and_missing_blob_are_redacted(client_factory, tmp_path: Path) -> None:
    client, factory = client_factory
    sender, _recipient, draft, payload, _store = _prepare(client, tmp_path)
    missing = client.put(
        f"/v1/capsules/{draft['capsule_id']}/blobs/{uuid4()}",
        content=payload,
        headers={"Authorization": f"Bearer {sender['access_token']}", **_upload_headers(payload)},
    )
    _assert_problem(missing, status=404, code="BLOB_NOT_DECLARED")
    with factory() as session:
        session.execute(
            update(Capsule)
            .where(Capsule.id == UUID(draft["capsule_id"]))
            .values(created_at=datetime.now(timezone.utc) - timedelta(days=2), draft_expires_at=datetime.now(timezone.utc) - timedelta(seconds=1))
        )
        session.commit()
    expired = _upload(client, sender, draft, payload)
    _assert_problem(expired, status=409, code="DRAFT_EXPIRED")


def test_upload_storage_failure_and_db_failure_keep_problem_redacted(client_factory, tmp_path: Path) -> None:
    client, factory = client_factory
    sender, _recipient, draft, payload, store = _prepare(client, tmp_path)

    class FailingStore:
        def open_reader(self, key: str):
            return store.open_reader(key)

        def put(self, *args: Any, **kwargs: Any):
            raise BlobStoreError("private storage error")

        def stat(self, key: str) -> BlobInfo:
            return store.stat(key)

    client.app.state.blob_store = FailingStore()
    failure = _upload(client, sender, draft, payload)
    _assert_problem(failure, status=500, code="INTERNAL_ERROR")
    assert "private storage error" not in failure.text
    assert _temp_files(tmp_path / "staging") == []

    client.app.state.blob_store = store
    original_factory = client.app.state.session_factory

    def failing_factory():
        session = original_factory()
        original_execute = session.execute

        def fail_update(statement, *args: Any, **kwargs: Any):
            if getattr(statement, "is_update", False):
                raise SQLAlchemyError("private database error")
            return original_execute(statement, *args, **kwargs)

        session.execute = fail_update
        return session

    client.app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=UUID(sender["user"]["user_id"]), session_id=uuid4()
    )
    client.app.state.session_factory = failing_factory
    db_failure = _upload(client, sender, draft, payload)
    _assert_problem(db_failure, status=500, code="INTERNAL_ERROR")
    client.app.state.session_factory = original_factory
    with factory() as session:
        blob = session.get(CapsuleBlob, UUID(draft["blobs"][0]["blob_id"]))
        assert blob is not None
        assert blob.state is CapsuleBlobState.DECLARED
    with store.open_reader(f"capsules/{draft['capsule_id']}/{draft['blobs'][0]['blob_id']}.blob") as reader:
        assert reader.read() == payload
    assert _temp_files(tmp_path / "staging") == []


def test_upload_uses_stream_not_request_body_and_app_wires_dev_storage(tmp_path: Path) -> None:
    source = inspect.getsource(upload_capsule_blob)
    assert "request.body" not in source
    assert "request.stream" in source

    settings = Settings(
        mode=AppMode.DEV,
        database_url=SecretStr("postgresql+psycopg://user:pass@localhost/db"),
        blob_root=tmp_path / "configured-root",
    )
    app = create_app(settings=settings, session_factory=lambda: None)
    with TestClient(app):
        assert isinstance(app.state.blob_store, LocalFileBlobStore)
        assert isinstance(app.state.ciphertext_stager, CiphertextStager)
        assert app.state.ciphertext_stager._staging_root == settings.blob_root / ".staging"  # noqa: SLF001
