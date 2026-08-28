"""Tests for DELETE /v1/capsules/{capsule_id}."""

from __future__ import annotations

import hashlib
import inspect
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select, update
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.capsules import abort_capsule
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.capsules.abort_service import (
    CapsuleAbortError,
    CapsuleAbortResult,
    CapsuleAbortService,
)
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.capsules.models import Capsule, CapsuleState
from remanence.main import create_app
from remanence.settings import AppMode, Settings

from test_capsule_draft_endpoint import (
    _assert_problem,
    _draft_payload,
    _post,
    _raw,
    _register,
)


def _assert_empty_204(response) -> None:
    assert response.status_code == 204
    assert response.content == b""
    assert "content-type" not in response.headers
    assert UUID(response.headers["x-request-id"])
    assert "is_replay" not in response.text
    assert "ABORTED" not in response.text
    assert "DRAFT" not in response.text


def _delete(client: TestClient, token: str, capsule_id: str, extra_headers: dict | None = None):
    headers = {"Authorization": f"Bearer {token}"}
    if extra_headers:
        headers.update(extra_headers)
    return client.delete(f"/v1/capsules/{capsule_id}", headers=headers)


def _transport_client() -> TestClient:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )

    class _Session:
        def begin(self):
            raise AssertionError("invalid paths must not open a transaction")

    app.dependency_overrides[get_db_session] = lambda: _Session()
    return TestClient(app)


def test_abort_route_has_no_storage_dependency() -> None:
    source = inspect.getsource(abort_capsule)
    assert "blob_store" not in source
    assert "BlobStore" not in source
    assert "ciphertext_stager" not in source
    assert "get_blob_store" not in source
    assert "get_ciphertext_stager" not in source
    params = inspect.signature(abort_capsule).parameters
    assert "blob_store" not in params
    assert "ciphertext_stager" not in params
    assert "use_cache" in inspect.getsource(abort_capsule)


def test_malformed_noncanonical_and_unauthenticated_abort_do_not_need_database() -> None:
    client = _transport_client()
    canonical = str(uuid4())
    for bad in ("NOT-A-UUID", str(uuid4()).upper(), "{" + str(uuid4()) + "}"):
        response = client.delete(f"/v1/capsules/{bad}")
        _assert_problem(response, status=422, code="VALIDATION_FAILED")

    missing_auth = create_app(settings=Settings(mode=AppMode.TEST))
    missing_auth.dependency_overrides[get_db_session] = lambda: object()
    unauth = TestClient(missing_auth)
    missing = unauth.delete(f"/v1/capsules/{canonical}")
    _assert_problem(missing, status=401, code="AUTH_INVALID")
    assert missing.headers["www-authenticate"] == "Bearer"
    malformed = unauth.delete(
        f"/v1/capsules/{canonical}",
        headers={"Authorization": "Basic abc"},
    )
    _assert_problem(malformed, status=401, code="AUTH_INVALID")
    assert malformed.headers["www-authenticate"] == "Bearer"


def test_mocked_success_is_empty_204_without_idempotency_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[dict] = []

    def fake_abort(self, **kwargs):
        captured.append(kwargs)
        return CapsuleAbortResult(
            capsule_id=kwargs["capsule_id"],
            state=CapsuleState.ABORTED,
            is_replay=False,
        )

    class _Session:
        def begin(self):
            @contextmanager
            def _begin():
                yield self

            return _begin()

    monkeypatch.setattr(CapsuleAbortService, "abort", fake_abort)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: _Session()
    client = TestClient(app)
    capsule_id = str(uuid4())
    response = client.delete(
        f"/v1/capsules/{capsule_id}",
        headers={"Idempotency-Key": str(uuid4())},
    )
    _assert_empty_204(response)
    assert len(captured) == 1
    assert captured[0]["capsule_id"] == UUID(capsule_id)
    assert "idempotency_key" not in captured[0]


def test_mocked_service_failure_rolls_back_and_is_redacted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    rolled_back = {"value": False}

    def fake_abort(self, **kwargs):
        raise RuntimeError("secret abort failure")

    class _Session:
        def begin(self):
            @contextmanager
            def _begin():
                try:
                    yield self
                except Exception:
                    rolled_back["value"] = True
                    raise

            return _begin()

    monkeypatch.setattr(CapsuleAbortService, "abort", fake_abort)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: _Session()
    client = TestClient(app)
    response = client.delete(f"/v1/capsules/{uuid4()}")
    _assert_problem(response, status=500, code="INTERNAL_ERROR")
    assert rolled_back["value"] is True
    assert "secret abort failure" not in response.text


