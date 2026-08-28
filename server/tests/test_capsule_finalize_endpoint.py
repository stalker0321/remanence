"""Tests for POST /v1/capsules/{capsule_id}/finalize."""

from __future__ import annotations

import base64
import copy
import hashlib
import json
from contextlib import contextmanager
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import pytest
import tink
from fastapi.testclient import TestClient
from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session
from tink import tink_config

pytest_plugins = ("test_registration_endpoint",)

from remanence.api.dependencies import (
    AuthenticatedPrincipal,
    get_authenticated_principal,
    get_db_session,
)
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.finalize_service import (
    CapsuleFinalizeError,
    CapsuleFinalizeResult,
    CapsuleFinalizeService,
)
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.capsules.limits import LIMITS_V1, MAX_FINALIZE_REQUEST_BYTES
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.publish_statement import MAX_PUBLISH_STATEMENT_BYTES
from remanence.capsules.schemas import CapsuleDraftValidationError, parse_finalize_capsule_request
from remanence.main import create_app
from remanence.settings import AppMode, Settings
from remanence.storage import LocalFileBlobStore
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle

from test_capsule_blob_endpoint import _upload_headers
from test_capsule_draft_endpoint import _assert_problem, _draft_payload, _register, _raw
from test_capsule_finalize_service import (
    _hpke_public_keyset,
    _protocol_blobs,
    _sign,
    _signing_pair,
    _statement_bytes,
)
from test_registration_endpoint import _valid_payload


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _sha256(value: bytes) -> bytes:
    return hashlib.sha256(value).digest()


def _finalize_payload(
    *,
    statement: bytes = b"s",
    signature: bytes | None = None,
    sender_key_bundle_id: UUID | None = None,
    recipient_key_bundle_id: UUID | None = None,
    ciphertext: bytes = b"e",
) -> dict[str, Any]:
    signature = signature if signature is not None else b"\x01" * 69
    return {
        "signed_publish_statement": {
            "statement": _b64(statement),
            "signature": _b64(signature),
            "sender_key_bundle_id": str(sender_key_bundle_id or uuid4()),
        },
        "recipient_envelope": {
            "recipient_key_bundle_id": str(recipient_key_bundle_id or uuid4()),
            "ciphertext": _b64(ciphertext),
            "ciphertext_size": len(ciphertext),
            "ciphertext_sha256": _b64(_sha256(ciphertext)),
        },
    }


def _finalize_body(**kwargs: Any) -> bytes:
    return json.dumps(_finalize_payload(**kwargs), separators=(",", ":")).encode("utf-8")


def _post_finalize(client: TestClient, token: str, capsule_id: str, raw: bytes, extra_headers: dict | None = None):
    headers = {"Authorization": f"Bearer {token}"}
    if extra_headers:
        headers.update(extra_headers)
    return client.post(f"/v1/capsules/{capsule_id}/finalize", content=raw, headers=headers)


