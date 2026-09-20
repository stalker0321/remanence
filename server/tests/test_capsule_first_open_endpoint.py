"""Tests for POST /v1/capsules/{capsule_id}/first-open."""

from __future__ import annotations

import inspect
from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi.testclient import TestClient

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.capsules import CapsuleFirstOpenResponse, first_open_capsule
from remanence.api.dependencies import get_authenticated_principal, get_db_session
from remanence.capsules.models import Capsule, CapsuleState
from remanence.main import create_app
from remanence.settings import AppMode, Settings

from test_capsule_draft_endpoint import _assert_problem, _register
from test_capsule_revoke_endpoint import _make_ready


def _first_open(client: TestClient, token: str, capsule_id: str):
    return client.post(
        f"/v1/capsules/{capsule_id}/first-open",
        headers={"Authorization": f"Bearer {token}"},
    )


def test_route_and_response_model_are_bounded() -> None:
    source = inspect.getsource(first_open_capsule)
    assert "CapsuleFirstOpenService" in source
    assert "session.begin()" in source
    assert set(CapsuleFirstOpenResponse.model_fields) == {
        "capsule_id",
        "state",
        "first_opened_at",
        "is_replay",
    }


def test_recipient_claims_once_and_replays_after_lost_response(client_factory):
    client, factory = client_factory
    sender = _register(client, email=f"sender-{uuid4().hex}@example.com", handle=f"sender{uuid4().hex[:8]}")
    recipient = _register(client, email=f"recipient-{uuid4().hex}@example.com", handle=f"recipient{uuid4().hex[:8]}")
    capsule_id = _make_ready(client, factory, sender, recipient, ready_at=datetime.now(timezone.utc))

    first = _first_open(client, recipient["access_token"], capsule_id)
    assert first.status_code == 200, first.text
    payload = first.json()
    assert payload["capsule_id"] == capsule_id
    assert payload["state"] == "OPENED"
    assert payload["is_replay"] is False
    assert payload["first_opened_at"].endswith("Z")

    replay = _first_open(client, recipient["access_token"], capsule_id)
    assert replay.status_code == 200, replay.text
    assert replay.json()["is_replay"] is True
    assert replay.json()["first_opened_at"] == payload["first_opened_at"]

    with factory() as session:
        row = session.get(Capsule, UUID(capsule_id))
        assert row is not None
        assert row.state is CapsuleState.READY
        assert row.first_opened_at is not None


def test_sender_cannot_claim_and_revoked_capsule_cannot_be_claimed(client_factory):
    client, factory = client_factory
    sender = _register(client, email=f"sender-{uuid4().hex}@example.com", handle=f"sender{uuid4().hex[:8]}")
    recipient = _register(client, email=f"recipient-{uuid4().hex}@example.com", handle=f"recipient{uuid4().hex[:8]}")
    foreign_id = _make_ready(client, factory, sender, recipient, ready_at=datetime.now(timezone.utc))
    foreign = _first_open(client, sender["access_token"], foreign_id)
    _assert_problem(foreign, status=404, code="CAPSULE_NOT_FOUND")

    revoked_id = _make_ready(client, factory, sender, recipient, ready_at=datetime.now(timezone.utc))
    revoked = client.post(
        f"/v1/capsules/{revoked_id}/revoke",
        headers={"Authorization": f"Bearer {sender['access_token']}"},
    )
    assert revoked.status_code == 200, revoked.text
    after = _first_open(client, recipient["access_token"], revoked_id)
    _assert_problem(after, status=409, code="CAPSULE_STATE_INVALID")


def test_unauthenticated_and_malformed_paths_fail_without_database() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_db_session] = lambda: object()
    client = TestClient(app)
    missing = client.post(f"/v1/capsules/{uuid4()}/first-open")
    _assert_problem(missing, status=401, code="AUTH_INVALID")
    malformed = client.post("/v1/capsules/NOT-A-UUID/first-open")
    _assert_problem(malformed, status=401, code="AUTH_INVALID")
