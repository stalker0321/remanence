"""Integration tests for the /v1/me endpoint against a temporary PostgreSQL database."""

import base64
import json
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from psycopg import sql
from sqlalchemy import select
from sqlalchemy.engine import make_url
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

from postmark.auth.models import AuthCredential, AuthSession
from postmark.db.session import build_engine, build_session_factory
from postmark.main import create_app
from postmark.settings import AppMode, Settings
from postmark.users.key_models import KeyBundleStatus, UserKeyBundle
from postmark.users.models import User

_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"
_HPKE_KEY = bytes(range(32))
_ED_KEY = bytes(range(32, 64))
_PASSWORD = "correct horse battery staple"


def _hpke_public_key() -> hpke_pb2.HpkePublicKey:
    return hpke_pb2.HpkePublicKey(version=0, params=hpke_pb2.HpkeParams(kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256, kdf=hpke_pb2.HKDF_SHA256, aead=hpke_pb2.AES_256_GCM), public_key=_HPKE_KEY)


def _ed25519_public_key() -> ed25519_pb2.Ed25519PublicKey:
    return ed25519_pb2.Ed25519PublicKey(version=0, key_value=_ED_KEY)


def _key_data(type_url: str, value: bytes) -> tink_pb2.KeyData:
    return tink_pb2.KeyData(type_url=type_url, value=value, key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC)


def _keyset(key_data: tink_pb2.KeyData) -> tink_pb2.Keyset:
    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(tink_pb2.Keyset.Key(key_data=key_data, status=tink_pb2.ENABLED, key_id=1, output_prefix_type=tink_pb2.TINK))
    return keyset


def _b64(data: bytes) -> str:
    return base64.b64encode(data, altchars=b"-_").decode("ascii").rstrip("=")


def _registration_payload(email="alice@example.com", handle="alice") -> dict:
    encryption = _keyset(_key_data("type.googleapis.com/google.crypto.tink.HpkePublicKey", _hpke_public_key().SerializeToString()))
    signing = _keyset(_key_data("type.googleapis.com/google.crypto.tink.Ed25519PublicKey", _ed25519_public_key().SerializeToString()))
    return {
        "email": email,
        "password": _PASSWORD,
        "handle": handle,
        "key_bundle": {
            "key_bundle_id": str(uuid4()),
            "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            "protocol_version": 1,
            "encryption_public_keyset": _b64(encryption.SerializeToString()),
            "signing_public_keyset": _b64(signing.SerializeToString()),
        },
    }


@pytest.fixture()
def me_env(monkeypatch: pytest.MonkeyPatch):
    source = os.environ.get("POSTMARK_TEST_DATABASE_URL")
    if not source:
        pytest.skip("POSTMARK_TEST_DATABASE_URL is not set")
    url = make_url(source)
    database = f"postmark_tmp_{uuid4().hex}"
    admin: psycopg.Connection | None = None
    created = False
    try:
        admin = psycopg.connect(host=url.host, port=url.port, user=url.username, password=url.password, dbname="postgres", autocommit=True)
        admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
        created = True
        for key in list(os.environ):
            if key.upper().startswith("POSTMARK_"):
                monkeypatch.delenv(key, raising=False)
        monkeypatch.setenv("POSTMARK_MODE", "dev")
        monkeypatch.setenv("POSTMARK_DATABASE_URL", url.set(database=database).render_as_string(hide_password=False))
        monkeypatch.setenv("POSTMARK_BLOB_ROOT", "var/test-blobs")
        config = Config(str(_ALEMBIC_INI))
        config.set_main_option("path_separator", "os")
        command.upgrade(config, "head")
        settings = Settings()
        engine = build_engine(settings)
        factory = build_session_factory(engine)
        try:
            app = create_app(settings=settings, session_factory=factory)
            with TestClient(app) as client:
                yield client, factory
        finally:
            engine.dispose()
    finally:
        if admin is not None:
            try:
                if created:
                    admin.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = %s AND pid <> pg_backend_pid()", (database,))
                    admin.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(database)))
            finally:
                admin.close()


def _seed(client: TestClient, **kwargs) -> dict:
    response = client.post("/v1/auth/register", json=_registration_payload(**kwargs))
    assert response.status_code == 201, response.text
    return response.json()