def test_parser_accepts_canonical_body_and_rejects_malformed_inputs() -> None:
    parsed = parse_finalize_capsule_request(_finalize_body(statement=b"ok"))
    assert parsed.signed_publish_statement.statement == b"ok"
    assert len(parsed.signed_publish_statement.signature) == 69
    assert parsed.recipient_envelope.ciphertext_size == 1

    with pytest.raises(CapsuleDraftValidationError) as caught:
        parse_finalize_capsule_request(b"")
    assert caught.value.code == "VALIDATION_FAILED"

    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(b"{" * (MAX_FINALIZE_REQUEST_BYTES + 1))
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(b"\xff\xff")
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(b"[1]")
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(b'{"signed_publish_statement":1}')

    payload = _finalize_payload()
    payload["extra"] = True
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(payload).encode())

    duplicate = b'{"signed_publish_statement":{"statement":"YQ","signature":"' + _b64(b"\x01" * 69).encode() + b'","sender_key_bundle_id":"00000000-0000-0000-0000-000000000001","statement":"YQ"},"recipient_envelope":{"recipient_key_bundle_id":"00000000-0000-0000-0000-000000000002","ciphertext":"YQ","ciphertext_size":1,"ciphertext_sha256":"' + _b64(_sha256(b"a")).encode() + b'"}}'
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(duplicate)

    bad_uuid = _finalize_payload()
    bad_uuid["signed_publish_statement"]["sender_key_bundle_id"] = str(uuid4()).upper()
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(bad_uuid).encode())

    padded = _finalize_payload()
    padded["recipient_envelope"]["ciphertext"] = _b64(b"e") + "="
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(padded).encode())

    too_long_statement = _finalize_payload(statement=b"x" * (MAX_PUBLISH_STATEMENT_BYTES + 1))
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(too_long_statement).encode())

    short_sig = _finalize_payload(signature=b"\x01" * 68)
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(short_sig).encode())

    bool_size = _finalize_payload()
    bool_size["recipient_envelope"]["ciphertext_size"] = True
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(bool_size).encode())

    huge = _finalize_payload()
    huge["recipient_envelope"]["ciphertext_size"] = int("1" * 21)
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(huge).encode())

    nonfinite = _finalize_body().replace(b":1,", b":NaN,", 1)
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(nonfinite)

    mismatch_len = _finalize_payload(ciphertext=b"ab")
    mismatch_len["recipient_envelope"]["ciphertext_size"] = 1
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(mismatch_len).encode())

    mismatch_hash = _finalize_payload(ciphertext=b"ab")
    mismatch_hash["recipient_envelope"]["ciphertext_sha256"] = _b64(_sha256(b"zz"))
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(json.dumps(mismatch_hash).encode())

    nested = b"{" * 9 + b"}" * 9
    with pytest.raises(CapsuleDraftValidationError):
        parse_finalize_capsule_request(nested)


def _transport_client() -> TestClient:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )

    class _Session:
        def begin(self):
            raise AssertionError("invalid bodies must not open a transaction")

    app.dependency_overrides[get_db_session] = lambda: _Session()
    app.state.blob_store = object()
    return TestClient(app)


def test_transport_auth_path_and_size_limits_do_not_require_database() -> None:
    client = _transport_client()
    capsule_id = str(uuid4())
    exact_cap = client.post(
        f"/v1/capsules/{capsule_id}/finalize",
        content=b"x" * MAX_FINALIZE_REQUEST_BYTES,
    )
    _assert_problem(exact_cap, status=422, code="VALIDATION_FAILED")
    oversized = client.post(
        f"/v1/capsules/{capsule_id}/finalize",
        content=b"x" * (MAX_FINALIZE_REQUEST_BYTES + 1),
    )
    _assert_problem(oversized, status=422, code="VALIDATION_FAILED")

    bad_path = client.post(
        "/v1/capsules/NOT-A-UUID/finalize",
        content=_finalize_body(),
    )
    _assert_problem(bad_path, status=422, code="VALIDATION_FAILED")

    upper = str(uuid4()).upper()
    bad_case = client.post(
        f"/v1/capsules/{upper}/finalize",
        content=_finalize_body(),
    )
    _assert_problem(bad_case, status=422, code="VALIDATION_FAILED")

    missing_auth = create_app(settings=Settings(mode=AppMode.TEST))
    missing_auth.dependency_overrides[get_db_session] = lambda: object()
    missing_auth.state.blob_store = object()
    unauth = TestClient(missing_auth)
    unauth_resp = unauth.post(f"/v1/capsules/{capsule_id}/finalize", content=_finalize_body())
    _assert_problem(unauth_resp, status=401, code="AUTH_INVALID")


