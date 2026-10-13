"""T185 R2: known-v1 frozen route + common locator extension (real PG).

v1 finalize with the negotiated locator capability
(`X-Remanence-Profile-Version: 2`, the same token T186 already sends
for pending — no second capability token) commits a common 5-char
locator in the SAME transaction and returns the plaintext code once.
Without the capability (or flag/ctx/custody) finalize is byte-for-byte
legacy: no row, legacy triple. v1 signed/encrypted bytes, verifiers,
fixtures, and escrow (no vault call on this path) are untouched.

Isolated synthetic PG + synthetic keys only. No Gradle/ADB, no live
prefix, no provider sends.
"""

from __future__ import annotations

import hashlib as _hashlib
import re
import uuid
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select
pytest_plugins = ("test_registration_endpoint",)

from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.models import Capsule, CapsuleState
from remanence.main import create_app
from remanence.pending.claim_models import PendingDelivery
from remanence.pending.models import GiftLocator, UsedLocatorTombstone
from remanence.settings import AppMode, Settings

from test_capsule_finalize_endpoint import _live_draft, _post_finalize

LOCATOR_ALPHABET = frozenset("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
CAPABILITY = {"X-Remanence-Profile-Version": "2"}


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    from tink import tink_config as _tink_config

    _tink_config.register()

_HMAC_SECRET = "0123456789abcdef0123456789abcdef"
_HMAC_KEY = _HMAC_SECRET.encode("ascii")
_CODE_KEY = bytes([0x42] * 32)
_DEV_VAULT_KEY = bytes([0x43] * 32)


@pytest.fixture()
def r2_client(monkeypatch: pytest.MonkeyPatch, tmp_path):
    """Full app with the pending master flag + issuance + synthetic ctx
    (TEST mode). v1 routes always mounted; pending router mounted only
    via master+ctx. Unique synthetic PG database per test."""
    import os

    import psycopg
    from alembic import command
    from alembic.config import Config
    from psycopg import sql
    from sqlalchemy.engine import make_url

    from remanence.db.session import build_engine, build_session_factory
    from remanence.pending.providers import MockDispatcher
    from remanence.pending.vault import DevSealedVault

    source = os.environ.get("REMANENCE_TEST_DATABASE_URL")
    if not source:
        pytest.skip("REMANENCE_TEST_DATABASE_URL is not set")
    url = make_url(source)
    database = f"remanence_tmp_{uuid4().hex}"
    admin = psycopg.connect(
        host=url.host, port=url.port, user=url.username,
        password=url.password, dbname="postgres", autocommit=True,
    )
    try:
        admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
        for key in list(os.environ):
            if key.upper().startswith("REMANENCE_"):
                monkeypatch.delenv(key, raising=False)
        monkeypatch.setenv("REMANENCE_MODE", "dev")
        monkeypatch.setenv(
            "REMANENCE_DATABASE_URL",
            url.set(database=database).render_as_string(hide_password=False),
        )
        monkeypatch.setenv("REMANENCE_BLOB_ROOT", "var/test-blobs")
        from pathlib import Path as _Path

        _alembic = _Path(__file__).resolve().parents[1] / "alembic.ini"
        config = Config(str(_alembic))
        config.set_main_option("path_separator", "os")
        command.upgrade(config, "head")
        settings = Settings(
            mode=AppMode.TEST,
            pending_protocol_enabled=True,
            pending_claims_enabled=True,
            pending_locator_issuance_enabled=True,
            pending_hmac_secret=_HMAC_SECRET,
        )
        engine = build_engine(settings)
        factory = build_session_factory(engine)
        ctx = {
            "hmac_key": _HMAC_KEY,
            "destination_key": _HMAC_KEY,
            "otp_key": _HMAC_KEY,
            "proof_mac_key": _HMAC_KEY,
            "vault": DevSealedVault(mode="test", key_id="r2-1", key_32=_DEV_VAULT_KEY),
            "service_key_id": uuid4(),
            "service_signer": None,
            "email_sender": MockDispatcher(),
            "locator_code_key": _CODE_KEY,
        }
        try:
            app = create_app(settings=settings, session_factory=factory, pending_ctx=ctx)
            with TestClient(app) as client:
                yield client, factory
        finally:
            engine.dispose()
    finally:
        try:
            admin.execute(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                "WHERE datname = %s AND pid <> pg_backend_pid()",
                (database,),
            )
            admin.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(database)))
        finally:
            admin.close()


def _draft_flow(client: TestClient, factory, tmp_path):
    return _live_draft(client, factory, tmp_path)


