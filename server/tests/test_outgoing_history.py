"""Real SQL + real authenticated FastAPI, isolated synthetic PostgreSQL only."""
import base64
import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr
from sqlalchemy import event, select

pytest_plugins = ("test_session_repository_create",)

from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.tokens import hash_opaque_token
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.outgoing_query_service import OutgoingQueryService, HistoryQueryError, _cursor
from remanence.main import create_app
from remanence.pending.claim_models import PendingDelivery
from remanence.pending.locator_store import code_hmac
from remanence.pending.models import GiftLocator
from remanence.pending.vault import aead_from_raw_key
from remanence.settings import Settings, AppMode
from test_capsule_abort_service import _seed_user

NOW = datetime.now(timezone.utc)
TAG_KEY = b"h" * 32
CODE_KEY = b"k" * 32
CUSTODY = {"hmac_key": TAG_KEY, "locator_code_key": CODE_KEY}

@pytest.fixture(scope="module", autouse=True)
def _register_existing_tink():
    from tink import tink_config
    tink_config.register()


def _token(session, owner):
    token = "pm_at_" + base64.urlsafe_b64encode(uuid4().bytes + uuid4().bytes).decode().rstrip("=")
    AuthSessionRepository(session).create(
        user_id=owner.id, access_token_hash=hash_opaque_token(token),
        refresh_token_hash=uuid4().bytes + uuid4().bytes,
        access_expires_at=NOW + timedelta(days=2), refresh_expires_at=NOW + timedelta(days=4),
    )
    return {"Authorization": f"Bearer {token}"}


def _capsule(session, sender, recipient, *, state=CapsuleState.READY, opened=False, ready=None):
    published = state in (CapsuleState.READY, CapsuleState.REVOKED)
    row = Capsule(
        id=uuid4(), sender_user_id=sender[0].id, recipient_user_id=recipient[0].id,
        sender_key_bundle_id=sender[1].id, recipient_key_bundle_id=recipient[1].id,
        protocol_version=1, state=state, created_at=NOW - timedelta(days=2),
        draft_expires_at=NOW + timedelta(days=1),
        ready_at=(ready or NOW - timedelta(hours=1)) if published else None,
        signed_statement=b"PRIVATE-STATEMENT-CANARY" if published else None,
        signed_statement_sha256=b"s" * 32 if published else None,
        publish_signature=b"x" * 69 if published else None,
        first_opened_at=NOW if opened else None,
        revoked_at=NOW if state == CapsuleState.REVOKED else None,
        tombstone_sequence=uuid4().int % 1_000_000_000 + 1 if state == CapsuleState.REVOKED else None,
    )
    session.add(row)
    session.flush()
    return row


def _locator(session, capsule_id, code, state="ACTIVE"):
    row = GiftLocator(
        id=uuid4(), capsule_id=capsule_id, code_hmac=code_hmac(code, TAG_KEY),
        code_ciphertext=aead_from_raw_key(CODE_KEY).encrypt(code.encode(), b"pending-locator-code-v1"),
        state=state, hint_mask="r•••@•••", created_at=NOW,
    )
    session.add(row)
    session.flush()
    return row


def _pending(session, sender, state, *, capsule_id=None):
    row = PendingDelivery(
        id=uuid4(), sender_user_id=sender[0].id, capsule_id=capsule_id or uuid4(),
        delivery_target_id=uuid4(), channel="email", state=state,
        claim_expires_at=NOW + timedelta(days=5), created_at=NOW - timedelta(days=2),
        publish_statement=b"PRIVATE-V2-STATEMENT" if state != "DRAFT" else None,
        publish_statement_sha256=b"v" * 32, publish_signature=b"z" * 69,
        address_commitment=b"a" * 32, key_commitment=b"b" * 32,
        vault_ciphertext=None if state == "CLAIMED" else b"PRIVATE-VAULT-CANARY",
    )
    session.add(row)
    session.flush()
    return row


def _app(factory, *, pending=False):
    settings = Settings(mode=AppMode.TEST, pending_protocol_enabled=pending,
                        pending_sends_enabled=pending,
                        pending_hmac_secret=SecretStr(TAG_KEY.decode()) if pending else None)
    app = create_app(settings=settings, session_factory=factory, pending_ctx=CUSTODY if pending else None)
    if not pending:
        app.state.pending_ctx = CUSTODY
    return app


def _get(client, headers, path="/v1/capsules/outgoing"):
    response = client.get(path, headers=headers)
    assert response.headers["cache-control"] == "private, no-store"
    return response