def test_mocked_service_returns_allow_list_and_ignores_idempotency_headers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[dict] = []
    capsule_id = uuid4()
    ready_at = datetime(2030, 1, 1, 12, 0, 0, 123456, tzinfo=timezone.utc)

    def fake_finalize(self, **kwargs):
        captured.append(kwargs)
        replay = len(captured) > 1
        return CapsuleFinalizeResult(
            capsule_id=kwargs["capsule_id"],
            state=CapsuleState.READY,
            ready_at=ready_at,
            recipient_key_bundle_id=uuid4(),
            is_replay=replay,
        )

    monkeypatch.setattr(CapsuleFinalizeService, "finalize", fake_finalize)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )

    class _Session:
        def begin(self):
            @contextmanager
            def _begin():
                yield self
            return _begin()

    app.dependency_overrides[get_db_session] = lambda: _Session()
    app.state.blob_store = object()
    client = TestClient(app)
    raw = _finalize_body()
    first = client.post(f"/v1/capsules/{capsule_id}/finalize", content=raw)
    assert first.status_code == 201, first.text
    body = first.json()
    assert set(body) == {"capsule_id", "state", "ready_at"}
    assert body["capsule_id"] == str(capsule_id)
    assert body["state"] == "READY"
    assert "is_replay" not in body
    assert "recipient_key_bundle_id" not in body

    replay = client.post(
        f"/v1/capsules/{capsule_id}/finalize",
        content=raw,
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert replay.status_code == 200
    assert replay.json() == body

    duplicate = client.request(
        "POST",
        f"/v1/capsules/{capsule_id}/finalize",
        content=raw,
        headers=[
            ("Idempotency-Key", str(uuid4())),
            ("Idempotency-Key", str(uuid4())),
        ],
    )
    assert duplicate.status_code == 200
    assert len(captured) == 3
    assert "idempotency_key" not in captured[0]


def test_mocked_service_error_rolls_back_begin(monkeypatch: pytest.MonkeyPatch) -> None:
    rolled_back = {"value": False}

    def fake_finalize(self, **kwargs):
        raise CapsuleFinalizeError("RECIPIENT_KEY_STALE")

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

    monkeypatch.setattr(CapsuleFinalizeService, "finalize", fake_finalize)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: _Session()
    app.state.blob_store = object()
    client = TestClient(app)
    response = client.post(f"/v1/capsules/{uuid4()}/finalize", content=_finalize_body())
    _assert_problem(response, status=409, code="RECIPIENT_KEY_STALE")
    assert rolled_back["value"] is True


def test_max_legal_finalize_body_parses_and_reaches_service(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[dict] = []
    capsule_id = uuid4()
    ready_at = datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone.utc)

    def fake_finalize(self, **kwargs):
        captured.append(kwargs)
        return CapsuleFinalizeResult(
            capsule_id=kwargs["capsule_id"],
            state=CapsuleState.READY,
            ready_at=ready_at,
            recipient_key_bundle_id=uuid4(),
            is_replay=False,
        )

    monkeypatch.setattr(CapsuleFinalizeService, "finalize", fake_finalize)
    statement = b"s" * MAX_PUBLISH_STATEMENT_BYTES
    ciphertext = b"e" * LIMITS_V1.recipient_envelope_max_ciphertext_bytes
    raw = _finalize_body(statement=statement, ciphertext=ciphertext)
    assert len(raw) <= MAX_FINALIZE_REQUEST_BYTES
    parsed = parse_finalize_capsule_request(raw)
    assert len(parsed.signed_publish_statement.statement) == MAX_PUBLISH_STATEMENT_BYTES
    assert len(parsed.recipient_envelope.ciphertext) == LIMITS_V1.recipient_envelope_max_ciphertext_bytes

    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )

    class _Session:
        def begin(self):
            @contextmanager
            def _begin():
                yield self
            return _begin()

    app.dependency_overrides[get_db_session] = lambda: _Session()
    app.state.blob_store = object()
    client = TestClient(app)
    response = client.post(f"/v1/capsules/{capsule_id}/finalize", content=raw)
    assert response.status_code == 201, response.text
    assert len(captured) == 1
    assert captured[0]["statement"] == statement
    assert captured[0]["envelope"].ciphertext == ciphertext


