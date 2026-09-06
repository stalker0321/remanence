"""Tests for POST /v1/capsules/{capsule_id}/revoke."""

from __future__ import annotations

import hashlib
import inspect
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

from fastapi.testclient import TestClient
from sqlalchemy import select, update

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.capsules import CapsuleRevokeResponse, revoke_capsule
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.capsules.blob_models import CapsuleBlob
from remanence.capsules.delivery_models import RecipientDeliveryState
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.revoke_service import CapsuleRevokeResult, CapsuleRevokeService
from remanence.main import create_app
from remanence.settings import AppMode, Settings

from test_capsule_draft_endpoint import _assert_problem, _draft_payload, _post, _raw, _register


def _make_ready(client: TestClient, factory, sender: dict, recipient: dict, *, ready_at: datetime) -> str:
    created = _post(client, sender["access_token"], _raw(_draft_payload(sender, recipient)))
    assert created.status_code == 201, created.text
    capsule_id = created.json()["capsule_id"]
    statement = b"signed-statement"
    with factory() as session:
        session.execute(
            update(Capsule)
            .where(Capsule.id == UUID(capsule_id))
            .values(
                state=CapsuleState.READY,
                signed_statement=statement,
                signed_statement_sha256=hashlib.sha256(statement).digest(),
                publish_signature=b"\x01" * 69,
                ready_at=ready_at,
            )
        )
        session.commit()
    return capsule_id


def _revoke(client: TestClient, token: str, capsule_id: str, *, content=None):
    return client.post(
        f"/v1/capsules/{capsule_id}/revoke",
        content=content,
        headers={"Authorization": f"Bearer {token}"},
    )


def test_route_and_response_model_are_bounded() -> None:
    source = inspect.getsource(revoke_capsule)
    assert "CapsuleRevokeService" in source
    assert "session.begin()" in source
    assert "get_blob_store" not in source
    assert "get_ciphertext_stager" not in source
    assert set(CapsuleRevokeResponse.model_fields) == {"capsule_id", "state", "is_replay"}


def test_revoke_returns_200_with_replay_indicator_and_preserves_rows(client_factory):
    client, factory = client_factory
    sender = _register(client, email=f"sender-{uuid4().hex}@example.com", handle=f"sender{uuid4().hex[:8]}")
    recipient = _register(client, email=f"recipient-{uuid4().hex}@example.com", handle=f"recipient{uuid4().hex[:8]}")
    capsule_id = _make_ready(client, factory, sender, recipient, ready_at=datetime.now(timezone.utc))

    first = _revoke(client, sender["access_token"], capsule_id)
    assert first.status_code == 200, first.text
    assert first.json() == {"capsule_id": capsule_id, "state": "REVOKED", "is_replay": False}
    replay = _revoke(client, sender["access_token"], capsule_id)
    assert replay.status_code == 200, replay.text
    assert replay.json() == {"capsule_id": capsule_id, "state": "REVOKED", "is_replay": True}

    with factory() as session:
        capsule = session.get(Capsule, UUID(capsule_id))
        assert capsule is not None and capsule.state is CapsuleState.REVOKED
        assert session.scalar(select(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule.id)) is not None
        assert session.get(CapsuleEnvelope, capsule.id) is None
        assert session.get(RecipientDeliveryState, (UUID(recipient["user"]["user_id"]), capsule.id)) is None


def test_window_state_and_ownership_errors_are_uniform(client_factory):
    client, factory = client_factory
    sender = _register(client, email=f"sender-{uuid4().hex}@example.com", handle=f"sender{uuid4().hex[:8]}")
    recipient = _register(client, email=f"recipient-{uuid4().hex}@example.com", handle=f"recipient{uuid4().hex[:8]}")
    other = _register(client, email=f"other-{uuid4().hex}@example.com", handle=f"other{uuid4().hex[:8]}")
    expired_id = _make_ready(
        client,
        factory,
        sender,
        recipient,
        ready_at=datetime.now(timezone.utc) - timedelta(days=1, seconds=1),
    )
    expired = _revoke(client, sender["access_token"], expired_id)
    _assert_problem(expired, status=409, code="WINDOW_EXPIRED")
    with factory() as session:
        assert session.get(Capsule, UUID(expired_id)).state is CapsuleState.READY

    foreign_id = _make_ready(
        client, factory, sender, recipient, ready_at=datetime.now(timezone.utc)
    )
    foreign = _revoke(client, other["access_token"], foreign_id)
    unknown = _revoke(client, sender["access_token"], str(uuid4()))
    _assert_problem(foreign, status=404, code="CAPSULE_NOT_FOUND")
    _assert_problem(unknown, status=404, code="CAPSULE_NOT_FOUND")
    assert foreign.json()["code"] == unknown.json()["code"]
    assert foreign_id not in foreign.text


def test_unauthenticated_and_malformed_paths_fail_without_database() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_db_session] = lambda: object()
    client = TestClient(app)
    missing = client.post(f"/v1/capsules/{uuid4()}/revoke")
    _assert_problem(missing, status=401, code="AUTH_INVALID")
    malformed = client.post("/v1/capsules/NOT-A-UUID/revoke")
    _assert_problem(malformed, status=401, code="AUTH_INVALID")


def test_unexpected_revoke_failure_is_redacted(client_factory, monkeypatch):
    client, _factory = client_factory
    sender = _register(client, email=f"sender-{uuid4().hex}@example.com", handle=f"sender{uuid4().hex[:8]}")

    def fail_revoke(self, **_kwargs):
        raise RuntimeError("secret revoke failure")

    monkeypatch.setattr(CapsuleRevokeService, "revoke", fail_revoke)
    response = _revoke(client, sender["access_token"], str(uuid4()), content=b"secret-body")
    _assert_problem(response, status=500, code="INTERNAL_ERROR")
    assert "secret revoke failure" not in response.text
    assert "secret-body" not in response.text


def test_mocked_success_serializes_exact_response(monkeypatch) -> None:
    capsule_id = uuid4()
    captured: list[dict] = []

    def fake_revoke(self, **kwargs):
        captured.append(kwargs)
        return CapsuleRevokeResult(
            capsule_id=kwargs["capsule_id"],
            state=CapsuleState.REVOKED,
            is_replay=False,
        )

    class _Session:
        def begin(self):
            class _Begin:
                def __enter__(self):
                    return self

                def __exit__(self, *_args):
                    return False

            return _Begin()

    monkeypatch.setattr(CapsuleRevokeService, "revoke", fake_revoke)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: _Session()
    response = TestClient(app).post(f"/v1/capsules/{capsule_id}/revoke")
    assert response.status_code == 200
    assert response.json() == {
        "capsule_id": str(capsule_id),
        "state": "REVOKED",
        "is_replay": False,
    }
    assert captured[0]["capsule_id"] == capsule_id
