"""Tests for GET /v1/capsules/incoming."""

from __future__ import annotations

import hashlib
import inspect
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy.exc import SQLAlchemyError

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.capsules import IncomingCapsulesResponse, list_incoming_capsules
from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.capsules.blob_models import CapsuleBlobKind
from remanence.capsules.delivery_models import RecipientDeliveryStatus
from remanence.capsules.encoding import decode_canonical_base64url
from remanence.capsules.incoming_cursor import encode_incoming_cursor
from remanence.capsules.incoming_query_service import (
    IncomingBlobSnapshot,
    IncomingCapsulePage,
    IncomingCapsuleQueryService,
    IncomingCapsuleSnapshot,
    IncomingEnvelopeSnapshot,
)
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.models import CapsuleState
from remanence.main import create_app
from remanence.settings import AppMode, Settings
from remanence.users.key_models import UserKeyBundle
from remanence.users.models import User

from test_capsule_abort_service import _NOW, _add_draft
from test_capsule_draft_endpoint import _assert_problem, _register
from test_incoming_query_service import _add_incoming_ready


_PAGE_KEYS = frozenset({"items", "next_cursor"})
_ITEM_KEYS = frozenset(
    {
        "capsule_id",
        "sender_user_id",
        "recipient_user_id",
        "sender_key_bundle_id",
        "recipient_key_bundle_id",
        "protocol_version",
        "ready_at",
        "signed_publish_statement",
        "recipient_envelope",
        "blobs",
    }
)
_STATEMENT_KEYS = frozenset({"statement", "statement_sha256", "signature"})
_ENVELOPE_KEYS = frozenset(
    {"recipient_key_bundle_id", "ciphertext", "ciphertext_size", "ciphertext_sha256"}
)
_BLOB_KEYS = frozenset({"blob_id", "kind", "ordinal", "ciphertext_size", "ciphertext_sha256"})
_EMAIL_CANARY = "secret-email-canary"
_HANDLE_CANARY = "secrethandlecanary"
_OBJECT_KEY_CANARY = "secret-object-key"


def _digest(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


def _b64d(value: str, size: int) -> bytes:
    return decode_canonical_base64url(value, expected_length=size)


def _snapshot(
    *,
    recipient_id: UUID,
    ready_at: datetime,
    capsule_id: UUID | None = None,
    recipient_key_bundle_id: UUID | None = None,
    statement: bytes = b"signed-statement",
    ciphertext: bytes = b"envelope-ciphertext",
) -> IncomingCapsuleSnapshot:
    bundle_id = recipient_key_bundle_id or uuid4()
    blobs = tuple(
        IncomingBlobSnapshot(
            blob_id=uuid4(),
            kind=kind,
            ordinal=ordinal,
            expected_ciphertext_size=len(body),
            expected_ciphertext_sha256=_digest(body),
        )
        for kind, ordinal, body in (
            (CapsuleBlobKind.RECOGNITION_MANIFEST, None, b"recognition"),
            (CapsuleBlobKind.CONTENT_MANIFEST, None, b"content"),
            (CapsuleBlobKind.PHOTO, 0, b"photo-0"),
            (CapsuleBlobKind.PHOTO, 1, b"photo-1"),
            (CapsuleBlobKind.PHOTO, 2, b"photo-2"),
        )
    )
    return IncomingCapsuleSnapshot(
        capsule_id=capsule_id or uuid4(),
        sender_user_id=uuid4(),
        recipient_user_id=recipient_id,
        sender_key_bundle_id=uuid4(),
        recipient_key_bundle_id=bundle_id,
        protocol_version=1,
        ready_at=ready_at,
        signed_statement=statement,
        signed_statement_sha256=_digest(statement),
        publish_signature=b"\x01" * 69,
        envelope=IncomingEnvelopeSnapshot(
            recipient_key_bundle_id=bundle_id,
            ciphertext=ciphertext,
            ciphertext_size=len(ciphertext),
            ciphertext_sha256=_digest(ciphertext),
        ),
        blobs=blobs,
    )


def _assert_page_keys(body: dict) -> None:
    assert set(body) == _PAGE_KEYS
    assert isinstance(body["items"], list)
    for item in body["items"]:
        assert set(item) == _ITEM_KEYS
        assert set(item["signed_publish_statement"]) == _STATEMENT_KEYS
        assert set(item["recipient_envelope"]) == _ENVELOPE_KEYS
        assert isinstance(item["blobs"], list)
        for blob in item["blobs"]:
            assert set(blob) == _BLOB_KEYS


def _get(client: TestClient, token: str, query: str = "") -> object:
    path = "/v1/capsules/incoming" + (f"?{query}" if query else "")
    return client.get(path, headers={"Authorization": f"Bearer {token}"})


def _transport_client() -> TestClient:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )

    class _Session:
        def begin(self):
            raise AssertionError("invalid query must not open a transaction")

    app.dependency_overrides[get_db_session] = lambda: _Session()
    return TestClient(app)


