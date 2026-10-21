"""Expired-cancel wire regressions through real FastAPI/PG/current verifiers.

Reuse the real RSA Google and ES256 WebAuthn fixtures. Only certificate transport
is synthetic; advance the existing registration clock, never approve fake auth.
Wire receipts deliberately omit request bearers, Google tokens and grant secrets.
"""
import json
import uuid
from datetime import datetime, timedelta

import pytest
from sqlalchemy import func, select

from test_me_endpoint import me_env
from test_google_auth import google_env
from test_atomic_registration import (
    PERMANENT, atomic, candidate, counts, final, grant, headers, post,
    register_credential, reserve, url,
)
from remanence.registration_setup.models import (
    RegistrationReservation as Reservation,
    RegistrationCredential as Credential,
    RegistrationChallenge as Challenge,
)


def cancel_wire(client, setup, *, request_headers=None, epoch=None):
    body = {"epoch": setup["reservation"]["epoch"] if epoch is None else epoch}
    response = client.post(url(setup, "cancel"), json=body,
                           headers=headers(setup) if request_headers is None else request_headers)
    print("CANCEL_WIRE " + json.dumps({"path": url(setup, "cancel"), "request": body,
        "status": response.status_code, "content_type": response.headers["content-type"],
        "cache_control": response.headers["cache-control"], "body": response.json()}, sort_keys=True))
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["content-type"] == "application/json"
    return response


def persisted_terminal(factory, setup, state, terminal_at, restart_authorized):
    with factory() as db:
        row = db.get(Reservation, uuid.UUID(setup["reservation"]["id"]))
        assert row.state == state
        assert row.terminal_at == terminal_at
        assert row.epoch == setup["reservation"]["epoch"]
        assert row.account_id == uuid.UUID(setup["reservation"]["account_id"])
        assert row.expires_at == datetime.fromisoformat(setup["reservation"]["expires_at"])
        assert row.setup_expires_at == datetime.fromisoformat(setup["setup_expires_at"])
        assert row.setup_session_id == uuid.UUID(setup["setup_session_id"])
        assert row.restart_authorized is restart_authorized
        assert row.is_current is True and row.restarted_into is None
        assert row.accepted_operation_id is None and row.activated_at is None
        assert db.scalar(select(func.count()).select_from(Reservation)) == 1
        assert db.scalar(select(func.count()).select_from(Credential)) == 0
        assert db.scalar(select(func.count()).select_from(Challenge)) == 0
    assert counts(factory) == [0] * len(PERMANENT)


def terminal_status(client, setup, state):
    response = client.get(url(setup), headers=headers(setup))
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    value = response.json()["reservation"]
    assert value["state"] == state
    assert value["id"] == setup["reservation"]["id"]
    assert value["account_id"] == setup["reservation"]["account_id"]
    assert value["epoch"] == setup["reservation"]["epoch"]
    assert value["credential_ids"] == [] and value["outcome"] is None
    assert value["restarted_into"] is None
    return value


def explicit_successor(env, terminal):
    successor = post(env, terminal, "restart")
    current = successor["reservation"]
    assert current["state"] == "RESERVED"
    assert current["id"] != terminal["reservation"]["id"]
    assert current["account_id"] != terminal["reservation"]["account_id"]
    assert current["credential_ids"] == [] and current["outcome"] is None
    repeated = post(env, terminal, "restart")
    assert repeated == {"status": "RESTARTED", "reservation": current, "reauthenticate": True}
    with env[1]() as db:
        old = db.get(Reservation, uuid.UUID(terminal["reservation"]["id"]))
        assert old.is_current is False and str(old.restarted_into) == current["id"]
        assert db.scalar(select(func.count()).select_from(Reservation)) == 2
        assert db.scalar(select(func.count()).select_from(Credential)) == 0
        assert db.scalar(select(func.count()).select_from(Challenge)) == 0
    assert counts(env[1]) == [0] * len(PERMANENT)


def test_cancel_expired_after_fresh_terminal_google_proof(atomic):
    client, factory, _, now = atomic
    original = reserve(atomic)
    auth = register_credential(atomic, original)
    grant(atomic, original, auth)
    now[0] = datetime.fromisoformat(original["reservation"]["expires_at"]) + timedelta(seconds=1)
    terminal = reserve(atomic)
    assert terminal["reservation"]["state"] == "EXPIRED"
    assert terminal["reservation"]["id"] == original["reservation"]["id"]
    assert terminal["reservation"]["epoch"] == original["reservation"]["epoch"] + 1
    assert datetime.fromisoformat(terminal["setup_expires_at"]) > now[0]
    persisted_terminal(factory, terminal, "EXPIRED", now[0], True)

    response = cancel_wire(client, terminal)
    # Check DB before any GET: status must not be the operation persisting expiry.
    persisted_terminal(factory, terminal, "EXPIRED", now[0], True)
    assert response.status_code == 410
    assert response.json() == {"reason": "RESERVATION_TERMINAL"}
    again = cancel_wire(client, terminal)
    assert again.status_code == 410 and again.json() == response.json()
    terminal_status(client, terminal, "EXPIRED")
    explicit_successor(atomic, terminal)