def test_malformed_service_result_is_500_and_rolls_back(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    rolled_back = {"value": False}

    def fake_finalize(self, **kwargs):
        return CapsuleFinalizeResult(
            capsule_id=kwargs["capsule_id"],
            state=CapsuleState.DRAFT,
            ready_at=datetime(2030, 1, 1, tzinfo=timezone.utc),
            recipient_key_bundle_id=uuid4(),
            is_replay="yes",  # type: ignore[arg-type]
        )

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

    monkeypatch.setattr(CapsuleFinalizeService, "finalize", fake_finalize)
    app = create_app(settings=Settings(mode=AppMode.TEST))
    app.dependency_overrides[get_authenticated_principal] = lambda: AuthenticatedPrincipal(
        user_id=uuid4(), session_id=uuid4()
    )
    app.dependency_overrides[get_db_session] = lambda: _Session()
    app.state.blob_store = object()
    client = TestClient(app)
    response = client.post(f"/v1/capsules/{uuid4()}/finalize", content=_finalize_body())
    _assert_problem(response, status=500, code="INTERNAL_ERROR")
    assert rolled_back["value"] is True
    assert "is_replay" not in response.json()


def _register_with_keys(client: TestClient, *, email: str | None = None, handle: str | None = None, signing_public: bytes) -> dict:
    payload = copy.deepcopy(_valid_payload())
    payload["email"] = email or f"fin-{uuid4().hex[:12]}@example.com"
    payload["handle"] = handle or f"fin{uuid4().hex[:12]}"
    payload["key_bundle"]["key_bundle_id"] = str(uuid4())
    payload["key_bundle"]["encryption_public_keyset"] = _b64(_hpke_public_keyset())
    payload["key_bundle"]["signing_public_keyset"] = _b64(signing_public)
    response = client.post("/v1/auth/register", json=payload)
    assert response.status_code == 201, response.text
    return response.json()


def _upload_all(client: TestClient, sender: dict, draft: dict, bodies: list[bytes]) -> None:
    for blob, body in zip(draft["blobs"], bodies, strict=True):
        response = client.put(
            f"/v1/capsules/{draft['capsule_id']}/blobs/{blob['blob_id']}",
            content=body,
            headers={
                "Authorization": f"Bearer {sender['access_token']}",
                **_upload_headers(body),
            },
        )
        assert response.status_code == 204, response.text


def _live_draft(client: TestClient, factory, tmp_path: Path):
    sender_private, sender_public = _signing_pair()
    sender = _register_with_keys(client, signing_public=sender_public)
    _recipient_private, recipient_public = _signing_pair()
    recipient = _register_with_keys(client, signing_public=recipient_public)
    bodies = [b"rec-body", b"content-body", b"photo-0", b"photo-1-x", b"photo-2-yy"]
    draft = _draft_payload(sender, recipient)
    for blob, body in zip(draft["blobs"], bodies, strict=True):
        blob["ciphertext_size"] = len(body)
        blob["ciphertext_sha256"] = _b64(_sha256(body))
    created = client.post(
        "/v1/capsules",
        content=_raw(draft),
        headers={
            "Authorization": f"Bearer {sender['access_token']}",
            "Idempotency-Key": str(uuid4()),
        },
    )
    assert created.status_code == 201, created.text
    store = LocalFileBlobStore(tmp_path / "blobs")
    client.app.state.blob_store = store
    from remanence.storage import CiphertextStager

    client.app.state.ciphertext_stager = CiphertextStager(tmp_path / "staging")
    _upload_all(client, sender, draft, bodies)
    with factory() as session:
        capsule = session.get(Capsule, UUID(draft["capsule_id"]))
        assert capsule is not None
        statement = _statement_bytes(capsule, _protocol_blobs(session, capsule))
        signature = _sign(sender_private, statement)
        envelope_id = UUID(recipient["active_key_bundle_id"])
    body = _finalize_body(
        statement=statement,
        signature=signature,
        sender_key_bundle_id=UUID(sender["active_key_bundle_id"]),
        recipient_key_bundle_id=envelope_id,
        ciphertext=b"hpke-live",
    )
    return sender, recipient, draft, body, statement, signature


def test_live_finalize_201_then_200_without_duplicates(client_factory, tmp_path: Path) -> None:
    client, factory = client_factory
    sender, _recipient, draft, body, statement, signature = _live_draft(client, factory, tmp_path)
    first = _post_finalize(client, sender["access_token"], draft["capsule_id"], body)
    assert first.status_code == 201, first.text
    payload = first.json()
    assert set(payload) == {"capsule_id", "state", "ready_at"}
    assert payload["capsule_id"] == draft["capsule_id"]
    assert payload["state"] == "READY"
    assert "is_replay" not in payload
    replay = _post_finalize(client, sender["access_token"], draft["capsule_id"], body)
    assert replay.status_code == 200
    assert replay.json() == payload
    with factory() as session:
        assert session.scalar(select(Capsule).where(Capsule.id == UUID(draft["capsule_id"]))).state is CapsuleState.READY
        assert session.scalar(select(CapsuleEnvelope).where(CapsuleEnvelope.capsule_id == UUID(draft["capsule_id"]))) is not None
        assert session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord)) == 1
        assert statement.hex() not in first.text
        assert signature.hex() not in first.text


