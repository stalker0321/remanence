"""Real temporary PostgreSQL acceptance; no live users or media."""
from uuid import UUID, uuid4
import pytest
from sqlalchemy import select, func
from test_me_endpoint import me_env, _seed
from test_capsule_draft_endpoint import _draft_payload
from remanence.settings import AppMode, Settings
from remanence.users.safety_models import UserBlock, SafetyReport, TermsAcceptance
from remanence.capsules.models import Capsule


def headers(seed):
    return {"Authorization": "Bearer " + seed["access_token"]}


def test_safety_requires_auth_and_is_owner_scoped(me_env):
    client, _ = me_env
    assert client.get("/v1/me/safety").status_code == 401
    alice = _seed(client); bob = _seed(client, email="bob@example.com", handle="bob")
    assert client.post("/v1/me/blocks", headers=headers(alice), json={"handle": "@BOB"}).status_code == 200
    assert client.get("/v1/me/safety", headers=headers(alice)).json()["blocked_handles"] == ["bob"]
    assert client.get("/v1/me/safety", headers=headers(bob)).json()["blocked_handles"] == []


def test_block_is_idempotent_self_and_unknown_refused(me_env):
    client, factory = me_env
    alice = _seed(client); _seed(client, email="bob@example.com", handle="bob")
    for _ in range(2): assert client.post("/v1/me/blocks", headers=headers(alice), json={"handle": "bob"}).status_code == 200
    for handle in ("alice", "missing"):
        assert client.post("/v1/me/blocks", headers=headers(alice), json={"handle": handle}).status_code == 404
    with factory() as db: assert db.scalar(select(func.count()).select_from(UserBlock)) == 1
    assert client.post("/v1/me/blocks/remove", headers=headers(alice), json={"handle": "bob"}).status_code == 200
    assert client.get("/v1/me/safety", headers=headers(alice)).json()["blocked_handles"] == []


def test_block_stops_new_draft_and_unblock_restores_delivery(me_env):
    client, factory = me_env
    alice = _seed(client); bob = _seed(client, email="bob@example.com", handle="bob")
    client.post("/v1/me/blocks", headers=headers(bob), json={"handle": "alice"})
    payload = _draft_payload(alice, bob)
    response = client.post("/v1/capsules", headers={**headers(alice), "Idempotency-Key": str(uuid4())}, json=payload)
    assert response.status_code == 409
    assert response.json()["code"] == "RECIPIENT_NOT_CONFIRMED"
    with factory() as db: assert db.scalar(select(func.count()).select_from(Capsule)) == 0
    client.post("/v1/me/blocks/remove", headers=headers(bob), json={"handle": "alice"})
    assert client.post("/v1/capsules", headers={**headers(alice), "Idempotency-Key": str(uuid4())}, json=payload).status_code == 201


def test_reports_bounded_and_metadata_only(me_env):
    client, factory = me_env
    alice = _seed(client); _seed(client, email="bob@example.com", handle="bob")
    body = {"handle": "bob", "reason": "SPAM"}
    for _ in range(5): assert client.post("/v1/me/reports", headers=headers(alice), json=body).status_code == 201
    assert client.post("/v1/me/reports", headers=headers(alice), json=body).status_code == 429
    assert client.post("/v1/me/reports", headers=headers(alice), json={**body, "plaintext": "secret"}).status_code == 422
    with factory() as db:
        rows = db.scalars(select(SafetyReport)).all()
        assert len(rows) == 5
        assert all(row.reason == "SPAM" and row.status == "PENDING" for row in rows)
        assert not {"photos", "note", "keys", "plaintext"} & set(SafetyReport.__table__.columns.keys())


def test_beta_consent_before_draft_and_acceptance_is_idempotent(me_env):
    client, factory = me_env
    alice = _seed(client); bob = _seed(client, email="bob@example.com", handle="bob")
    client.app.state.settings.mode = AppMode.BETA
    payload = _draft_payload(alice, bob)
    def draft():
        return client.post("/v1/capsules", headers={**headers(alice), "Idempotency-Key": str(uuid4())}, json=payload)
    assert draft().json()["code"] == "TERMS_REQUIRED"
    for _ in range(2): assert client.post("/v1/me/terms", headers=headers(alice), json={"version": "2026-10-11"}).status_code == 200
    assert client.get("/v1/me/safety", headers=headers(alice)).json()["terms_accepted"] is True
    assert client.get("/v1/me/safety", headers=headers(bob)).json()["terms_accepted"] is False
    with factory() as db: assert db.scalar(select(func.count()).select_from(TermsAcceptance)) == 1
    assert draft().status_code == 201


@pytest.mark.parametrize("path", ["privacy", "terms", "delete-account"])
def test_public_policy_pages_work_without_account_and_without_secrets(me_env, path):
    client, _ = me_env
    response = client.get("/legal/" + path)
    assert response.status_code == 200
    assert "hryshyn.dev@gmail.com" in response.text and "Remanence" in response.text
    assert response.headers["content-security-policy"].startswith("default-src 'none'")
    assert "<script" not in response.text
    assert "pm_at_" not in response.text and "pm_rt_" not in response.text


def test_beta_config_keeps_production_recovery_gate():
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        Settings(mode="prod", database_url="postgresql+psycopg://local@localhost/local", blob_root="/tmp/local", recovery_enabled=True)
    cfg = Settings(mode="beta", database_url="postgresql+psycopg://local@localhost/local", blob_root="/tmp/local", recovery_enabled=True,
        google_sign_in_enabled=True, google_client_ids=["web.apps.googleusercontent.com"], google_presenter_ids=["android.apps.googleusercontent.com"])
    assert cfg.mode is AppMode.BETA