def _mocked_client(monkeypatch, fake_list, *, user_id: UUID | None = None) -> tuple[TestClient, UUID]:
    principal_id = user_id or uuid4()
    rolled = {"value": False}

    class _Session:
        def begin(self):
            @contextmanager
            def _begin():
                try:
                    yield self
                except Exception:
                    rolled["value"] = True
                    raise

            return _begin()

    monkeypatch.setattr(IncomingCapsuleQueryService, "list_incoming", fake_list)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=principal_id, session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: _Session()
    return TestClient(app), principal_id, rolled


def test_incoming_route_has_no_storage_dependency() -> None:
    source = inspect.getsource(list_incoming_capsules)
    assert "blob_store" not in source
    assert "BlobStore" not in source
    assert "ciphertext_stager" not in source
    assert "get_blob_store" not in source
    assert "get_ciphertext_stager" not in source
    assert "object_key" not in source
    params = inspect.signature(list_incoming_capsules).parameters
    assert "blob_store" not in params
    assert "ciphertext_stager" not in params
    assert "use_cache" in source
    assert set(IncomingCapsulesResponse.model_fields) == {"items", "next_cursor"}


def test_auth_and_query_strictness_do_not_need_database() -> None:
    client = _transport_client()
    bad_cursor = encode_incoming_cursor(ready_at=_NOW, capsule_id=uuid4())
    invalid_queries = [
        "cursor=",
        "cursor=not-a-cursor",
        "limit=",
        "limit=0",
        "limit=01",
        "limit=101",
        "limit=+1",
        "limit=-1",
        "limit=1.0",
        "limit=1 ",
        "limit=%31",
        "limit=true",
        "limit=1000",
        "foo=1",
        "limit=1&foo=1",
        f"cursor={bad_cursor}&cursor={bad_cursor}",
        "limit=1&limit=2",
        "limit=1&",
        "page=1",
    ]
    for query in invalid_queries:
        response = client.get(f"/v1/capsules/incoming?{query}")
        _assert_problem(response, status=422, code="VALIDATION_FAILED")
        assert "www-authenticate" not in {key.lower() for key in response.headers}

    missing_auth = create_app(settings=Settings(mode=AppMode.TEST))
    missing_auth.dependency_overrides[get_db_session] = lambda: object()
    unauth = TestClient(missing_auth)
    missing = unauth.get("/v1/capsules/incoming")
    _assert_problem(missing, status=401, code="AUTH_INVALID")
    assert missing.headers["www-authenticate"] == "Bearer"
    malformed = unauth.get(
        "/v1/capsules/incoming?limit=01",
        headers={"Authorization": "Basic abc"},
    )
    _assert_problem(malformed, status=401, code="AUTH_INVALID")
    assert malformed.headers["www-authenticate"] == "Bearer"


