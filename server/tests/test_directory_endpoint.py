"""Integration tests for the handle-directory endpoint against a temporary PostgreSQL database."""

import base64
import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from psycopg import sql
from sqlalchemy.engine import make_url
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

from postmark.db.session import build_engine, build_session_factory
from postmark.main import create_app
from postmark.settings import AppMode, Settings

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
def directory_env(monkeypatch: pytest.MonkeyPatch):
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


def test_lookup_returns_user_and_active_bundle_without_email(directory_env) -> None:
    client, _ = directory_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    response = client.get("/v1/directory/handles/@Alice", headers=headers)
    assert response.status_code == 200
    body = response.json()
    assert body["user"] == {"user_id": seed["user"]["user_id"], "handle": "alice"}
    bundle = body["key_bundle"]
    assert bundle["key_bundle_id"] == seed["active_key_bundle_id"]
    assert bundle["user_id"] == seed["user"]["user_id"]
    assert bundle["suite"] == "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
    assert bundle["protocol_version"] == 1
    assert bundle["status"] == "ACTIVE"
    assert isinstance(bundle["created_at"], str) and bundle["created_at"].endswith("Z")
    assert set(bundle) == {
        "key_bundle_id",
        "user_id",
        "suite",
        "protocol_version",
        "encryption_public_keyset",
        "signing_public_keyset",
        "status",
        "created_at",
    }
    assert body["directory_version"]
    serialized = str(body)
    assert "alice@example.com" not in serialized
    assert "email" not in serialized


def test_lookup_returns_exact_stored_public_keysets(directory_env) -> None:
    client, _ = directory_env
    payload = _registration_payload()
    response = client.post("/v1/auth/register", json=payload)
    assert response.status_code == 201, response.text
    headers = {"Authorization": f"Bearer {response.json()['access_token']}"}
    body = client.get("/v1/directory/handles/alice", headers=headers).json()
    assert body["key_bundle"]["encryption_public_keyset"] == payload["key_bundle"]["encryption_public_keyset"]
    assert body["key_bundle"]["signing_public_keyset"] == payload["key_bundle"]["signing_public_keyset"]


def test_lookup_directory_version_is_stable_per_active_bundle(directory_env) -> None:
    client, _ = directory_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    first = client.get("/v1/directory/handles/alice", headers=headers).json()["directory_version"]
    second = client.get("/v1/directory/handles/alice", headers=headers).json()["directory_version"]
    assert first == second


def test_unknown_handle_404_handle_not_found(directory_env) -> None:
    client, _ = directory_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    for handle in ["nobody", "@ghost_user", "ab", "BAD!", "%20"]:
        response = client.get(f"/v1/directory/handles/{handle}", headers=headers)
        assert response.status_code == 404, handle
        assert response.headers["content-type"].startswith("application/problem+json")
        assert response.json()["code"] == "HANDLE_NOT_FOUND"


def test_unauthenticated_lookup_401(directory_env) -> None:
    client, _ = directory_env
    _seed(client)
    response = client.get("/v1/directory/handles/alice")
    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"