def test_live_stale_revoked_foreign_and_flush_rollback(client_factory, tmp_path: Path, monkeypatch):
    client, factory = client_factory
    sender, recipient, draft, body, statement, signature = _live_draft(client, factory, tmp_path)

    other = _register(client, email=f"carol-{uuid4().hex[:8]}@example.com", handle=f"carol{uuid4().hex[:8]}")
    foreign = _post_finalize(client, other["access_token"], draft["capsule_id"], body)
    _assert_problem(foreign, status=404, code="CAPSULE_NOT_FOUND")
    assert draft["capsule_id"] not in foreign.text
    assert statement.hex() not in foreign.text

    with factory() as session:
        bundle = session.get(UserKeyBundle, UUID(recipient["active_key_bundle_id"]))
        bundle.status = KeyBundleStatus.RETIRED
        session.add(
            UserKeyBundle(
                id=uuid4(),
                user_id=UUID(recipient["user"]["user_id"]),
                encryption_public_keyset=_hpke_public_keyset(),
                signing_public_keyset=_signing_pair()[1],
                suite=bundle.suite,
                protocol_version=bundle.protocol_version,
                status=KeyBundleStatus.ACTIVE,
            )
        )
        session.commit()
    stale = _post_finalize(client, sender["access_token"], draft["capsule_id"], body)
    _assert_problem(stale, status=409, code="RECIPIENT_KEY_STALE")
    with factory() as session:
        assert session.get(Capsule, UUID(draft["capsule_id"])).state is CapsuleState.DRAFT
        assert session.get(CapsuleEnvelope, UUID(draft["capsule_id"])) is None

    with factory() as session:
        sender_bundle = session.get(UserKeyBundle, UUID(sender["active_key_bundle_id"]))
        sender_bundle.status = KeyBundleStatus.REVOKED
        session.commit()
    revoked = _post_finalize(client, sender["access_token"], draft["capsule_id"], body)
    _assert_problem(revoked, status=409, code="KEY_BUNDLE_REVOKED")
    assert signature.hex() not in revoked.text


def test_live_flush_failure_rolls_back_draft(client_factory, tmp_path: Path, monkeypatch) -> None:
    client, factory = client_factory
    sender, _recipient, draft, body, _statement, _signature = _live_draft(client, factory, tmp_path)

    def fail_flush(self, *args, **kwargs):
        raise SQLAlchemyError("private flush detail")

    monkeypatch.setattr(Session, "flush", fail_flush)
    failed = _post_finalize(client, sender["access_token"], draft["capsule_id"], body)
    monkeypatch.undo()
    _assert_problem(failed, status=500, code="INTERNAL_ERROR")
    assert "private flush detail" not in failed.text
    with factory() as session:
        assert session.get(Capsule, UUID(draft["capsule_id"])).state is CapsuleState.DRAFT
        assert session.get(CapsuleEnvelope, UUID(draft["capsule_id"])) is None
