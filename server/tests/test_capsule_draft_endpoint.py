"""Tests for the authenticated capsule draft endpoint."""

import base64
import copy
import json
from datetime import datetime, timezone
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select, update

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.capsules.blob_models import CapsuleBlob
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.capsules.models import Capsule, CapsuleState
from remanence.settings import AppMode, Settings
from remanence.main import create_app
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

from test_registration_endpoint import _valid_payload


def _register(client: TestClient, *, email: str, handle: str) -> dict:
    payload = copy.deepcopy(_valid_payload())
    payload["email"] = email
    payload["handle"] = handle
    payload["key_bundle"]["key_bundle_id"] = str(uuid4())
    response = client.post("/v1/auth/register", json=payload)
    assert response.status_code == 201, response.text
    return response.json()


def _digest(value: int) -> str:
    return base64.urlsafe_b64encode(bytes([value]) * 32).decode("ascii").rstrip("=")


def _draft_payload(
    sender: dict,
    recipient: dict,
    *,
    capsule_id: UUID | None = None,
    sender_bundle_id: UUID | None = None,
    recipient_bundle_id: UUID | None = None,
    blob_ids: list[UUID] | None = None,
) -> dict:
    ids = blob_ids or [uuid4() for _ in range(5)]
    assert len(ids) == 5
    return {
        "capsule_id": str(capsule_id or uuid4()),
        "recipient_user_id": recipient["user"]["user_id"],
        "sender_key_bundle_id": str(sender_bundle_id or sender["active_key_bundle_id"]),
        "recipient_key_bundle_id": str(recipient_bundle_id or recipient["active_key_bundle_id"]),
        "protocol_version": 1,
        "blobs": [
            {
                "blob_id": str(ids[0]),
                "kind": "RECOGNITION_MANIFEST",
                "ordinal": None,
                "ciphertext_size": 100,
                "ciphertext_sha256": _digest(1),
            },
            {
                "blob_id": str(ids[1]),
                "kind": "CONTENT_MANIFEST",
                "ordinal": None,
                "ciphertext_size": 200,
                "ciphertext_sha256": _digest(2),
            },
            *[
                {
                    "blob_id": str(ids[ordinal + 2]),
                    "kind": "PHOTO",
                    "ordinal": ordinal,
                    "ciphertext_size": 300 + ordinal,
                    "ciphertext_sha256": _digest(3 + ordinal),
                }
                for ordinal in range(3)
            ],
        ],
    }


def _raw(payload: dict) -> bytes:
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


def _post(client: TestClient, token: str, raw: bytes, key: UUID | None = None):
    return client.post(
        "/v1/capsules",
        content=raw,
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": str(key or uuid4()),
        },
    )


def _assert_problem(response, *, status: int, code: str) -> None:
    assert response.status_code == status
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert set(body) == {"type", "title", "status", "code"}
    assert body["status"] == status
    assert body["code"] == code


def _count(session, model) -> int:
    return session.scalar(select(func.count()).select_from(model))


def test_transport_rejections_work_without_database() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: None
    client = TestClient(app)

    oversized = client.post(
        "/v1/capsules",
        content=b"x" * (16 * 1024 + 1),
        headers={"Idempotency-Key": str(uuid4())},
    )
    _assert_problem(oversized, status=422, code="VALIDATION_FAILED")

    missing_key = client.post("/v1/capsules", content=b"{}")
    _assert_problem(missing_key, status=422, code="VALIDATION_FAILED")


def test_missing_and_malformed_duplicate_idempotency_headers_are_422(client_factory) -> None:
    client, _factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    recipient = _register(client, email="bob@example.com", handle="bob")
    raw = _raw(_draft_payload(sender, recipient))

    missing = client.post(
        "/v1/capsules",
        content=raw,
        headers={"Authorization": f"Bearer {sender['access_token']}"},
    )
    _assert_problem(missing, status=422, code="VALIDATION_FAILED")

    for value in ("not-a-uuid", str(uuid4()).upper()):
        malformed = client.post(
            "/v1/capsules",
            content=raw,
            headers={
                "Authorization": f"Bearer {sender['access_token']}",
                "Idempotency-Key": value,
            },
        )
        _assert_problem(malformed, status=422, code="VALIDATION_FAILED")

    duplicate = client.request(
        "POST",
        "/v1/capsules",
        content=raw,
        headers=[
            ("Authorization", f"Bearer {sender['access_token']}"),
            ("Idempotency-Key", str(uuid4())),
            ("Idempotency-Key", str(uuid4())),
        ],
    )
    _assert_problem(duplicate, status=422, code="VALIDATION_FAILED")


