"""Integration tests for the public key-bundle-by-ID endpoint against a temporary PostgreSQL database."""

import base64
import os
import uuid
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
from postmark.settings import Settings
from postmark.users.key_models import KeyBundleStatus, UserKeyBundle

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
def bundle_env(monkeypatch: pytest.MonkeyPatch):
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


def _set_status(factory, bundle_id: str, status: KeyBundleStatus) -> None:
    with factory() as session:
        row = session.get(UserKeyBundle, uuid.UUID(bundle_id))
        assert row is not None
        row.status = status
        session.commit()


def test_active_bundle_lookup_returns_exact_public_portion(bundle_env) -> None:
    client, _ = bundle_env
    payload = _registration_payload()
    seed_response = client.post("/v1/auth/register", json=payload)
    assert seed_response.status_code == 201, seed_response.text
    viewer_headers = {"Authorization": f"Bearer {seed_response.json()['access_token']}"}
    response = client.get(f"/v1/directory/key-bundles/{payload['key_bundle']['key_bundle_id']}", headers=viewer_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["key_bundle_id"] == payload["key_bundle"]["key_bundle_id"]
    assert body["suite"] == "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
    assert body["protocol_version"] == 1
    assert body["status"] == "ACTIVE"
    assert body["encryption_public_keyset"] == payload["key_bundle"]["encryption_public_keyset"]
    assert body["signing_public_keyset"] == payload["key_bundle"]["signing_public_keyset"]
    assert set(body) == {
        "key_bundle_id",
        "user_id",
        "suite",
        "protocol_version",
        "encryption_public_keyset",
        "signing_public_keyset",
        "status",
        "created_at",
    }


def test_retired_and_revoked_bundles_remain_available_with_status(bundle_env) -> None:
    client, factory = bundle_env
    seed = _seed(client)
    bundle_id = seed["active_key_bundle_id"]
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    for status in (KeyBundleStatus.RETIRED, KeyBundleStatus.ACTIVE, KeyBundleStatus.REVOKED):
        _set_status(factory, bundle_id, status)
        response = client.get(f"/v1/directory/key-bundles/{bundle_id}", headers=headers)
        assert response.status_code == 200
        assert response.json()["status"] == status.value


def test_unknown_or_malformed_id_404_key_bundle_not_found(bundle_env) -> None:
    client, _ = bundle_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    for bad in [str(uuid4()), "not-a-uuid", "alice"]:
        response = client.get(f"/v1/directory/key-bundles/{bad}", headers=headers)
        assert response.status_code == 404, bad
        assert response.headers["content-type"].startswith("application/problem+json")
        assert response.json()["code"] == "KEY_BUNDLE_NOT_FOUND"


def test_lookup_never_exposes_email_or_private_material(bundle_env) -> None:
    client, factory = bundle_env
    seed = _seed(client)
    headers = {"Authorization": f"Bearer {seed['access_token']}"}
    response = client.get(f"/v1/directory/key-bundles/{seed['active_key_bundle_id']}", headers=headers)
    assert response.status_code == 200
    serialized = str(response.json())
    assert "alice@example.com" not in serialized
    assert "email" not in serialized.lower()
    assert "private" not in serialized.lower()


def test_unauthenticated_lookup_401(bundle_env) -> None:
    client, _ = bundle_env
    seed = _seed(client)
    response = client.get(f"/v1/directory/key-bundles/{seed['active_key_bundle_id']}")
    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"