def test_authenticated_isolation_metadata_and_real_revoke(session_factory):
    with session_factory() as session:
        sender, target, other = [_seed_user(session, label) for label in ("sender", "target", "other")]
        headers, other_headers = _token(session, sender[0]), _token(session, other[0])
        ready = _capsule(session, sender, target)
        _locator(session, ready.id, "AB12Z")
        capsule_id = str(ready.id)
        session.commit()
    with TestClient(_app(session_factory)) as client:
        assert _get(client, {}).status_code == 401
        assert _get(client, other_headers).json()["items"] == []
        response = _get(client, headers)
        assert response.status_code == 200, response.text
        row = response.json()["items"][0]
        assert row["locator"] == {"status": "ACTIVE", "code": "AB12Z"}
        assert row["cancel_action"] == "REVOKE_V1"
        assert row["recipient"]["user_id"] == str(target[0].id)
        assert "PRIVATE" not in response.text and "example.com" not in response.text
        denied = client.post(f"/v1/capsules/{capsule_id}/revoke", headers=other_headers)
        assert denied.status_code == 404
        revoked = client.post(f"/v1/capsules/{capsule_id}/revoke", headers=headers)
        assert revoked.status_code == 200, revoked.text
        assert revoked.json()["is_replay"] is False
        assert client.post(f"/v1/capsules/{capsule_id}/revoke", headers=headers).json()["is_replay"] is True
        row = _get(client, headers).json()["items"][0]
        assert row["status"] == "REVOKED" and row["cancel_action"] is None
        assert row["locator"] == {"status": "RETIRED_TERMINAL", "code": None}


def test_keyset_ties_both_ledgers_owner_cursor_and_live_update(session_factory):
    with session_factory() as session:
        sender, target = _seed_user(session, "sender"), _seed_user(session, "target")
        owner = sender[0].id
        for _ in range(4):
            _capsule(session, sender, target)
        for state in ("DRAFT", "PENDING", "CLAIMED", "CANCELLED", "EXPIRED"):
            _pending(session, sender, state)
        session.commit()
        service = OutgoingQueryService(session)
        all_rows = service.outgoing(owner=owner, now=NOW)["items"]
        seen, cursor = [], None
        while True:
            page = service.outgoing(owner=owner, now=NOW, limit=2, cursor=cursor)
            seen.extend(page["items"])
            if not page["has_more"]:
                assert page["next_cursor"] is None
                break
            cursor = page["next_cursor"]
        assert seen == all_rows and len(seen) == 9
        keys = [(row["created_at"], row["protocol_version"], row["capsule_id"]) for row in seen]
        assert keys == sorted(set(keys), reverse=True)
        with pytest.raises(HistoryQueryError):
            service.outgoing(owner=target[0].id, now=NOW, cursor=cursor)
        page = service.outgoing(owner=owner, now=NOW, limit=1)
        newer = _capsule(session, sender, target)
        newer.created_at = NOW
        session.commit()
        rest = service.outgoing(owner=owner, now=NOW, cursor=page["next_cursor"])
        assert str(newer.id) not in [row["capsule_id"] for row in rest["items"]]
        for row in seen:
            if row["protocol_version"] == 2:
                assert row["published_at"] is None
                assert row["recipient"]["user_id"] is None and row["recipient"]["is_self"] is None
                assert row["cancel_action"] is None  # flags OFF


def test_readonly_projection_and_recent_dedupe_self_disabled_unpublished(session_factory):
    with session_factory() as session:
        sender, target, disabled, unnamed = [_seed_user(session, label) for label in ("sender", "target", "disabled", "unnamed")]
        disabled[0].disabled_at = NOW
        unnamed[0].handle_normalized = unnamed[0].handle_display = None
        _capsule(session, sender, target, opened=True)
        _capsule(session, sender, target, state=CapsuleState.REVOKED, ready=NOW)
        _capsule(session, sender, sender)
        _capsule(session, sender, disabled)
        _capsule(session, sender, unnamed)
        _capsule(session, sender, target, state=CapsuleState.DRAFT)
        _capsule(session, sender, target, state=CapsuleState.ABORTED)
        _pending(session, sender, "CLAIMED")
        session.commit()
        statements = []
        def observe(_conn, _cursor, statement, *_args):
            statements.append(statement)
        event.listen(session.bind, "before_cursor_execute", observe)
        try:
            service = OutgoingQueryService(session)
            recent = service.recent(owner=sender[0].id)["items"]
            rows = service.outgoing(owner=sender[0].id, now=NOW)["items"]
        finally:
            event.remove(session.bind, "before_cursor_execute", observe)
        assert recent == [{"user_id": str(target[0].id), "handle": target[0].handle_normalized, "last_sent_at": NOW.isoformat()}]
        assert len(rows) == 8
        for statement in statements:
            assert statement.lstrip().upper().startswith("SELECT")
            for forbidden in ("vault_ciphertext", "sealed_destination", "publish_signature", "signed_statement", "email_normalized"):
                assert forbidden not in statement