def test_draft_expired_replay_ready_missing_and_foreign(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    recipient = _register(client, email="bob@example.com", handle="bob")
    created = _post(client, sender["access_token"], _raw(_draft_payload(sender, recipient)))
    assert created.status_code == 201, created.text
    capsule_id = created.json()["capsule_id"]
    with factory() as session:
        before_idempotency = session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord))

    first = _delete(client, sender["access_token"], capsule_id)
    _assert_empty_204(first)
    with factory() as session:
        capsule = session.get(Capsule, UUID(capsule_id))
        assert capsule.state is CapsuleState.ABORTED
        assert capsule.ready_at is None
        assert session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord)) == before_idempotency
        assert session.scalar(
            select(func.count())
            .select_from(CapsuleIdempotencyRecord)
            .where(CapsuleIdempotencyRecord.method == "DELETE")
        ) == 0

    replay = _delete(
        client,
        sender["access_token"],
        capsule_id,
        extra_headers={"Idempotency-Key": str(uuid4())},
    )
    _assert_empty_204(replay)
    with factory() as session:
        assert session.get(Capsule, UUID(capsule_id)).state is CapsuleState.ABORTED
        assert session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord)) == before_idempotency

    expired_created = _post(client, sender["access_token"], _raw(_draft_payload(sender, recipient)))
    assert expired_created.status_code == 201, expired_created.text
    expired_id = expired_created.json()["capsule_id"]
    with factory() as session:
        session.execute(
            update(Capsule)
            .where(Capsule.id == UUID(expired_id))
            .values(
                created_at=datetime.now(timezone.utc) - timedelta(days=8),
                draft_expires_at=datetime.now(timezone.utc) - timedelta(seconds=1),
            )
        )
        session.commit()
    expired = _delete(client, sender["access_token"], expired_id)
    _assert_empty_204(expired)
    with factory() as session:
        assert session.get(Capsule, UUID(expired_id)).state is CapsuleState.ABORTED

    ready_created = _post(client, sender["access_token"], _raw(_draft_payload(sender, recipient)))
    assert ready_created.status_code == 201, ready_created.text
    ready_id = ready_created.json()["capsule_id"]
    statement = b"signed-statement"
    with factory() as session:
        ready = session.get(Capsule, UUID(ready_id))
        ready.state = CapsuleState.READY
        ready.signed_statement = statement
        ready.signed_statement_sha256 = hashlib.sha256(statement).digest()
        ready.publish_signature = b"\x01" * 69
        ready.ready_at = datetime.now(timezone.utc)
        session.commit()
    ready_delete = _delete(client, sender["access_token"], ready_id)
    _assert_problem(ready_delete, status=409, code="CAPSULE_STATE_INVALID")
    with factory() as session:
        still_ready = session.get(Capsule, UUID(ready_id))
        assert still_ready.state is CapsuleState.READY
        assert still_ready.signed_statement == statement

    other = _register(client, email="carol@example.com", handle="carol")
    foreign_created = _post(client, sender["access_token"], _raw(_draft_payload(sender, recipient)))
    assert foreign_created.status_code == 201, foreign_created.text
    foreign_id = foreign_created.json()["capsule_id"]
    missing_id = str(uuid4())
    foreign = _delete(client, other["access_token"], foreign_id)
    missing = _delete(client, sender["access_token"], missing_id)
    _assert_problem(foreign, status=404, code="CAPSULE_NOT_FOUND")
    _assert_problem(missing, status=404, code="CAPSULE_NOT_FOUND")
    assert foreign.json()["code"] == missing.json()["code"]
    assert foreign_id not in foreign.text
    assert missing_id not in missing.text
    with factory() as session:
        assert session.get(Capsule, UUID(foreign_id)).state is CapsuleState.DRAFT


def test_live_injected_failures_roll_back_and_stay_redacted(client_factory, monkeypatch):
    client, factory = client_factory
    sender = _register(client, email="dave@example.com", handle="dave")
    recipient = _register(client, email="erin@example.com", handle="erin")
    created = _post(client, sender["access_token"], _raw(_draft_payload(sender, recipient)))
    assert created.status_code == 201, created.text
    capsule_id = created.json()["capsule_id"]

    def fail_flush(self, *args, **kwargs):
        raise SQLAlchemyError("private abort flush")

    monkeypatch.setattr(Session, "flush", fail_flush)
    db_failed = _delete(client, sender["access_token"], capsule_id)
    monkeypatch.undo()
    _assert_problem(db_failed, status=500, code="INTERNAL_ERROR")
    assert "private abort flush" not in db_failed.text
    with factory() as session:
        assert session.get(Capsule, UUID(capsule_id)).state is CapsuleState.DRAFT

    def fail_abort(self, **kwargs):
        raise CapsuleAbortError("INTERNAL_ERROR")

    monkeypatch.setattr(CapsuleAbortService, "abort", fail_abort)
    service_failed = _delete(client, sender["access_token"], capsule_id)
    monkeypatch.undo()
    _assert_problem(service_failed, status=500, code="INTERNAL_ERROR")
    assert "capsule abort failed" not in service_failed.json()["detail"]
    with factory() as session:
        assert session.get(Capsule, UUID(capsule_id)).state is CapsuleState.DRAFT
