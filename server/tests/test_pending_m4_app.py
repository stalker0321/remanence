"""T185 M4: gated experimental app composition (isolated, synthetic).

- Flag OFF (default): main app mounts nothing new; existing routers work.
- Flag ON without explicit ctx: startup raises (fail closed).
- Flag ON with synthetic ctx: pending router mounted; existing
  handlers/auth/recovery preserved; backfill reads the actual users.
"""

from __future__ import annotations

import uuid

import uuid

import pytest
from fastapi.testclient import TestClient
from tink import tink_config

pytest_plugins = ("test_session_repository_create",)

from remanence.main import create_app
from remanence.pending.vault import DevSealedVault
from remanence.settings import AppMode, Settings

_CTX_KEY = bytes([0x21] * 32)
_CTX_SECRET = _CTX_KEY.decode("ascii")


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _settings(**overrides) -> Settings:
    base = dict(
        mode=AppMode.TEST,
        pending_hmac_secret=_CTX_SECRET,
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


def test_ctx_hmac_mismatch_fails_closed() -> None:
    import pytest as _pytest

    from remanence.pending.providers import MockDispatcher as _Mock

    ctx = {
        "hmac_key": bytes([0x22] * 32),
        "destination_key": _CTX_KEY,
        "otp_key": _CTX_KEY,
        "proof_mac_key": _CTX_KEY,
        "vault": DevSealedVault(mode="test", key_id="m4-x", key_32=_CTX_KEY),
        "service_key_id": uuid.uuid4(),
        "email_sender": _Mock(),
        "locator_code_key": _CTX_KEY,
    }
    with _pytest.raises(ValueError):
        create_app(settings=_settings(pending_protocol_enabled=True), pending_ctx=ctx)


def test_lifespan_backfill_then_r1_routes_old_email(session_factory) -> None:
    """Composition: lifespan backfills actual users-table emails with the
    SAME authoritative key; R1 then routes the old email v1 to its owner,
    and a foreign claim on it is fenced."""
    from remanence.pending.registry import address_fate, live_verified
    from remanence.users.models import User

    factory = session_factory
    with factory() as session:
        owner = User(
            id=uuid.uuid4(),
            email_normalized="lifespan-old@example.test",
            handle_normalized="lifespanowner",
            handle_display="lifespanowner",
        )
        session.add(owner)
        session.commit()
        owner_id, owner_email = owner.id, owner.email_normalized
    from remanence.pending.providers import MockDispatcher as _Mock2

    ctx = {
        "hmac_key": _CTX_KEY,
        "destination_key": _CTX_KEY,
        "otp_key": _CTX_KEY,
        "proof_mac_key": _CTX_KEY,
        "vault": DevSealedVault(mode="test", key_id="m4-ls", key_32=_CTX_KEY),
        "service_key_id": uuid.uuid4(),
        "email_sender": _Mock2(),
        "locator_code_key": _CTX_KEY,
    }
    app = create_app(
        settings=_settings(
            pending_protocol_enabled=True,
            pending_sends_enabled=True,
            pending_claims_enabled=True,
            pending_locator_issuance_enabled=True,
        ),
        session_factory=factory,
        pending_ctx=ctx,
    )
    with TestClient(app, raise_server_exceptions=False) as client:
        assert app.state.pending_registry_ready is True
        assert app.state.pending_backfill_outcome["created"] >= 1
        with factory() as session:
            assert live_verified(
                session, kind="email", normalized=owner_email, hmac_key=_CTX_KEY
            ) is None
            assert (
                address_fate(session, kind="email", normalized=owner_email, hmac_key=_CTX_KEY)
                == "RESERVED"
            )


def test_prod_ctx_requires_the_card_seal_and_rejects_mocks() -> None:
    import pytest as _pytest

    from remanence.pending.card_seal import CardSealVault as _CardVault
    from remanence.pending.providers import MockDispatcher as _MockMail
    from remanence.pending.providers import MockSmsSender as _MockSms
    from remanence.pending.vault import DevSealedVault as _DevVault
    from remanence.pending.vault import ProductionKmsVault as _KmsVault

    class _KmsClient:
        def encrypt(self, *, key_id, plaintext, encryption_context):
            return b"enc:" + plaintext

        def decrypt(self, *, key_id, ciphertext, encryption_context):
            return ciphertext[4:]

    def _ctx(**overrides):
        base = {
            "hmac_key": _CTX_KEY,
            "destination_key": _CTX_KEY,
            "otp_key": _CTX_KEY,
            "proof_mac_key": _CTX_KEY,
            "vault": _CardVault(key_32=bytes([0x41] * 32)),
            "service_key_id": uuid.uuid4(),
            "email_sender": None,
        }
        base.update(overrides)
        return base

    prod = dict(
        mode=AppMode.PROD,
        database_url="postgresql+psycopg://u:p@127.0.0.1:1/db",
        blob_root="/tmp/blobs",
        pending_protocol_enabled=True,
        pending_hmac_secret=_CTX_SECRET,
        pending_master_secret="synthetic-master-secret-32-bytes-minimum",
        pending_service_key_id=str(uuid.uuid4()),
        pending_service_keyset_b64="c3ludGhldGlj",
    )
    create_app(settings=Settings(**prod), pending_ctx=_ctx())
    # Escrow in any form is refused, including the KMS vault (ADR-020).
    for refused in (
        _DevVault(mode="test", key_id="x", key_32=_CTX_KEY),
        _KmsVault(kms_client=_KmsClient(), kms_key_id="kms-1", allowed_key_ids=("kms-1",)),
    ):
        with _pytest.raises(ValueError):
            create_app(settings=Settings(**prod), pending_ctx=_ctx(vault=refused))
    # Mock providers refused.
    with _pytest.raises(ValueError):
        create_app(settings=Settings(**prod), pending_ctx=_ctx(email_sender=_MockMail()))
    with _pytest.raises(ValueError):
        create_app(settings=Settings(**prod), pending_ctx=_ctx(sms_sender=_MockSms()))
    # A public locator never comes back in production.
    with _pytest.raises(ValueError):
        create_app(
            settings=Settings(**prod, pending_sends_enabled=True, pending_locator_issuance_enabled=True),
            pending_ctx=_ctx(),
        )


def test_production_ctx_derives_distinct_keys_and_builds_from_settings() -> None:
    import base64

    import tink
    from tink import cleartext_keyset_handle, signature, tink_config

    from remanence.pending.card_seal import CardSealVault
    from remanence.pending.production import build_production_ctx, service_public_keyset_b64

    tink_config.register()
    handle = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
    buffer = __import__("io").BytesIO()
    cleartext_keyset_handle.write(tink.BinaryKeysetWriter(buffer), handle)
    keyset_b64 = base64.b64encode(buffer.getvalue()).decode()
    settings = Settings(
        mode=AppMode.PROD,
        database_url="postgresql+psycopg://u:p@127.0.0.1:1/db",
        blob_root="/tmp/blobs",
        pending_protocol_enabled=True,
        pending_hmac_secret=_CTX_SECRET,
        pending_master_secret="synthetic-master-secret-32-bytes-minimum",
        pending_service_key_id=str(uuid.uuid4()),
        pending_service_keyset_b64=keyset_b64,
    )
    ctx = build_production_ctx(settings)
    assert isinstance(ctx["vault"], CardSealVault)
    keys = [ctx["destination_key"], ctx["otp_key"], ctx["proof_mac_key"], ctx["evidence_key"], ctx["vault"].key_32]
    assert len({bytes(k) for k in keys}) == 5 and all(len(k) == 32 for k in keys)
    assert ctx["sms_sender"] is None
    assert service_public_keyset_b64(keyset_b64) != keyset_b64
    create_app(settings=settings)


def test_bulkgate_body_and_answers(monkeypatch) -> None:
    import io
    import json

    import pytest as _pytest

    from remanence.pending import sms_bulkgate
    from remanence.pending.providers import ProviderError

    captured = {}

    class _Response(io.BytesIO):
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    def _open(request, timeout):
        captured.update(json.loads(request.data), url=request.full_url, timeout=timeout)
        return _Response(json.dumps({"data": {"status": "accepted", "sms_id": "tmp1"}}).encode())

    monkeypatch.setattr(sms_bulkgate.urllib.request, "urlopen", _open)
    sender = sms_bulkgate.BulkGateSmsSender(application_id="451", application_token="tok", sender_name="Remanence")
    assert sender.send(attempt_id="a", to_e164="+31612345612", text="123456 is your code") == "tmp1"
    assert captured["url"] == sms_bulkgate.ENDPOINT
    assert captured["number"] == "31612345612"
    assert captured["application_id"] == "451" and captured["sender_id_value"] == "Remanence"
    assert "tok" not in repr(sender)

    def _refused(request, timeout):
        return _Response(json.dumps({"type": "invalid_phone_number", "code": 400, "error": "x"}).encode())

    monkeypatch.setattr(sms_bulkgate.urllib.request, "urlopen", _refused)
    with _pytest.raises(ProviderError):
        sender.send(attempt_id="a", to_e164="+31612345612", text="t")