def test_pending_actions_exact_cancel_and_claimed_conflict(session_factory):
    with session_factory() as session:
        sender = _seed_user(session, "sender")
        headers = _token(session, sender[0])
        pending = _pending(session, sender, "PENDING")
        pending.locator_id = _locator(session, pending.capsule_id, "BC23Z").id
        claimed = _pending(session, sender, "CLAIMED")
        delivery_id, claimed_id = str(pending.id), str(claimed.id)
        session.commit()
    with TestClient(_app(session_factory, pending=True)) as client:
        rows = _get(client, headers).json()["items"]
        row = next(row for row in rows if row["delivery_id"] == delivery_id)
        assert row["cancel_action"] == "CANCEL_PENDING_V2" and row["cancel_deadline"] is None
        assert row["locator"]["code"] == "BC23Z"
        assert client.post(f"/v2/pending-deliveries/{claimed_id}/cancel", headers=headers).status_code == 409
        for _ in range(2):
            response = client.post(f"/v2/pending-deliveries/{delivery_id}/cancel", headers=headers)
            assert response.status_code == 200 and response.json()["state"] == "CANCELLED"
        row = next(row for row in _get(client, headers).json()["items"] if row["delivery_id"] == delivery_id)
        assert row["status"] == "CANCELLED" and row["locator"]["code"] is None


def test_locator_custody_corruption_retired_collision_and_revoke_boundary(session_factory):
    with session_factory() as session:
        sender, target, other = [_seed_user(session, label) for label in ("sender", "target", "other")]
        row = _capsule(session, sender, target, ready=NOW - timedelta(hours=24))
        locator = _locator(session, row.id, "CD34Z")
        session.commit()
        service = OutgoingQueryService(session)
        args = dict(owner=sender[0].id, now=NOW, custody=CUSTODY, mode="test")
        assert service.outgoing(**args)["items"][0]["cancel_action"] == "REVOKE_V1"
        assert service.outgoing(**{**args, "now": NOW + timedelta(microseconds=1)})["items"][0]["cancel_action"] is None
        assert service.outgoing(**{**args, "mode": "prod"})["items"][0]["locator"]["code"] is None
        assert service.outgoing(**{**args, "custody": None})["items"][0]["locator"]["code"] is None
        locator.code_hmac = b"wrong".ljust(32, b"x")
        session.commit()
        assert service.outgoing(**args)["items"][0]["locator"]["status"] == "UNAVAILABLE"
        locator.state = "RETIRED_OPEN"
        session.commit()
        assert service.outgoing(**args)["items"][0]["locator"] == {"status": "RETIRED_OPEN", "code": None}
        _pending(session, other, "DRAFT", capsule_id=row.id)
        session.commit()
        value = service.outgoing(**args)["items"][0]
        assert value["locator"]["code"] is None and value["recipient"]["hint"] is None


def test_query_validation_error_cache_headers_and_auth(session_factory):
    with session_factory() as session:
        sender = _seed_user(session, "sender")
        headers = _token(session, sender[0])
        session.commit()
    with TestClient(_app(session_factory)) as client:
        for base, maximum in (("/v1/capsules/outgoing", 50), ("/v1/recipients/recent", 20)):
            assert _get(client, {}, base).status_code == 401
            for query in ("limit=0", "limit=01", f"limit={maximum+1}", "limit=1&limit=2", "sender=x", "cursor="):
                response = _get(client, headers, f"{base}?{query}")
                assert response.status_code == 422, (query, response.text)
                assert response.json()["code"] == "VALIDATION_FAILED"
            assert _get(client, headers, f"{base}?limit={maximum}").status_code == 200
        for value in ("bad", "A" * 1025, base64.urlsafe_b64encode(json.dumps([1, str(sender[0].id), NOW.isoformat(), 1, 42]).encode()).decode().rstrip("=")):
            assert _get(client, headers, f"/v1/capsules/outgoing?cursor={value}").status_code == 422


