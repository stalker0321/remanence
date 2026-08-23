"""Integration tests for the registration endpoint against a temporary PostgreSQL database."""

import base64
import json
import os
from datetime import timezone
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
from postmark.auth.tokens import hash_opaque_token
from postmark.db.session import build_engine, build_session_factory
from postmark.main import create_app
from postmark.settings import AppMode, Settings
from postmark.users.key_models import UserKeyBundle
from postmark.users.models import User

_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"
_HPKE_KEY = bytes(range(32))
_ED_KEY = bytes(range(32, 64))


def _hpke_public_key() -> hpke_pb2.HpkePublicKey:
    return hpke_pb2.HpkePublicKey(
        version=0,
        params=hpke_pb2.HpkeParams(
            kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256,
            kdf=hpke_pb2.HKDF_SHA256,
            aead=hpke_pb2.AES_256_GCM,
        ),
        public_key=_HPKE_KEY,
    )


def _ed25519_public_key() -> ed25519_pb2.Ed25519PublicKey:
    return ed25519_pb2.Ed25519PublicKey(version=0, key_value=_ED_KEY)


def _key_data(type_url: str, value: bytes) -> tink_pb2.KeyData:
    return tink_pb2.KeyData(
        type_url=type_url,
        value=value,
        key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC,
    )


def _keyset(key_data: tink_pb2.KeyData) -> tink_pb2.Keyset:
    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=key_data,
            status=tink_pb2.ENABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset


def _b64(data: bytes) -> str:
    return base64.b64encode(data, altchars=b"-_").decode("ascii").rstrip("=")


def _valid_payload() -> dict:
    encryption = _keyset(_key_data("type.googleapis.com/google.crypto.tink.HpkePublicKey", _hpke_public_key().SerializeToString()))
    signing = _keyset(_key_data("type.googleapis.com/google.crypto.tink.Ed25519PublicKey", _ed25519_public_key().SerializeToString()))
    return {
        "email": "alice@example.com",
        "password": "correct horse battery staple",
        "handle": "alice",
        "key_bundle": {
            "key_bundle_id": "00010203-0405-0607-0809-0a0b0c0d0e0f",
            "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            "protocol_version": 1,
            "encryption_public_keyset": _b64(encryption.SerializeToString()),
            "signing_public_keyset": _b64(signing.SerializeToString()),
        },
    }


@pytest.fixture()
def client_factory(monkeypatch: pytest.MonkeyPatch):
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


def _register(client: TestClient) -> dict:
    response = client.post("/v1/auth/register", json=_valid_payload())
    assert response.status_code == 201
    assert response.headers["content-type"].startswith("application/json")
    return response.json()


def test_valid_registration_201_and_four_rows(client_factory) -> None:
    client, factory = client_factory
    body = _register(client)
    assert body["access_token"].startswith("pm_at_")
    assert body["refresh_token"].startswith("pm_rt_")
    assert body["access_expires_at"].endswith("+00:00") or body["access_expires_at"].endswith("Z")
    assert body["refresh_expires_at"].endswith("+00:00") or body["refresh_expires_at"].endswith("Z")
    with factory() as session:
        assert len(session.scalars(select(User)).all()) == 1
        assert len(session.scalars(select(AuthCredential)).all()) == 1
        assert len(session.scalars(select(UserKeyBundle)).all()) == 1
        assert len(session.scalars(select(AuthSession)).all()) == 1
        auth_session = session.scalars(select(AuthSession)).one()
        assert auth_session.access_token_hash != body["access_token"].encode()
        assert auth_session.refresh_token_hash != body["refresh_token"].encode()
        assert auth_session.access_token_hash == hash_opaque_token(body["access_token"])
        assert auth_session.refresh_token_hash == hash_opaque_token(body["refresh_token"])


def test_duplicate_email_409_no_partial_rows(client_factory) -> None:
    client, factory = client_factory
    _register(client)
    second = _valid_payload()
    second["handle"] = "bob"
    response = client.post("/v1/auth/register", json=second)
    assert response.status_code == 409
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body == {
        "type": "https://postmark.invalid/problems/account-conflict",
        "title": "Account conflict",
        "status": 409,
        "code": "ACCOUNT_CONFLICT",
    }
    serialized = str(body)
    for secret in ("alice@example.com", "bob", "correct horse battery staple", second["key_bundle"]["encryption_public_keyset"], second["key_bundle"]["signing_public_keyset"]):
        assert secret not in serialized
    with factory() as session:
        assert len(session.scalars(select(User)).all()) == 1
        assert len(session.scalars(select(AuthSession)).all()) == 1


def test_duplicate_handle_409_indistinguishable(client_factory) -> None:
    client, factory = client_factory
    _register(client)
    second = _valid_payload()
    second["email"] = "bob@example.com"
    response = client.post("/v1/auth/register", json=second)
    assert response.status_code == 409
    body = response.json()
    assert body == {
        "type": "https://postmark.invalid/problems/account-conflict",
        "title": "Account conflict",
        "status": 409,
        "code": "ACCOUNT_CONFLICT",
    }
    assert "bob@example.com" not in json.dumps(body)
    with factory() as session:
        assert len(session.scalars(select(User)).all()) == 1


def test_malformed_inputs_fixed_422(client_factory) -> None:
    client, _ = client_factory
    bad_payloads = [
        {**_valid_payload(), "password": "short"},
        {**_valid_payload(), "key_bundle": {**_valid_payload()["key_bundle"], "encryption_public_keyset": "A" * 6000}},
        {**_valid_payload(), "key_bundle": {**_valid_payload()["key_bundle"], "signing_public_keyset": "not-base64!!"}},
    ]
    expected = {
        "type": "https://postmark.invalid/problems/invalid-request",
        "title": "Invalid request",
        "status": 422,
        "code": "INVALID_REQUEST",
    }
    for payload in bad_payloads:
        response = client.post("/v1/auth/register", json=payload)
        assert response.status_code == 422
        assert response.headers["content-type"].startswith("application/problem+json")
        body = response.json()
        assert body == expected
        serialized = json.dumps(body)
        assert "short" not in serialized
        assert payload["key_bundle"]["encryption_public_keyset"] not in serialized
        assert payload["key_bundle"]["signing_public_keyset"] not in serialized


def test_test_app_without_factory_health_ok_register_503() -> None:
    app = create_app(settings=Settings(mode=AppMode.TEST))
    client = TestClient(app)
    assert client.get("/healthz").status_code == 200
    response = client.post("/v1/auth/register", json=_valid_payload())
    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body == {
        "type": "https://postmark.invalid/problems/service-unavailable",
        "title": "Service unavailable",
        "status": 503,
        "code": "SERVICE_UNAVAILABLE",
    }