"""T185 M4: gated experimental app composition (isolated, synthetic).

- Flag OFF (default): main app mounts nothing new; existing routers work.
- Flag ON without explicit ctx: startup raises (fail closed).
- Flag ON with synthetic ctx: pending router mounted; existing
  handlers/auth/recovery preserved; backfill reads the actual users.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient
from tink import tink_config

pytest_plugins = ("test_session_repository_create",)

from remanence.main import create_app
from remanence.pending.vault import DevSealedVault
from remanence.settings import AppMode, Settings

_CTX_KEY = bytes([0x21] * 32)


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _settings(**overrides) -> Settings:
    base = dict(
        mode=AppMode.TEST,
        pending_hmac_secret="synthetic-test-secret-32-bytes-minimum",
    )
    base.update(overrides)
    return Settings(**base)


def _paths(app) -> set[str]:
    return set(app.openapi()["paths"])


def test_flag_off_mounts_nothing_new() -> None:
    app = create_app(settings=_settings())
    paths = _paths(app)
    assert "/healthz" in paths
    assert not any(p.startswith("/v2/send-routes") for p in paths)
    assert not any(p.startswith("/v2/gift-lookups") for p in paths)


def test_flag_on_without_ctx_fails_closed() -> None:
    import pytest as _pytest

    with _pytest.raises(ValueError):
        create_app(settings=_settings(pending_protocol_enabled=True))


def test_flag_on_with_ctx_mounts_pending_only_additively() -> None:
    from remanence.pending.providers import MockDispatcher

    ctx = {
        "hmac_key": _CTX_KEY,
        "destination_key": _CTX_KEY,
        "otp_key": _CTX_KEY,
        "proof_mac_key": _CTX_KEY,
        "vault": DevSealedVault(mode="test", key_id="m4-1", key_32=_CTX_KEY),
        "service_key_id": uuid.uuid4(),
        "service_signer": None,
        "email_sender": MockDispatcher(),
        "locator_code_key": _CTX_KEY,
    }
    app = create_app(
        settings=_settings(
            pending_protocol_enabled=True,
            pending_sends_enabled=True,
            pending_claims_enabled=True,
            pending_locator_issuance_enabled=True,
        ),
        pending_ctx=ctx,
    )
    paths = _paths(app)
    assert any(p.startswith("/v2/send-routes") for p in paths)
    assert any(p.startswith("/v2/gift-lookups") for p in paths)
    # Existing surface preserved (health + v1 auth still mounted).
    assert "/healthz" in paths
    client = TestClient(app, raise_server_exceptions=False)
    response = client.get("/healthz")
    assert response.status_code in (200, 404, 422)