def test_me_returns_exact_keys_and_values(me_env) -> None:
    client, factory = me_env
    seed = _seed(client)
    response = client.get("/v1/me", headers={"Authorization": f"Bearer {seed['access_token']}"})
    assert response.status_code == 200
    body = response.json()
    assert set(body.keys()) == {"user_id", "email", "handle", "created_at", "updated_at", "active_key_bundle"}
    assert body["user_id"] == seed["user"]["user_id"]
    assert body["email"] == "alice@example.com"
    assert body["handle"] == "alice"
    assert body["created_at"].endswith("Z") or body["created_at"].endswith("+00:00")
    assert body["updated_at"].endswith("Z") or body["updated_at"].endswith("+00:00")
    assert body["active_key_bundle"] == {
        "key_bundle_id": seed["active_key_bundle_id"],
        "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        "protocol_version": 1,
        "status": "ACTIVE",
    }


def test_me_response_json_excludes_secrets(me_env) -> None:
    client, factory = me_env
    seed = _seed(client)
    with factory() as session:
        credential = session.scalars(select(AuthCredential)).one()
        bundle = session.scalars(select(UserKeyBundle)).one()
        phc_hash = credential.password_hash
        enc_b64 = _b64(bundle.encryption_public_keyset)
        sign_b64 = _b64(bundle.signing_public_keyset)
        session_ids = [str(row.id) for row in session.scalars(select(AuthSession)).all()]
    response = client.get("/v1/me", headers={"Authorization": f"Bearer {seed['access_token']}"})
    assert response.status_code == 200
    serialized = json.dumps(response.json())
    for secret in (_PASSWORD, seed["access_token"], seed["refresh_token"], phc_hash, enc_b64, sign_b64):
        assert secret not in serialized
    for session_id in session_ids:
        assert session_id not in serialized


def test_missing_wrong_expired_revoked_rotated_identical_401(me_env) -> None:
    client, factory = me_env
    seed = _seed(client)
    cases = {
        "missing": {},
        "wrong": {"Authorization": "Bearer pm_at_" + "A" * 43},
        "revoked": None,
    }
    # revoked via logout
    client.post("/v1/auth/logout", headers={"Authorization": f"Bearer {seed['access_token']}"})
    # rotated via refresh
    seed2 = _seed(client, email="bob@example.com", handle="bob")
    refresh = client.post("/v1/auth/refresh", json={"refresh_token": seed2["refresh_token"]})
    assert refresh.status_code == 200
    rotated_old = seed2["access_token"]
    cases["rotated"] = {"Authorization": f"Bearer {rotated_old}"}
    responses = []
    for label, headers in cases.items():
        response = client.get("/v1/me", headers=headers if headers is not None else {})
        assert response.status_code == 401, label
        assert response.headers["content-type"].startswith("application/problem+json")
        assert response.headers["www-authenticate"] == "Bearer"
        responses.append(response)
    for response in responses[1:]:
        assert response.content == responses[0].content


def test_handle_email_belong_to_authenticated_user(me_env) -> None:
    client, _ = me_env
    seed_a = _seed(client, email="alice@example.com", handle="alice")
    seed_b = _seed(client, email="bob@example.com", handle="bob")
    response_a = client.get("/v1/me", headers={"Authorization": f"Bearer {seed_a['access_token']}"})
    response_b = client.get("/v1/me", headers={"Authorization": f"Bearer {seed_b['access_token']}"})
    assert response_a.json()["email"] == "alice@example.com"
    assert response_a.json()["handle"] == "alice"
    assert response_b.json()["email"] == "bob@example.com"
    assert response_b.json()["handle"] == "bob"


def test_missing_active_bundle_maps_fixed_409(me_env) -> None:
    client, factory = me_env
    seed = _seed(client)
    with factory() as session:
        user = session.scalars(select(User)).one()
        bundle = session.scalars(select(UserKeyBundle)).one()
        bundle.status = KeyBundleStatus.RETIRED
        session.commit()
    response = client.get("/v1/me", headers={"Authorization": f"Bearer {seed['access_token']}"})
    assert response.status_code == 409
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json() == {
        "type": "https://postmark.invalid/problems/account-state-invalid",
        "title": "Account state invalid",
        "status": 409,
        "code": "ACCOUNT_STATE_INVALID",
    }


def test_health_unchanged(me_env) -> None:
    client, _ = me_env
    assert client.get("/healthz").status_code == 200