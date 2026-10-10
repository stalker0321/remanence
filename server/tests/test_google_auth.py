"""Real Google library RSA verification with synthetic cert transport and real PG.

No real Google account/token and no signature-verifier replacement. Cert fetch is
the only substituted network boundary; provider UI/production OAuth remain untested.
"""
import json
import time
import uuid
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor

import pytest
from google.auth import crypt, jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from sqlalchemy import select, func

from test_me_endpoint import me_env, _registration_payload, _seed
from remanence.auth.models import AuthCredential, AuthSession
from remanence.auth.google_models import GoogleIdentity
from remanence.settings import Settings
from remanence.users.models import User

CLIENT = "synthetic-web.apps.googleusercontent.com"
ANDROID = "synthetic-android.apps.googleusercontent.com"


@pytest.fixture
def google_env(me_env, monkeypatch):
    client, factory = me_env
    cfg = client.app.state.settings
    cfg.recovery_enabled = True; cfg.google_sign_in_enabled = True
    cfg.google_client_ids = [CLIENT]; cfg.google_presenter_ids = [ANDROID]
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    public = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    signer = crypt.RSASigner.from_string(private, key_id="synthetic")

    class CertificateTransport:
        def __call__(self, url, **kwargs):
            assert url == "https://www.googleapis.com/oauth2/v1/certs"
            return type("Response", (), {"status": 200, "data": json.dumps({"synthetic": public}).encode()})()

    monkeypatch.setattr("remanence.auth.google_verifier.BoundedRequest", CertificateTransport)
    def token(options, **changes):
        now = int(time.time())
        claims = {"iss": "https://accounts.google.com", "aud": CLIENT, "azp": ANDROID,
                  "iat": now, "exp": now + 300, "nonce": options["nonce"], "sub": "synthetic-subject",
                  "email": "google@example.com", "email_verified": True}
        claims.update(changes)
        return jwt.encode(signer, claims).decode()
    return client, factory, token


def options(client):
    response = client.post("/v1/auth/google/options", json={})
    assert response.status_code == 200
    return response.json()


def verify(client, start, token):
    return client.post("/v1/auth/google/verify", json={"flow": start["flow"], "nonce": start["nonce"], "id_token": token})


def registration(ticket, handle="googleuser"):
    return {"flow": ticket["flow"], "registration_token": ticket["registration_token"], "handle": handle,
            "key_bundle": _registration_payload()["key_bundle"]}


def test_google_register_and_login_same_immutable_identity(google_env):
    client, factory, token = google_env
    start = options(client); reply = verify(client, start, token(start))
    assert reply.status_code == 200
    ticket = reply.json(); assert ticket["status"] == "NEEDS_HANDLE"
    body = registration(ticket)
    created = client.post("/v1/auth/google/register", json=body)
    assert created.status_code == 200
    login = created.json()["login"]
    with factory() as db:
        assert db.get(AuthCredential, uuid.UUID(login["user"]["user_id"])) is None
        assert db.get(GoogleIdentity, "synthetic-subject").user_id == uuid.UUID(login["user"]["user_id"])
    assert client.post("/v1/auth/google/register", json=body).status_code == 400
    start2 = options(client); again = verify(client, start2, token(start2)).json()
    assert again["status"] == "SIGNED_IN"
    assert again["login"]["user"] == login["user"]
    assert again["login"]["active_key_bundle"] == login["active_key_bundle"]
    assert "registration_token" not in again
    headers = {"Authorization": "Bearer " + login["access_token"]}
    assert client.patch("/v1/me/handle", json={"handle": "changed"}, headers=headers).status_code == 409
    assert client.get("/v1/me", headers=headers).json()["handle"] == "googleuser"


@pytest.mark.parametrize("changes", [
    {"aud": "evil.apps.googleusercontent.com"}, {"azp": "evil.apps.googleusercontent.com"},
    {"iss": "https://evil.example"}, {"nonce": "wrong"}, {"email_verified": False},
    {"sub": ""}, {"exp": 1},
])
def test_rejected_proof_burns_nonce_and_creates_no_session(google_env, changes):
    client, factory, token = google_env; start = options(client)
    assert verify(client, start, token(start, **changes)).status_code == 400
    assert verify(client, start, token(start)).status_code == 400
    with factory() as db:
        assert db.scalar(select(func.count()).select_from(AuthSession)) == 0


def test_invalid_rsa_signature_is_rejected(google_env):
    client, factory, token = google_env; start = options(client)
    signed = token(start); parts = signed.split("."); parts[2] = "A" * len(parts[2])
    assert verify(client, start, ".".join(parts)).status_code == 400


def test_email_match_does_not_link_password_account(google_env):
    client, factory, token = google_env
    _seed(client, email="google@example.com")
    start = options(client); result = verify(client, start, token(start))
    assert result.status_code == 409
    assert result.json()["reason"] == "EXISTING_ACCOUNT_REQUIRES_LINK"
    with factory() as db: assert db.scalar(select(func.count()).select_from(GoogleIdentity)) == 0


def test_handle_conflict_preserves_registration_ticket(google_env):
    client, factory, token = google_env; _seed(client, handle="taken")
    start = options(client); ticket = verify(client, start, token(start)).json()
    body = registration(ticket, "taken")
    assert client.post("/v1/auth/google/register", json=body).status_code == 409
    body["handle"] = "available"
    assert client.post("/v1/auth/google/register", json=body).status_code == 200


def test_registration_ticket_is_secret_and_atomic(google_env):
    client, factory, token = google_env
    start = options(client); ticket = verify(client, start, token(start)).json()
    body = registration(ticket); bad = {**body, "registration_token": "wrong"}
    assert client.post("/v1/auth/google/register", json=bad).status_code == 400
    with ThreadPoolExecutor(2) as pool:
        statuses = list(pool.map(lambda _: client.post("/v1/auth/google/register", json=body).status_code, range(2)))
    assert sorted(statuses) == [200, 400]
    with factory() as db: assert db.scalar(select(func.count()).select_from(AuthSession)) == 1


def test_disabled_account_cannot_mint_another_session(google_env):
    client, factory, token = google_env
    start = options(client)
    ticket = verify(client, start, token(start)).json()
    created = client.post("/v1/auth/google/register", json=registration(ticket)).json()["login"]
    with factory() as db:
        user = db.get(User, uuid.UUID(created["user"]["user_id"]))
        user.disabled_at = datetime.now(timezone.utc)
        db.commit()
    start = options(client)
    assert verify(client, start, token(start)).status_code == 400
    assert verify(client, start, token(start)).status_code == 400
    with factory() as db:
        assert db.scalar(select(func.count()).select_from(AuthSession)) == 1


def test_provider_defaults_off(google_env):
    client, factory, token = google_env
    client.app.state.settings.google_sign_in_enabled = False
    assert client.post("/v1/auth/google/options", json={}).status_code == 404
    assert not Settings(mode="test").google_sign_in_enabled
    with pytest.raises(ValueError): Settings(mode="test", google_sign_in_enabled=True)
    with pytest.raises(ValueError): Settings(mode="test", recovery_enabled=True, google_sign_in_enabled=True)