def test_cancel_lazy_expiry_with_still_valid_renewed_capability(atomic):
    client, factory, _, now = atomic
    original = reserve(atomic)
    auth = register_credential(atomic, original)
    deadline = datetime.fromisoformat(original["reservation"]["expires_at"])
    now[0] = deadline - timedelta(seconds=60)
    renewed = reserve(atomic)
    assert renewed["reservation"]["state"] == "RESERVED"
    assert renewed["reservation"]["id"] == original["reservation"]["id"]
    assert renewed["reservation"]["expires_at"] == original["reservation"]["expires_at"]
    grant(atomic, renewed, auth)
    now[0] = deadline + timedelta(seconds=1)
    assert datetime.fromisoformat(renewed["setup_expires_at"]) > now[0]
    with factory() as db:
        row = db.get(Reservation, uuid.UUID(renewed["reservation"]["id"]))
        assert row.state == "RESERVED" and row.terminal_at is None
        assert row.expires_at < now[0] < row.setup_expires_at
        assert db.scalar(select(func.count()).select_from(Credential)) == 1
        assert db.scalar(select(func.count()).select_from(Challenge)) > 0

    response = cancel_wire(client, renewed)
    # Separate transaction immediately observes committed expiry and draft erasure.
    persisted_terminal(factory, renewed, "EXPIRED", now[0], False)
    assert response.status_code == 410
    assert response.json() == {"reason": "RESERVATION_TERMINAL"}
    assert cancel_wire(client, renewed).status_code == 410
    terminal_status(client, renewed, "EXPIRED")
    assert post(atomic, renewed, "restart", expected=401) == {"reason": "SETUP_REAUTH_REQUIRED"}
    persisted_terminal(factory, renewed, "EXPIRED", now[0], False)
    fresh = reserve(atomic)
    assert fresh["reservation"]["id"] == renewed["reservation"]["id"]
    assert fresh["reservation"]["state"] == "EXPIRED"
    explicit_successor(atomic, fresh)


def test_reserved_cancel_replay_leave_and_fresh_restart(atomic):
    client, factory, _, now = atomic
    setup = reserve(atomic)
    auth = register_credential(atomic, setup)
    grant(atomic, setup, auth)
    response = cancel_wire(client, setup)
    assert response.status_code == 200
    persisted_terminal(factory, setup, "CANCELLED", now[0], False)
    expected = terminal_status(client, setup, "CANCELLED")
    assert response.json() == {"reservation": expected}
    again = cancel_wire(client, setup)
    assert again.status_code == 200 and again.json() == response.json()
    assert post(atomic, setup, "restart", expected=401) == {"reason": "SETUP_REAUTH_REQUIRED"}
    assert post(atomic, setup, "leave") == {"left": True}
    assert client.get(url(setup), headers=headers(setup)).status_code == 401
    assert cancel_wire(client, setup).json() == {"reason": "SETUP_AUTH_INVALID"}
    fresh = reserve(atomic)
    assert fresh["reservation"]["state"] == "CANCELLED"
    assert fresh["reservation"]["account_id"] == setup["reservation"]["account_id"]
    explicit_successor(atomic, fresh)


def test_active_cancel_has_no_effect_and_leave_keeps_permanent_identity(atomic):
    client, factory, _, _ = atomic
    setup, body, _ = candidate(atomic)
    accepted = final(atomic, setup, body)
    assert accepted.status_code == 201
    with factory() as db:
        before = [list(db.execute(select(model.__table__)).mappings()) for model in PERMANENT]
    prior = client.get(url(setup), headers=headers(setup)).json()
    for _ in range(2):
        response = cancel_wire(client, setup)
        assert response.status_code == 409 and response.json() == {"reason": "ALREADY_ACTIVE"}
    assert client.get(url(setup), headers=headers(setup)).json() == prior
    assert post(atomic, setup, "restart", expected=409) == {"reason": "ALREADY_ACTIVE"}
    assert post(atomic, setup, "leave") == {"left": True}
    assert client.get(url(setup), headers=headers(setup)).status_code == 401
    access = {"Authorization": "Bearer " + accepted.json()["login"]["access_token"]}
    assert client.get(url(setup), headers=access).json() == prior
    with factory() as db:
        assert [list(db.execute(select(model.__table__)).mappings()) for model in PERMANENT] == before
        assert db.scalar(select(func.count()).select_from(Reservation)) == 1


@pytest.mark.parametrize("failure", ["missing", "malformed", "replaced", "expired", "stale_epoch"])
def test_expired_cancel_preserves_capability_and_epoch_error_precedence(atomic, failure):
    client, factory, _, now = atomic
    original = reserve(atomic)
    now[0] = datetime.fromisoformat(original["reservation"]["expires_at"]) + timedelta(seconds=1)
    terminal = reserve(atomic)
    terminal_at = now[0]
    request_headers, epoch = headers(terminal), terminal["reservation"]["epoch"]
    if failure == "missing": request_headers = {}
    elif failure == "malformed": request_headers = {"Authorization": "Bearer rgs_not-canonical"}
    elif failure == "replaced": request_headers = headers(original)
    elif failure == "expired": now[0] = datetime.fromisoformat(terminal["setup_expires_at"])
    elif failure == "stale_epoch": epoch = original["reservation"]["epoch"]
    response = cancel_wire(client, terminal, request_headers=request_headers, epoch=epoch)
    expected = (409, "STALE_SETUP") if failure == "stale_epoch" else (401, "SETUP_AUTH_INVALID")
    assert response.status_code == expected[0] and response.json() == {"reason": expected[1]}
    persisted_terminal(factory, terminal, "EXPIRED", terminal_at, True)