def test_authentication_is_required(client_factory) -> None:
    client, _factory = client_factory
    response = client.post("/v1/capsules", content=b"{}", headers={"Idempotency-Key": str(uuid4())})
    _assert_problem(response, status=401, code="AUTHENTICATION_REQUIRED")


def test_duplicate_json_keys_and_streamed_body_cap_are_redacted(client_factory) -> None:
    client, _factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    recipient = _register(client, email="bob@example.com", handle="bob")
    payload = _draft_payload(sender, recipient)
    raw = _raw(payload)
    marker = f'"capsule_id":"{payload["capsule_id"]}",'
    duplicate = raw.decode("utf-8").replace(marker, marker[:-1] + f',"capsule_id":"{payload["capsule_id"]}",', 1).encode("utf-8")
    response = _post(client, sender["access_token"], duplicate)
    _assert_problem(response, status=422, code="VALIDATION_FAILED")

    oversized = _post(client, sender["access_token"], b"{" * (16 * 1024 + 1))
    _assert_problem(oversized, status=422, code="VALIDATION_FAILED")


def test_success_is_201_replay_is_200_and_response_allow_list_is_exact(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    recipient = _register(client, email="bob@example.com", handle="bob")
    payload = _draft_payload(sender, recipient)
    raw = _raw(payload)
    key = uuid4()

    first = _post(client, sender["access_token"], raw, key)
    assert first.status_code == 201, first.text
    assert first.headers["content-type"].startswith("application/json")
    body = first.json()
    assert set(body) == {"capsule_id", "state", "draft_expires_at", "blobs"}
    assert body["capsule_id"] == payload["capsule_id"]
    assert body["state"] == "DRAFT"
    assert datetime.fromisoformat(body["draft_expires_at"]).utcoffset().total_seconds() == 0
    assert [blob["blob_id"] for blob in body["blobs"]] == [
        blob["blob_id"] for blob in payload["blobs"]
    ]
    assert all(set(blob) == {"blob_id", "state"} and blob["state"] == "DECLARED" for blob in body["blobs"])
    assert "is_replay" not in body

    replay = _post(client, sender["access_token"], raw, key)
    assert replay.status_code == 200, replay.text
    assert replay.json() == body

    with factory() as session:
        capsule = session.get(Capsule, UUID(payload["capsule_id"]))
        assert capsule is not None
        assert capsule.sender_user_id == UUID(sender["user"]["user_id"])
        assert _count(session, Capsule) == 1
        assert _count(session, CapsuleBlob) == 5
        assert _count(session, CapsuleIdempotencyRecord) == 1


def test_replay_response_uses_current_blob_states(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email="state-alice@example.com", handle="statealice")
    recipient = _register(client, email="state-bob@example.com", handle="statebob")
    payload = _draft_payload(sender, recipient)
    key = uuid4()

    first = _post(client, sender["access_token"], _raw(payload), key)
    assert first.status_code == 201
    stored_ids = [UUID(blob["blob_id"]) for blob in payload["blobs"][:2]]
    with factory() as session:
        session.execute(
            update(CapsuleBlob)
            .where(CapsuleBlob.id.in_(stored_ids))
            .values(state="STORED")
        )
        session.commit()

    replay = _post(client, sender["access_token"], _raw(payload), key)
    assert replay.status_code == 200
    assert [blob["state"] for blob in replay.json()["blobs"]] == [
        "STORED",
        "STORED",
        "DECLARED",
        "DECLARED",
        "DECLARED",
    ]
    assert "is_replay" not in replay.json()


def test_replay_after_abort_is_a_redacted_conflict(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email="abort-alice@example.com", handle="abortalice")
    recipient = _register(client, email="abort-bob@example.com", handle="abortbob")
    payload = _draft_payload(sender, recipient)
    key = uuid4()

    first = _post(client, sender["access_token"], _raw(payload), key)
    assert first.status_code == 201
    with factory() as session:
        session.execute(
            update(Capsule)
            .where(Capsule.id == UUID(payload["capsule_id"]))
            .values(state=CapsuleState.ABORTED)
        )
        session.commit()

    replay = _post(client, sender["access_token"], _raw(payload), key)
    _assert_problem(replay, status=409, code="IDEMPOTENCY_CONFLICT")


def test_conflict_mapping_and_sender_binding(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    other = _register(client, email="bob@example.com", handle="bob")
    recipient = _register(client, email="carol@example.com", handle="carol")
    key = uuid4()
    first_payload = _draft_payload(sender, recipient)
    first = _post(client, sender["access_token"], _raw(first_payload), key)
    assert first.status_code == 201

    changed_payload = _draft_payload(sender, recipient)
    conflict = _post(client, sender["access_token"], _raw(changed_payload), key)
    _assert_problem(conflict, status=409, code="IDEMPOTENCY_CONFLICT")

    wrong_sender = _draft_payload(
        sender,
        recipient,
        sender_bundle_id=UUID(other["active_key_bundle_id"]),
    )
    wrong_sender_response = _post(client, sender["access_token"], _raw(wrong_sender))
    _assert_problem(wrong_sender_response, status=404, code="KEY_BUNDLE_NOT_FOUND")

    with factory() as session:
        capsule = session.get(Capsule, UUID(first_payload["capsule_id"]))
        assert capsule is not None
        assert capsule.sender_user_id == UUID(sender["user"]["user_id"])
        assert _count(session, Capsule) == 1


def test_recipient_missing_disabled_and_stale_key_errors(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    recipient = _register(client, email="bob@example.com", handle="bob")

    missing = _draft_payload(sender, recipient, recipient_bundle_id=uuid4())
    missing["recipient_user_id"] = str(uuid4())
    missing_response = _post(client, sender["access_token"], _raw(missing))
    _assert_problem(missing_response, status=409, code="RECIPIENT_NOT_CONFIRMED")

    with factory() as session:
        user = session.get(User, UUID(recipient["user"]["user_id"]))
        assert user is not None
        user.disabled_at = datetime.now(timezone.utc)
        session.commit()
    disabled = _draft_payload(sender, recipient)
    disabled_response = _post(client, sender["access_token"], _raw(disabled))
    _assert_problem(disabled_response, status=409, code="RECIPIENT_NOT_CONFIRMED")

    with factory() as session:
        user = session.get(User, UUID(recipient["user"]["user_id"]))
        assert user is not None
        user.disabled_at = None
        session.commit()
    stale = _draft_payload(
        sender,
        recipient,
        recipient_bundle_id=UUID(sender["active_key_bundle_id"]),
    )
    stale_response = _post(client, sender["access_token"], _raw(stale))
    _assert_problem(stale_response, status=409, code="RECIPIENT_KEY_STALE")


def test_transaction_rolls_back_after_parent_flush_on_child_failure(client_factory) -> None:
    client, factory = client_factory
    sender = _register(client, email="alice@example.com", handle="alice")
    recipient = _register(client, email="bob@example.com", handle="bob")
    first_payload = _draft_payload(sender, recipient)
    first = _post(client, sender["access_token"], _raw(first_payload))
    assert first.status_code == 201

    duplicate_blob_ids = [UUID(blob["blob_id"]) for blob in first_payload["blobs"]]
    second_payload = _draft_payload(sender, recipient, blob_ids=duplicate_blob_ids)
    failed = _post(client, sender["access_token"], _raw(second_payload))
    _assert_problem(failed, status=500, code="INTERNAL_ERROR")

    with factory() as session:
        assert _count(session, Capsule) == 1
        assert _count(session, CapsuleBlob) == 5
        assert _count(session, CapsuleIdempotencyRecord) == 1