def test_finalize_without_capability_is_legacy(r2_client, tmp_path) -> None:
    """No capability header: exact legacy triple, no locator row, v1
    envelope bytes stored untouched."""
    client, factory = r2_client
    sender, _recipient, draft, body, statement, signature = _draft_flow(client, factory, tmp_path)
    first = _post_finalize(client, sender["access_token"], draft["capsule_id"], body)
    assert first.status_code == 201, first.text
    assert set(first.json()) == {"capsule_id", "state", "ready_at"}
    replay = _post_finalize(client, sender["access_token"], draft["capsule_id"], body)
    assert replay.status_code == 200
    assert replay.json() == first.json()
    with factory() as session:
        capsule_id = UUID(draft["capsule_id"])
        assert session.scalar(select(GiftLocator).where(GiftLocator.capsule_id == capsule_id)) is None
        capsule = session.get(Capsule, capsule_id)
        assert capsule.state is CapsuleState.READY
        assert bytes(capsule.signed_statement) == statement
        assert bytes(capsule.publish_signature) == signature
        assert bytes(capsule.signed_statement_sha256) == _hashlib.sha256(statement).digest()
        envelope = session.get(CapsuleEnvelope, capsule_id)
        assert envelope is not None and bytes(envelope.ciphertext) == b"hpke-live"
        assert len(bytes(capsule.publish_signature)) == 69


def test_finalize_with_capability_issues_locator(r2_client, tmp_path) -> None:
    """Negotiated finalize: ACTIVE locator in the same transaction,
    additive code+hint beside the exact legacy triple. Same v1 envelope
    bytes as the legacy path; no escrow/delivery rows, no vault writes."""
    client, factory = r2_client
    sender, recipient, draft, body, statement, signature = _draft_flow(client, factory, tmp_path)
    first = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert first.status_code == 201, first.text
    payload = first.json()
    assert set(payload) == {"capsule_id", "state", "ready_at", "locator_code", "locator_hint"}
    assert payload["capsule_id"] == draft["capsule_id"] and payload["state"] == "READY"
    code = payload["locator_code"]
    assert isinstance(code, str) and len(code) == 5 and all(c in LOCATOR_ALPHABET for c in code)
    with factory() as session:
        from remanence.users.models import User as _User

        capsule_id = UUID(draft["capsule_id"])
        row = session.scalar(select(GiftLocator).where(GiftLocator.capsule_id == capsule_id))
        assert row is not None and row.state == "ACTIVE"
        assert row.code_ciphertext is not None
        capsule = session.get(Capsule, capsule_id)
        recipient_email = session.scalar(
            select(_User.email_normalized).where(_User.id == capsule.recipient_user_id)
        )
        # Hint is the real minimal mask of the recipient address, never
        # the literal "pending".
        assert recipient_email and "@" in recipient_email
        assert row.hint_mask == recipient_email[0] + "•••@•••"
        assert payload["locator_hint"] == row.hint_mask
        # v1 artifacts byte-identical in kind to the legacy path.
        assert bytes(capsule.signed_statement) == statement
        assert bytes(capsule.publish_signature) == signature
        assert session.get(CapsuleEnvelope, capsule_id) is not None
        # No pending escrow machinery touched on the v1 path.
        assert session.scalar(
            select(PendingDelivery).where(PendingDelivery.capsule_id == capsule_id)
        ) is None


def test_capable_replay_returns_same_code(r2_client, tmp_path) -> None:
    client, factory = r2_client
    sender, _recipient, draft, body, _s, _g = _draft_flow(client, factory, tmp_path)
    first = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert first.status_code == 201, first.text
    code = first.json()["locator_code"]
    with factory() as session:
        row_id = session.scalar(
            select(GiftLocator.id).where(GiftLocator.capsule_id == UUID(draft["capsule_id"]))
        )
    replay = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert replay.status_code == 200
    assert replay.json()["locator_code"] == code
    assert replay.json()["locator_hint"] == first.json()["locator_hint"]
    with factory() as session:
        assert session.scalar(
            select(GiftLocator.id).where(GiftLocator.capsule_id == UUID(draft["capsule_id"]))
        ) == row_id
        assert session.scalar(select(func.count()).select_from(GiftLocator)) == 1


def test_capable_replay_without_prior_issuance_does_not_retro_issue(
    r2_client, tmp_path
) -> None:
    """Frozen semantic: first finalize without capability leaves no
    locator; a later capable replay reads (nothing) but never allocates."""
    client, factory = r2_client
    sender, _recipient, draft, body, _s, _g = _draft_flow(client, factory, tmp_path)
    first = _post_finalize(client, sender["access_token"], draft["capsule_id"], body)
    assert first.status_code == 201, first.text
    replay = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert replay.status_code == 200
    assert set(replay.json()) == {"capsule_id", "state", "ready_at"}
    with factory() as session:
        assert session.scalar(
            select(GiftLocator).where(GiftLocator.capsule_id == UUID(draft["capsule_id"]))
        ) is None


