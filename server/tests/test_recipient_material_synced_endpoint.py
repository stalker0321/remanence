"""Tests for POST /v1/capsules/{capsule_id}/material-synced."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, update
from sqlalchemy.exc import DBAPIError, OperationalError

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.capsules import mark_capsule_material_synced
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.api.problems import PROBLEM_BODY_FIELDS, PROBLEM_CATALOG, problem_payload
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.recipient_material_synced_service import (
    RecipientMaterialSyncedResult,
    RecipientMaterialSyncedService,
)
from remanence.main import create_app
from remanence.settings import AppMode, Settings
from remanence.users.key_models import UserKeyBundle
from remanence.users.models import User

from test_capsule_abort_service import _add_draft
from test_capsule_draft_endpoint import _assert_problem, _draft_payload, _post, _raw, _register
from test_incoming_query_service import _NOW, _add_incoming_ready


def _assert_empty_204(response) -> None:
    assert response.status_code == 204
    assert response.content == b""
    assert "content-type" not in response.headers
    assert UUID(response.headers["x-request-id"])
    assert response.text == ""
    assert "capsule_id" not in response.text
    assert "ciphertext_synced_at" not in response.text
    assert "sender" not in response.text


def _sync(client: TestClient, token: str, capsule_id: UUID | str, *, headers=None, content=b""):
    request_headers = {"Authorization": f"Bearer {token}"}
    if headers:
        request_headers.update(headers)
    return client.post(
        f"/v1/capsules/{capsule_id}/material-synced",
        content=content,
        headers=request_headers,
    )


def _ready_world(client: TestClient, factory, prefix: str):
    sender = _register(
        client,
        email=f"{prefix}-sender@example.com",
        handle=f"{prefix}sender",
    )
    recipient = _register(
        client,
        email=f"{prefix}-recipient@example.com",
        handle=f"{prefix}recipient",
    )
    with factory() as session:
        sender_user = session.get(User, UUID(sender["user"]["user_id"]))
        sender_bundle = session.get(UserKeyBundle, UUID(sender["active_key_bundle_id"]))
        recipient_user = session.get(User, UUID(recipient["user"]["user_id"]))
        recipient_bundle = session.get(UserKeyBundle, UUID(recipient["active_key_bundle_id"]))
        assert sender_user is not None
        assert sender_bundle is not None
        assert recipient_user is not None
        assert recipient_bundle is not None
        capsule = _add_incoming_ready(
            session,
            sender=sender_user,
            sender_bundle=sender_bundle,
            recipient=recipient_user,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        session.commit()
    return sender, recipient, capsule.id


def _delivery(factory, recipient_id: UUID, capsule_id: UUID) -> RecipientDeliveryState:
    with factory() as session:
        row = session.get(RecipientDeliveryState, (recipient_id, capsule_id))
        assert row is not None
        return row


def _assert_problem_contract(response, *, status: int, code: str) -> None:
    spec = PROBLEM_CATALOG[code]
    assert response.status_code == status
    assert response.headers["content-type"].startswith("application/problem+json")
    request_id = response.headers["x-request-id"]
    assert set(response.json()) == PROBLEM_BODY_FIELDS
    assert response.json() == problem_payload(code, request_id)
    assert response.json()["status"] == spec.status


def test_authentication_precedes_malformed_path_and_body() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_db_session] = lambda: object()
    client = TestClient(app)

    response = client.post(
        "/v1/capsules/NOT-A-UUID/material-synced",
        content=b"not an empty body",
        headers={"Content-Length": "17", "Transfer-Encoding": "chunked"},
    )
    _assert_problem_contract(response, status=401, code="AUTH_INVALID")
    assert response.headers["www-authenticate"] == "Bearer"


def test_path_body_and_payload_headers_fail_before_transaction() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )

    class _Session:
        def begin(self):
            raise AssertionError("transport rejection must not open a transaction")

    app.dependency_overrides[get_db_session] = lambda: _Session()
    client = TestClient(app)
    capsule_id = str(uuid4())

    for bad_path in ("NOT-A-UUID", capsule_id.upper(), "{" + capsule_id + "}"):
        response = client.post(
            f"/v1/capsules/{bad_path}/material-synced", content=b""
        )
        _assert_problem_contract(response, status=422, code="VALIDATION_FAILED")

    invalid_requests = [
        ({}, b"{}"),
        ({"Content-Length": "1"}, b""),
        ({"Content-Length": "00"}, b""),
        ({"Transfer-Encoding": "chunked"}, b""),
        ({"Content-Encoding": "gzip"}, b""),
        ({"Content-Type": "text/plain"}, b""),
        ({"Content-Type": "application/json"}, b"{}"),
    ]
    for headers, content in invalid_requests:
        response = client.post(
            f"/v1/capsules/{capsule_id}/material-synced",
            content=content,
            headers=headers,
        )
        _assert_problem_contract(response, status=422, code="VALIDATION_FAILED")

    duplicate = client.request(
        "POST",
        f"/v1/capsules/{capsule_id}/material-synced",
        content=b"",
        headers=[
            ("Content-Length", "0"),
            ("Content-Length", "0"),
        ],
    )
    _assert_problem_contract(duplicate, status=422, code="VALIDATION_FAILED")

    valid_empty = client.post(
        f"/v1/capsules/{capsule_id}/material-synced",
        content=b"",
        headers={"Content-Length": "0", "Content-Type": "application/json"},
    )
    _assert_problem_contract(valid_empty, status=500, code="INTERNAL_ERROR")


def test_first_and_replay_are_empty_204_and_keep_first_timestamp(client_factory) -> None:
    client, factory = client_factory
    _sender, recipient, capsule_id = _ready_world(client, factory, "first")
    recipient_id = UUID(recipient["user"]["user_id"])

    before_get = client.get(
        "/v1/capsules/incoming",
        headers={"Authorization": f"Bearer {recipient['access_token']}"},
    )
    assert before_get.status_code == 200
    assert _delivery(factory, recipient_id, capsule_id).state is RecipientDeliveryStatus.AVAILABLE

    first = _sync(client, recipient["access_token"], capsule_id)
    _assert_empty_204(first)
    stored = _delivery(factory, recipient_id, capsule_id)
    assert stored.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
    first_timestamp = stored.ciphertext_synced_at
    assert first_timestamp is not None

    replay = _sync(client, recipient["access_token"], capsule_id)
    _assert_empty_204(replay)
    replayed = _delivery(factory, recipient_id, capsule_id)
    assert replayed.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
    assert replayed.ciphertext_synced_at == first_timestamp


def test_sender_unrelated_missing_and_non_ready_are_redacted(client_factory) -> None:
    client, factory = client_factory
    sender, recipient, ready_id = _ready_world(client, factory, "authz")
    other = _register(client, email="authz-other@example.com", handle="authzother")
    draft_payload = _draft_payload(sender, recipient)
    draft = _post(client, sender["access_token"], _raw(draft_payload))
    assert draft.status_code == 201
    draft_id = UUID(draft_payload["capsule_id"])

    for token, target in (
        (sender["access_token"], ready_id),
        (other["access_token"], ready_id),
        (recipient["access_token"], uuid4()),
        (recipient["access_token"], draft_id),
    ):
        response = _sync(client, token, target)
        _assert_problem_contract(response, status=404, code="CAPSULE_NOT_FOUND")

    with factory() as session:
        delivery = session.get(
            RecipientDeliveryState,
            (UUID(recipient["user"]["user_id"]), ready_id),
        )
        assert delivery is not None
        assert delivery.state is RecipientDeliveryStatus.AVAILABLE
        assert delivery.ciphertext_synced_at is None
        assert session.get(Capsule, ready_id).state is CapsuleState.READY


def test_corrupt_ready_fails_closed_without_transition(client_factory) -> None:
    client, factory = client_factory
    _sender, recipient, capsule_id = _ready_world(client, factory, "corrupt")
    recipient_id = UUID(recipient["user"]["user_id"])
    with factory() as session:
        session.execute(
            update(Capsule)
            .where(Capsule.id == capsule_id)
            .values(signed_statement=b"corrupt-ready-statement")
        )
        session.commit()

    response = _sync(client, recipient["access_token"], capsule_id)
    _assert_problem_contract(response, status=500, code="INTERNAL_ERROR")
    delivery = _delivery(factory, recipient_id, capsule_id)
    assert delivery.state is RecipientDeliveryStatus.AVAILABLE
    assert delivery.ciphertext_synced_at is None


def test_service_failure_rolls_back_endpoint_transaction(client_factory, monkeypatch) -> None:
    client, factory = client_factory
    _sender, recipient, capsule_id = _ready_world(client, factory, "rollback")
    recipient_id = UUID(recipient["user"]["user_id"])

    def fail_after_mutation(self, **_kwargs):
        delivery = self._session.get(RecipientDeliveryState, (recipient_id, capsule_id))
        assert delivery is not None
        delivery.state = RecipientDeliveryStatus.CIPHERTEXT_SYNCED
        delivery.ciphertext_synced_at = _NOW + timedelta(seconds=9)
        self._session.flush()
        raise RuntimeError("secret service failure")

    monkeypatch.setattr(RecipientMaterialSyncedService, "mark_material_synced", fail_after_mutation)
    response = _sync(client, recipient["access_token"], capsule_id)
    _assert_problem_contract(response, status=500, code="INTERNAL_ERROR")
    assert "secret service failure" not in response.text
    delivery = _delivery(factory, recipient_id, capsule_id)
    assert delivery.state is RecipientDeliveryStatus.AVAILABLE
    assert delivery.ciphertext_synced_at is None


def test_transient_database_disconnect_is_retryable_and_rolls_back(
    client_factory, monkeypatch
) -> None:
    client, factory = client_factory
    _sender, recipient, capsule_id = _ready_world(client, factory, "retryabledb")
    recipient_id = UUID(recipient["user"]["user_id"])
    secret = "private-db-detail"

    def fail_transiently(self, **_kwargs):
        raise OperationalError(
            "UPDATE private_statement",
            {"private": secret},
            RuntimeError(secret),
            hide_parameters=True,
            connection_invalidated=True,
        )

    monkeypatch.setattr(RecipientMaterialSyncedService, "mark_material_synced", fail_transiently)
    response = _sync(client, recipient["access_token"], capsule_id)
    _assert_problem_contract(response, status=503, code="INTERNAL_UNAVAILABLE")
    assert secret not in response.text
    delivery = _delivery(factory, recipient_id, capsule_id)
    assert delivery.state is RecipientDeliveryStatus.AVAILABLE
    assert delivery.ciphertext_synced_at is None


def test_transient_database_disconnect_during_commit_is_retryable(monkeypatch) -> None:
    capsule_id = uuid4()
    recipient_id = uuid4()
    secret = "private-commit-detail"

    def fake_mark(self, **kwargs):
        return RecipientMaterialSyncedResult(
            capsule_id=kwargs["capsule_id"],
            state=RecipientDeliveryStatus.CIPHERTEXT_SYNCED,
            ciphertext_synced_at=datetime(2030, 1, 1, tzinfo=timezone.utc),
        )

    monkeypatch.setattr(RecipientMaterialSyncedService, "mark_material_synced", fake_mark)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=recipient_id, session_id=uuid4()
    )

    class _Session:
        def begin(self):
            class _Transaction:
                def __enter__(self):
                    return self

                def __exit__(self, exc_type, _exc_value, _traceback):
                    if exc_type is None:
                        raise DBAPIError(
                            "COMMIT private_statement",
                            {"private": secret},
                            RuntimeError(secret),
                            hide_parameters=True,
                            connection_invalidated=True,
                        )
                    return False

            return _Transaction()

    app.dependency_overrides[get_db_session] = lambda: _Session()
    response = TestClient(app).post(
        f"/v1/capsules/{capsule_id}/material-synced",
        content=b"",
        headers={
            "Authorization": "Bearer unused",
            "Content-Length": "0",
        },
    )
    _assert_problem_contract(response, status=503, code="INTERNAL_UNAVAILABLE")
    assert secret not in response.text


def test_route_has_no_storage_or_idempotency_surface() -> None:
    import inspect

    parameters = inspect.signature(mark_capsule_material_synced).parameters
    assert set(parameters) == {"capsule_id", "request", "principal", "session"}
    source = inspect.getsource(mark_capsule_material_synced)
    assert "BlobStore" not in source
    assert "get_blob_store" not in source
    assert "Idempotency" not in source
    assert "RecipientMaterialSyncedService" in source


def test_mocked_success_uses_authenticated_recipient_and_no_response_fields(monkeypatch) -> None:
    captured: list[dict] = []
    capsule_id = uuid4()
    recipient_id = uuid4()

    def fake_mark(self, **kwargs):
        captured.append(kwargs)
        return RecipientMaterialSyncedResult(
            capsule_id=kwargs["capsule_id"],
            state=RecipientDeliveryStatus.CIPHERTEXT_SYNCED,
            ciphertext_synced_at=datetime(2030, 1, 1, tzinfo=timezone.utc),
        )

    monkeypatch.setattr(RecipientMaterialSyncedService, "mark_material_synced", fake_mark)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=recipient_id, session_id=uuid4()
    )

    class _Session:
        def begin(self):
            class _Transaction:
                def __enter__(self):
                    return self

                def __exit__(self, exc_type, exc_value, traceback):
                    return False

            return _Transaction()

    app.dependency_overrides[get_db_session] = lambda: _Session()
    client = TestClient(app)
    response = _sync(
        client,
        "unused",
        capsule_id,
        headers={"Content-Length": "0"},
    )
    _assert_empty_204(response)
    assert len(captured) == 1
    assert captured[0]["authenticated_recipient_user_id"] == recipient_id
    assert captured[0]["capsule_id"] == capsule_id
    assert captured[0]["now"].tzinfo is not None
