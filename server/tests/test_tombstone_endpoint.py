"""Focused HTTP contract tests for the recipient tombstone feed."""

from __future__ import annotations

import inspect
from datetime import datetime, timezone
from uuid import uuid4

from fastapi.testclient import TestClient

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.capsules import TombstoneItemResponse, TombstonesResponse, list_tombstones
from remanence.api.dependencies import get_db_session
from remanence.capsules.tombstone_query_service import TombstoneQueryService
from remanence.main import create_app
from remanence.settings import AppMode, Settings

from test_capsule_revoke_endpoint import _assert_problem, _make_ready, _register, _revoke


def test_route_and_dtos_expose_only_redacted_tombstone_fields() -> None:
    assert set(TombstoneItemResponse.model_fields) == {"capsule_id", "revoked_at"}
    assert set(TombstonesResponse.model_fields) == {"items", "has_more", "next_cursor"}
    source = inspect.getsource(list_tombstones)
    assert "TombstoneQueryService" in source
    assert "get_blob_store" not in source
    assert "get_ciphertext_stager" not in source


def test_revoke_then_feed_returns_recipient_only_items_and_cursor_pages(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email=f"sender-{uuid4().hex}@example.com", handle=f"sender{uuid4().hex[:8]}")
    recipient = _register(client, email=f"recipient-{uuid4().hex}@example.com", handle=f"recipient{uuid4().hex[:8]}")
    first_id = _make_ready(client, factory, sender, recipient, ready_at=datetime.now(timezone.utc))
    second_id = _make_ready(client, factory, sender, recipient, ready_at=datetime.now(timezone.utc))

    first = _revoke(client, sender["access_token"], first_id)
    second = _revoke(client, sender["access_token"], second_id)
    assert first.status_code == second.status_code == 200

    page = client.get(
        "/v1/incoming/tombstones?limit=1",
        headers={"Authorization": f"Bearer {recipient['access_token']}"},
    )
    assert page.status_code == 200, page.text
    body = page.json()
    assert set(body) == {"items", "has_more", "next_cursor"}
    assert len(body["items"]) == 1
    assert set(body["items"][0]) == {"capsule_id", "revoked_at"}
    assert body["items"][0]["capsule_id"] == first_id
    assert body["has_more"] is True
    assert isinstance(body["next_cursor"], str)
    assert "sender_user_id" not in page.text
    assert "recipient_user_id" not in page.text
    assert "payload" not in page.text

    continuation = client.get(
        f"/v1/incoming/tombstones?since={body['next_cursor']}&limit=10",
        headers={"Authorization": f"Bearer {recipient['access_token']}"},
    )
    assert continuation.status_code == 200, continuation.text
    assert [item["capsule_id"] for item in continuation.json()["items"]] == [second_id]
    assert continuation.json()["has_more"] is False


def test_tombstone_feed_is_other_recipient_isolated_and_query_is_strict(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email=f"sender-{uuid4().hex}@example.com", handle=f"sender{uuid4().hex[:8]}")
    recipient = _register(client, email=f"recipient-{uuid4().hex}@example.com", handle=f"recipient{uuid4().hex[:8]}")
    other = _register(client, email=f"other-{uuid4().hex}@example.com", handle=f"other{uuid4().hex[:8]}")
    capsule_id = _make_ready(client, factory, sender, recipient, ready_at=datetime.now(timezone.utc))
    assert _revoke(client, sender["access_token"], capsule_id).status_code == 200

    isolated = client.get(
        "/v1/incoming/tombstones",
        headers={"Authorization": f"Bearer {other['access_token']}"},
    )
    assert isolated.status_code == 200
    assert isolated.json() == {"items": [], "has_more": False, "next_cursor": None}

    for query in (
        "?cursor=not-allowed",
        "?since=",
        "?limit=0",
        "?limit=101",
        "?limit=1&limit=2",
        "?unknown=1",
        "?limit",
    ):
        response = client.get(
            f"/v1/incoming/tombstones{query}",
            headers={"Authorization": f"Bearer {recipient['access_token']}"},
        )
        _assert_problem(response, status=422, code="VALIDATION_FAILED")


def test_tombstone_feed_internal_failure_is_redacted(client_factory, monkeypatch) -> None:
    client, _factory = client_factory
    recipient = _register(client, email=f"recipient-{uuid4().hex}@example.com", handle=f"recipient{uuid4().hex[:8]}")

    def fail_query(self, **_kwargs):
        raise RuntimeError("secret tombstone query failure")

    monkeypatch.setattr(TombstoneQueryService, "list_tombstones", fail_query)
    response = client.get(
        "/v1/incoming/tombstones",
        headers={"Authorization": f"Bearer {recipient['access_token']}"},
    )
    _assert_problem(response, status=500, code="INTERNAL_ERROR")
    assert "secret tombstone query failure" not in response.text
    assert "traceback" not in response.text.lower()


def test_unauthenticated_tombstone_request_fails_before_database() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_db_session] = lambda: object()
    client = TestClient(app)
    response = client.get("/v1/incoming/tombstones")
    _assert_problem(response, status=401, code="AUTH_INVALID")