def test_no_custody_ctx_means_legacy_even_when_capable(client_factory, tmp_path) -> None:
    """No pending custody context (plain app): capable header alone
    issues nothing — byte-identical legacy shape."""
    client, factory = client_factory
    from test_capsule_finalize_endpoint import _live_draft as _plain_draft

    sender, _recipient, draft, body, _s, _g = _plain_draft(client, factory, tmp_path)
    response = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert response.status_code == 201, response.text
    assert set(response.json()) == {"capsule_id", "state", "ready_at"}
    with factory() as session:
        assert session.scalar(
            select(GiftLocator).where(GiftLocator.capsule_id == UUID(draft["capsule_id"]))
        ) is None


def test_old_client_gated_before_control_and_material(r2_client, tmp_path) -> None:
    """Locator-bearing capsule + old client (no header): 426 on
    material-synced, blob download, and first-open BEFORE any admission
    write. Capable client proceeds on all three."""
    client, factory = r2_client
    sender, recipient, draft, body, _s, _g = _draft_flow(client, factory, tmp_path)
    finalized = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert finalized.status_code == 201, finalized.text
    capsule_id = draft["capsule_id"]
    recipient_token = recipient["access_token"]
    blob_id = draft["blobs"][0]["blob_id"]

    def _authed_old(method: str, path: str, **kwargs):
        headers = {"Authorization": f"Bearer {recipient_token}"}
        return getattr(client, method)(path, headers=headers, **kwargs)

    def _authed_new(method: str, path: str, **kwargs):
        headers = {"Authorization": f"Bearer {recipient_token}", **CAPABILITY}
        return getattr(client, method)(path, headers=headers, **kwargs)

    for method, path, kwargs in (
        ("post", f"/v1/capsules/{capsule_id}/material-synced", {"content": b""}),
        ("get", f"/v1/capsules/{capsule_id}/blobs/{blob_id}", {}),
        ("post", f"/v1/capsules/{capsule_id}/first-open", {"json": {}}),
    ):
        denied = _authed_old(method, path, **kwargs)
        assert denied.status_code == 426, (path, denied.status_code, denied.text)
        assert denied.json()["code"] == "CLIENT_UPGRADE_REQUIRED"
    with factory() as session:
        assert session.get(Capsule, UUID(capsule_id)).first_opened_at is None

    synced = _authed_new("post", f"/v1/capsules/{capsule_id}/material-synced", content=b"")
    assert synced.status_code == 204, synced.text
    downloaded = _authed_new("get", f"/v1/capsules/{capsule_id}/blobs/{blob_id}")
    assert downloaded.status_code == 200, downloaded.text
    opened = _authed_new("post", f"/v1/capsules/{capsule_id}/first-open", json={})
    assert opened.status_code == 200, opened.text
    assert opened.json()["state"] == "OPENED"


def test_revoke_retires_terminal_and_lookup_dead(r2_client, tmp_path) -> None:
    client, factory = r2_client
    sender, _recipient, draft, body, _s, _g = _draft_flow(client, factory, tmp_path)
    finalized = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert finalized.status_code == 201, finalized.text
    code = finalized.json()["locator_code"]
    revoked = client.post(
        f"/v1/capsules/{draft['capsule_id']}/revoke",
        headers={"Authorization": f"Bearer {sender['access_token']}"},
    )
    assert revoked.status_code == 200, revoked.text
    with factory() as session:
        row = session.scalar(
            select(GiftLocator).where(GiftLocator.capsule_id == UUID(draft["capsule_id"]))
        )
        assert row is not None and row.state == "RETIRED_TERMINAL"
        assert session.scalar(
            select(UsedLocatorTombstone).where(UsedLocatorTombstone.code_hmac == row.code_hmac)
        ) is not None
    lookup = client.post("/v2/gift-lookups", json={"code": code.lower()})
    assert lookup.status_code == 404
    assert lookup.json()["code"] == "GIFT_UNAVAILABLE"