def test_opened_late_revoke_and_pending_other_owner_denied(session_factory):
    with session_factory() as session:
        sender, target, other = [_seed_user(session, name) for name in ("sender", "target", "other")]
        headers, other_headers = _token(session, sender[0]), _token(session, other[0])
        opened = _capsule(session, sender, target, opened=True)
        late = _capsule(session, sender, target, ready=NOW - timedelta(days=2))
        pending = _pending(session, sender, "PENDING")
        opened_id, late_id, pending_id = str(opened.id), str(late.id), str(pending.id)
        session.commit()
    with TestClient(_app(session_factory, pending=True)) as client:
        for capsule_id in (opened_id, late_id):
            assert client.post(f"/v1/capsules/{capsule_id}/revoke", headers=headers).status_code == 409
        assert client.post(f"/v2/pending-deliveries/{pending_id}/cancel", headers=other_headers).status_code == 404
        assert _get(client, other_headers, "/v1/recipients/recent").json() == {"items": []}
        rows = _get(client, headers).json()["items"]
        assert all(row["cancel_action"] is None for row in rows if row["protocol_version"] == 1)
        assert next(row for row in rows if row["capsule_id"] == opened_id)["status"] == "OPENED"


def test_recent_ties_limit_and_reserved_missing_codes(session_factory):
    with session_factory() as session:
        sender = _seed_user(session, "sender")
        targets = [_seed_user(session, f"target{index}") for index in range(10)]
        for target in targets:
            _capsule(session, sender, target, ready=NOW)
        reserved = _capsule(session, sender, targets[0], state=CapsuleState.DRAFT)
        _locator(session, reserved.id, "DE45Z", state="RESERVED")
        session.commit()
        service = OutgoingQueryService(session)
        recent = service.recent(owner=sender[0].id)["items"]
        assert len(recent) == 8
        assert [row["user_id"] for row in recent] == sorted([str(target[0].id) for target in targets], reverse=True)[:8]
        assert len(service.recent(owner=sender[0].id, limit=20)["items"]) == 10
        rows = service.outgoing(owner=sender[0].id, now=NOW, mode="test", custody=CUSTODY)["items"]
        assert len(rows) == 11
        assert all(row["locator"] == {"status": "UNAVAILABLE", "code": None} for row in rows)


def test_safe_failure_envelopes_remain_private(session_factory, monkeypatch):
    from sqlalchemy.exc import OperationalError
    with session_factory() as session:
        sender = _seed_user(session, "sender")
        headers = _token(session, sender[0])
        session.commit()
    with TestClient(_app(session_factory)) as client:
        for method, path in (("outgoing", "/v1/capsules/outgoing"), ("recent", "/v1/recipients/recent")):
            for error, status, code in ((OperationalError("PRIVATE-SQL", {}, Exception("PRIVATE")), 503, "INTERNAL_UNAVAILABLE"),
                                        (ValueError("PRIVATE-CORRUPT"), 500, "INTERNAL_ERROR")):
                def fail(_self, **_kwargs):
                    raise error
                with monkeypatch.context() as patch:
                    patch.setattr(OutgoingQueryService, method, fail)
                    response = _get(client, headers, path)
                assert response.status_code == status
                assert response.json()["code"] == code
                assert "PRIVATE" not in response.text


def test_dependency_failures_are_private_and_contract_shaped(session_factory, monkeypatch):
    from sqlalchemy.exc import OperationalError
    with session_factory() as session:
        sender = _seed_user(session, "sender")
        headers = _token(session, sender[0])
        session.commit()
    app = _app(session_factory)
    with TestClient(app) as client:
        for error, status, code in ((OperationalError("PRIVATE", {}, Exception()), 503, "INTERNAL_UNAVAILABLE"),
                                   (RuntimeError("PRIVATE"), 500, "INTERNAL_ERROR")):
            def fail(*_args, **_kwargs):
                raise error
            with monkeypatch.context() as patch:
                patch.setattr(AuthSessionRepository, "find_by_access_token_hash", fail)
                for path in ("/v1/capsules/outgoing", "/v1/recipients/recent"):
                    response = _get(client, headers, path)
                    assert response.status_code == status
                    assert response.json()["code"] == code
                    assert "PRIVATE" not in response.text
        app.state.session_factory = None
        response = _get(client, headers)
        assert response.status_code == 503 and response.json()["code"] == "INTERNAL_UNAVAILABLE"