def test_default_max_limit_and_allow_list_from_mocked_service(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: list[dict] = []
    ready_at = datetime(2030, 1, 1, 12, 0, 0, 123456, tzinfo=timezone.utc)
    recipient_id = uuid4()
    first = _snapshot(recipient_id=recipient_id, ready_at=ready_at, statement=b"stmt-one")
    second = _snapshot(
        recipient_id=recipient_id,
        ready_at=ready_at + timedelta(seconds=1),
        statement=b"stmt-two",
    )
    next_cursor = encode_incoming_cursor(ready_at=first.ready_at, capsule_id=first.capsule_id)

    def fake_list(self, **kwargs):
        captured.append(kwargs)
        cursor = kwargs["cursor"]
        limit = kwargs["limit"]
        if cursor is None and limit == 1:
            return IncomingCapsulePage(items=(first,), has_more=True, next_cursor=next_cursor)
        if cursor is None:
            return IncomingCapsulePage(items=(first, second), has_more=False, next_cursor=None)
        return IncomingCapsulePage(items=(second,), has_more=False, next_cursor=None)

    client, principal_id, rolled = _mocked_client(monkeypatch, fake_list, user_id=recipient_id)
    default = _get(client, "unused")
    assert default.status_code == 200, default.text
    assert default.headers["content-type"].startswith("application/json")
    body = default.json()
    _assert_page_keys(body)
    assert body["next_cursor"] is None
    assert "has_more" not in body
    assert len(body["items"]) == 2
    item = body["items"][0]
    assert item["capsule_id"] == str(first.capsule_id)
    assert item["capsule_id"] == item["capsule_id"].lower()
    assert UUID(item["capsule_id"])
    parsed_ready = datetime.fromisoformat(item["ready_at"].replace("Z", "+00:00"))
    assert parsed_ready == ready_at
    assert parsed_ready.microsecond == 123456
    statement = item["signed_publish_statement"]
    assert "=" not in statement["statement"]
    assert _b64d(statement["statement"], len(first.signed_statement)) == first.signed_statement
    assert _b64d(statement["statement_sha256"], 32) == first.signed_statement_sha256
    assert _b64d(statement["signature"], 69) == first.publish_signature
    envelope = item["recipient_envelope"]
    assert _b64d(envelope["ciphertext"], len(first.envelope.ciphertext)) == first.envelope.ciphertext
    assert envelope["ciphertext_size"] == first.envelope.ciphertext_size
    assert item["blobs"][0]["kind"] == "RECOGNITION_MANIFEST"
    assert item["blobs"][0]["ordinal"] is None
    assert item["blobs"][2]["kind"] == "PHOTO"
    assert item["blobs"][2]["ordinal"] == 0
    assert captured[0]["authenticated_recipient_user_id"] == principal_id
    assert captured[0]["cursor"] is None
    assert captured[0]["limit"] == LIMITS_V1.incoming_page_default == 50
    assert rolled["value"] is False

    maxed = _get(client, "unused", "limit=100")
    assert maxed.status_code == 200
    assert captured[1]["limit"] == 100

    paged = _get(client, "unused", "limit=1")
    assert paged.status_code == 200
    page_body = paged.json()
    assert page_body["next_cursor"] == next_cursor
    replay = _get(client, "unused", f"limit=1&cursor={next_cursor}")
    assert replay.status_code == 200
    replay_again = _get(client, "unused", f"limit=1&cursor={next_cursor}")
    assert replay.json() == replay_again.json()
    assert [row["capsule_id"] for row in replay.json()["items"]] == [str(second.capsule_id)]
    assert replay.json()["next_cursor"] is None
    assert "object_key" not in default.text
    assert "has_more" not in default.text
    assert rolled["value"] is False


def test_malformed_service_result_and_db_error_roll_back(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_bad(self, **kwargs):
        return IncomingCapsulePage(items=("nope",), has_more=False, next_cursor=None)  # type: ignore[arg-type]

    client, _principal_id, rolled = _mocked_client(monkeypatch, fake_bad)
    response = _get(client, "unused")
    _assert_problem(response, status=500, code="INTERNAL_ERROR")
    assert rolled["value"] is True
    assert "nope" not in response.text

    def fake_db(self, **kwargs):
        raise SQLAlchemyError("secret-db-failure")

    client, _principal_id, rolled = _mocked_client(monkeypatch, fake_db)
    db_response = _get(client, "unused")
    _assert_problem(db_response, status=500, code="INTERNAL_ERROR")
    assert rolled["value"] is True
    assert "secret-db-failure" not in db_response.text


def test_response_dump_failure_is_redacted_internal(monkeypatch: pytest.MonkeyPatch) -> None:
    recipient_id = uuid4()
    item = _snapshot(recipient_id=recipient_id, ready_at=_NOW)

    def fake_list(self, **kwargs):
        return IncomingCapsulePage(items=(item,), has_more=False, next_cursor=None)

    def boom(self, *args, **kwargs):
        IncomingCapsulesResponse.model_validate({"items": "secret-wire-leak", "next_cursor": None})

    monkeypatch.setattr(IncomingCapsulesResponse, "model_dump", boom)
    client, _principal_id, rolled = _mocked_client(monkeypatch, fake_list, user_id=recipient_id)
    response = _get(client, "unused")
    _assert_problem(response, status=500, code="INTERNAL_ERROR")
    assert rolled["value"] is True
    assert "secret-wire-leak" not in response.text
    with pytest.raises(ValidationError):
        boom(None)


def test_live_isolation_ready_only_synced_allow_list_and_bytes(client_factory) -> None:
    client, factory = client_factory
    sender_reg = _register(client, email=f"{_EMAIL_CANARY}-{uuid4().hex[:8]}@example.com", handle=f"snd{uuid4().hex[:8]}")
    recipient_reg = _register(
        client,
        email=f"rec-{uuid4().hex[:8]}@example.com",
        handle=f"{_HANDLE_CANARY[:12]}{uuid4().hex[:8]}"[:30],
    )
    other_reg = _register(client, email=f"oth-{uuid4().hex[:8]}@example.com", handle=f"oth{uuid4().hex[:8]}")
    with factory() as session:
        sender = session.get(User, UUID(sender_reg["user"]["user_id"]))
        sender_bundle = session.get(UserKeyBundle, UUID(sender_reg["active_key_bundle_id"]))
        recipient = session.get(User, UUID(recipient_reg["user"]["user_id"]))
        recipient_bundle = session.get(UserKeyBundle, UUID(recipient_reg["active_key_bundle_id"]))
        other = session.get(User, UUID(other_reg["user"]["user_id"]))
        other_bundle = session.get(UserKeyBundle, UUID(other_reg["active_key_bundle_id"]))
        available = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            statement=b"live-statement-one",
            ciphertext=b"live-envelope-one",
            object_key_prefix=_OBJECT_KEY_CANARY,
        )
        synced = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=1, microseconds=7),
            delivery_status=RecipientDeliveryStatus.CIPHERTEXT_SYNCED,
            statement=b"live-statement-two",
            ciphertext=b"live-envelope-two",
            object_key_prefix=_OBJECT_KEY_CANARY,
        )
        _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=other,
            recipient_bundle=other_bundle,
            ready_at=_NOW,
            statement=b"other-statement",
            ciphertext=b"other-envelope",
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
        available_id = available.id
        synced_id = synced.id
        draft_id = draft.id
        aborted_id = aborted.id
        service_page = IncomingCapsuleQueryService(session).list_incoming(
            authenticated_recipient_user_id=recipient.id,
            limit=10,
        )

    empty = _get(client, sender_reg["access_token"])
    assert empty.status_code == 200, empty.text
    empty_body = empty.json()
    _assert_page_keys(empty_body)
    assert empty_body["items"] == []
    assert empty_body["next_cursor"] is None

    first = _get(client, recipient_reg["access_token"], "limit=1")
    assert first.status_code == 200, first.text
    first_body = first.json()
    _assert_page_keys(first_body)
    assert [item["capsule_id"] for item in first_body["items"]] == [str(available_id)]
    assert first_body["next_cursor"] is not None
    replay = _get(client, recipient_reg["access_token"], "limit=1")
    assert replay.json() == first_body
    continuation = _get(
        client,
        recipient_reg["access_token"],
        f"limit=1&cursor={first_body['next_cursor']}",
    )
    assert continuation.status_code == 200
    cont_body = continuation.json()
    assert [item["capsule_id"] for item in cont_body["items"]] == [str(synced_id)]
    assert cont_body["next_cursor"] is None

    full = _get(client, recipient_reg["access_token"], "limit=100")
    assert full.status_code == 200
    full_body = full.json()
    _assert_page_keys(full_body)
    assert [item["capsule_id"] for item in full_body["items"]] == [str(available_id), str(synced_id)]
    assert full_body["next_cursor"] is None
    assert str(draft_id) not in full.text
    assert str(aborted_id) not in full.text
    other_page = _get(client, other_reg["access_token"])
    assert [item["capsule_id"] for item in other_page.json()["items"]] != [str(available_id), str(synced_id)]
    assert len(other_page.json()["items"]) == 1
    assert other_page.json()["items"][0]["recipient_user_id"] == other_reg["user"]["user_id"]

    assert len(service_page.items) == 2
    for snap, wire in zip(service_page.items, full_body["items"], strict=True):
        assert wire["capsule_id"] == str(snap.capsule_id)
        assert wire["sender_user_id"] == str(snap.sender_user_id)
        assert wire["recipient_user_id"] == str(snap.recipient_user_id)
        assert wire["protocol_version"] == snap.protocol_version
        parsed_ready = datetime.fromisoformat(wire["ready_at"].replace("Z", "+00:00"))
        assert parsed_ready == snap.ready_at
        statement = wire["signed_publish_statement"]
        assert _b64d(statement["statement"], len(snap.signed_statement)) == snap.signed_statement
        assert _b64d(statement["statement_sha256"], 32) == snap.signed_statement_sha256
        assert _b64d(statement["signature"], 69) == snap.publish_signature
        envelope = wire["recipient_envelope"]
        assert _b64d(envelope["ciphertext"], len(snap.envelope.ciphertext)) == snap.envelope.ciphertext
        assert envelope["ciphertext_size"] == snap.envelope.ciphertext_size
        assert _b64d(envelope["ciphertext_sha256"], 32) == snap.envelope.ciphertext_sha256
        assert len(wire["blobs"]) == len(snap.blobs)
        for blob_snap, blob_wire in zip(snap.blobs, wire["blobs"], strict=True):
            assert blob_wire["blob_id"] == str(blob_snap.blob_id)
            assert blob_wire["kind"] == blob_snap.kind.value
            assert blob_wire["ordinal"] == blob_snap.ordinal
            assert blob_wire["ciphertext_size"] == blob_snap.expected_ciphertext_size
            assert _b64d(blob_wire["ciphertext_sha256"], 32) == blob_snap.expected_ciphertext_sha256
            assert "=" not in blob_wire["ciphertext_sha256"]

    leaked = full.text + other_page.text + empty.text
    assert _EMAIL_CANARY not in leaked
    assert _HANDLE_CANARY[:12] not in leaked
    assert _OBJECT_KEY_CANARY not in leaked
    assert "object_key" not in leaked
    assert "has_more" not in leaked
    assert "AVAILABLE" not in leaked
    assert "CIPHERTEXT_SYNCED" not in leaked