def test_completion_after_first_open_retires_open(r2_client, tmp_path) -> None:
    """Genuine first-open admission, then R15 background completion:
    ACTIVE -> RETIRED_OPEN, envelope intact, second scan works offline
    (incoming still lists the capsule, download serves)."""
    client, factory = r2_client
    sender, recipient, draft, body, _s, _g = _draft_flow(client, factory, tmp_path)
    finalized = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert finalized.status_code == 201, finalized.text
    capsule_id = draft["capsule_id"]
    recipient_headers = {"Authorization": f"Bearer {recipient['access_token']}", **CAPABILITY}
    opened = client.post(f"/v1/capsules/{capsule_id}/first-open", headers=recipient_headers, json={})
    assert opened.status_code == 200, opened.text
    with factory() as session:
        pubhash = bytes(session.get(Capsule, UUID(capsule_id)).signed_statement_sha256).hex()
    completed = client.post(
        f"/v2/capsules/{capsule_id}/locator-open-completion",
        headers=recipient_headers,
        json={"publication_sha256_hex": pubhash, "operation_id": str(uuid4())},
    )
    assert completed.status_code == 200, completed.text
    assert completed.json() == {
        "capsule_id": capsule_id,
        "completion": True,
        "locator_state": "RETIRED_OPEN",
    }
    with factory() as session:
        row = session.scalar(
            select(GiftLocator).where(GiftLocator.capsule_id == UUID(capsule_id))
        )
        assert row.state == "RETIRED_OPEN"
        assert session.get(CapsuleEnvelope, UUID(capsule_id)) is not None
    # Second scan works offline: sync feed + material intact.
    incoming = client.get("/v1/capsules/incoming", headers=recipient_headers)
    assert incoming.status_code == 200, incoming.text
    assert any(item["capsule_id"] == capsule_id for item in incoming.json()["items"])
    blob_id = draft["blobs"][0]["blob_id"]
    downloaded = client.get(
        f"/v1/capsules/{capsule_id}/blobs/{blob_id}", headers=recipient_headers
    )
    assert downloaded.status_code == 200, downloaded.text


def test_abort_draft_creates_no_locator(r2_client, tmp_path) -> None:
    """Abort only touches DRAFT capsules, where no locator can exist;
    verified, no code needed beyond this assertion."""
    client, factory = r2_client
    sender, _recipient, draft, _body, _s, _g = _draft_flow(client, factory, tmp_path)
    aborted = client.request(
        "DELETE",
        f"/v1/capsules/{draft['capsule_id']}",
        headers={"Authorization": f"Bearer {sender['access_token']}", **CAPABILITY},
    )
    assert aborted.status_code == 204, aborted.text
    with factory() as session:
        assert session.scalar(
            select(GiftLocator).where(GiftLocator.capsule_id == UUID(draft["capsule_id"]))
        ) is None


def test_v1_lookup_and_continue_shape_for_locator_gift(r2_client, tmp_path) -> None:
    """Common lookup resolves a v1 locator gift: real minimal hint,
    frozen v1 channel value, and the v1 continue branch (next: sync)."""
    client, factory = r2_client
    sender, recipient, draft, body, _s, _g = _draft_flow(client, factory, tmp_path)
    finalized = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert finalized.status_code == 201, finalized.text
    code = finalized.json()["locator_code"]
    hint = finalized.json()["locator_hint"]
    lookup = client.post("/v2/gift-lookups", json={"code": f" {code.lower()}-"})
    assert lookup.status_code == 200, lookup.text
    found = lookup.json()
    assert set(found) == {"gift_reference", "channel", "hint"}
    assert found["hint"] == hint
    # v1 gifts have no pending delivery channel: frozen "unknown"
    # (never guessed), documented for T186.
    assert found["channel"] == "unknown"
    continued = client.post(
        f"/v2/gift-lookups/{found['gift_reference']}/continue",
        headers={"Authorization": f"Bearer {recipient['access_token']}"},
        json={},
    )
    assert continued.status_code == 200, continued.text
    assert continued.json() == {"next": "sync", "capsule_id": draft["capsule_id"]}


def test_v1_incoming_lists_locator_gift_without_code(r2_client, tmp_path) -> None:
    """Listing alone exposes no code and promises no completion: the v1
    incoming item shape is unchanged for locator-bearing capsules, so no
    old-client list gate is needed."""
    client, factory = r2_client
    sender, recipient, draft, body, _s, _g = _draft_flow(client, factory, tmp_path)
    finalized = _post_finalize(
        client, sender["access_token"], draft["capsule_id"], body, extra_headers=CAPABILITY
    )
    assert finalized.status_code == 201, finalized.text
    code = finalized.json()["locator_code"]
    incoming = client.get(
        "/v1/capsules/incoming",
        headers={"Authorization": f"Bearer {recipient['access_token']}"},
    )
    assert incoming.status_code == 200, incoming.text
    items = [i for i in incoming.json()["items"] if i["capsule_id"] == draft["capsule_id"]]
    assert len(items) == 1
    raw = incoming.text
    assert code not in raw
    assert "locator" not in raw.lower()
