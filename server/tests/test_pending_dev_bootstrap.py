"""T185 dev-bootstrap guards + composition (synthetic only).

Covers the root-run launcher (`remanence.pending.dev_bootstrap`):
loopback/DB/secret guards, public-only pin export with a matching
ephemeral signer, the root-readable OTP file sink (unit + real proof
round trip), and app composition on a synthetic tmp PG database.
No server is started here (root sole server start), no persistent DB,
no real sends, no private material in any artifact.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import stat
import uuid
from pathlib import Path
from uuid import uuid4

import pytest
import tink
from sqlalchemy import select
from tink import signature, tink_config

pytest_plugins = ("test_session_repository_create",)

from remanence.pending import domains
from remanence.pending.dev_bootstrap import (
    BootstrapRefusal,
    PIN_JSON_KEYS,
    build_composition,
    build_pin_json,
    check_schema_head,
    database_name_of,
    generate_service_signer,
    main,
    refuse_unless_loopback,
    resolve_hmac_secret,
    resolve_vault_key,
    touch_otp_sink,
    write_pin_json,
)
from remanence.pending.providers import FileSinkEmailSender, ProviderError


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def test_loopback_guards() -> None:
    assert refuse_unless_loopback("127.0.0.1") == "127.0.0.1"
    assert refuse_unless_loopback("::1") == "::1"
    assert refuse_unless_loopback("localhost") == "localhost"
    for bad in ("0.0.0.0", "", "192.168.1.2", "example.com", None):
        with pytest.raises(BootstrapRefusal):
            refuse_unless_loopback(bad)


def test_database_name_guards() -> None:
    base = "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/"
    assert database_name_of(base + "remanence_tmp_abc123") == "remanence_tmp_abc123"
    for bad in (
        "",
        "sqlite:///tmp/x.db",
        base + "t181_clone",
        base + "remanence",
        base + "postgres",
        base + "other_db",
    ):
        with pytest.raises(BootstrapRefusal):
            database_name_of(bad)


def test_hmac_secret_guards(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("PENDING_DEV_HMAC_SECRET", raising=False)
    with pytest.raises(BootstrapRefusal):
        resolve_hmac_secret(None)
    with pytest.raises(BootstrapRefusal):
        resolve_hmac_secret("short")
    assert resolve_hmac_secret("0123456789abcdef0123456789abcdef") == (
        b"0123456789abcdef0123456789abcdef"
    )
    monkeypatch.setenv("PENDING_DEV_HMAC_SECRET", "env-fallback-secret-00-env-fallback")
    assert resolve_hmac_secret(None) == b"env-fallback-secret-00-env-fallback"


def test_vault_key_resolution() -> None:
    with pytest.raises(BootstrapRefusal):
        resolve_vault_key("not-hex")
    with pytest.raises(BootstrapRefusal):
        resolve_vault_key("00" * 16)
    key, ephemeral = resolve_vault_key("0b" * 32)
    assert (key, ephemeral) == (bytes([0x0B] * 32), False)
    auto, ephemeral = resolve_vault_key(None)
    assert ephemeral is True and len(auto) == 32


def test_sink_mode_and_parent_gates(tmp_path: Path) -> None:
    with pytest.raises(ProviderError):
        FileSinkEmailSender(path=str(tmp_path / "otp.jsonl"), mode="prod")
    with pytest.raises(ProviderError):
        FileSinkEmailSender(path=str(tmp_path / "otp.jsonl"), mode="beta")
    with pytest.raises(ProviderError):
        FileSinkEmailSender(path=str(tmp_path / "no-such-dir" / "otp.jsonl"))


def test_sink_writes_root_readable_lines_without_destination(tmp_path: Path) -> None:
    sink_path = str(tmp_path / "otp.jsonl")
    sink = FileSinkEmailSender(path=sink_path)
    reference = sink.send(
        attempt_id="attempt-1", stored_destination="secret@example.test",
        code="12345678", locale="en",
    )
    assert reference == "dev-email-attempt-1"
    assert stat.S_IMODE(os.stat(sink_path).st_mode) == 0o600
    lines = Path(sink_path).read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1
    record = json.loads(lines[0])
    assert record["reference"] == reference and record["code"] == "12345678"
    blob = Path(sink_path).read_text(encoding="utf-8")
    assert "secret@example.test" not in blob
    assert len(sink.dispatched) == 1
    assert sink.dispatched[0].destination_length == len("secret@example.test")


def test_pin_json_is_public_only() -> None:
    signer = generate_service_signer()
    pin = build_pin_json(
        service_key_id=signer.key_id,
        public_keyset_b64=signer.public_keyset_b64,
        vault_key_id="dev-bootstrap-1",
    )
    assert set(pin) == PIN_JSON_KEYS
    uuid.UUID(pin["service_key_id"])
    blob = json.dumps(pin).lower()
    assert "private" not in blob and "secret" not in blob
    # The exported pin parses as a secretless public keyset.
    handle = tink.KeysetHandle.read_no_secret(
        tink.BinaryKeysetReader(base64.b64decode(pin["service_ed25519_public_keyset_b64"]))
    )
    buf = io.BytesIO()
    handle.write_no_secret(tink.BinaryKeysetWriter(buf))
    assert buf.getvalue() == base64.b64decode(pin["service_ed25519_public_keyset_b64"])


def test_signer_matches_exported_pin() -> None:
    """Deployment-configured signer proves against the exported PUBLIC
    verifier (what the discarded 099 private half can no longer do);
    a foreign key does not verify (no R-carried trust)."""
    from remanence.pending.publication import verify_signature_69

    signer = generate_service_signer()
    pin = build_pin_json(
        service_key_id=signer.key_id,
        public_keyset_b64=signer.public_keyset_b64,
        vault_key_id="dev-bootstrap-1",
    )
    domain_input = domains.domain_prefix(domains.CLAIM_BINDING_DOMAIN) + b"canonical-R-bytes"
    sig = signer.sign(domain_input)
    assert len(sig) == 69
    verify_signature_69(base64.b64decode(pin["service_ed25519_public_keyset_b64"]), domain_input, sig)
    foreign = generate_service_signer()
    with pytest.raises(Exception):
        verify_signature_69(
            base64.b64decode(pin["service_ed25519_public_keyset_b64"]),
            domain_input, foreign.sign(domain_input),
        )


def test_write_pin_json_and_touch_sink_guards(tmp_path: Path) -> None:
    signer = generate_service_signer()
    pin = build_pin_json(
        service_key_id=signer.key_id,
        public_keyset_b64=signer.public_keyset_b64,
        vault_key_id="dev-bootstrap-1",
    )
    with pytest.raises(BootstrapRefusal):
        write_pin_json(str(tmp_path / "no-such-dir" / "pin.json"), pin)
    with pytest.raises(BootstrapRefusal):
        touch_otp_sink(str(tmp_path / "no-such-dir" / "otp.jsonl"))
    pin_path = str(tmp_path / "pin.json")
    write_pin_json(pin_path, pin)
    assert json.loads(Path(pin_path).read_text(encoding="utf-8")) == pin
    sink_path = str(tmp_path / "otp.jsonl")
    touch_otp_sink(sink_path)
    assert stat.S_IMODE(os.stat(sink_path).st_mode) == 0o600
    marker = json.loads(Path(sink_path).read_text(encoding="utf-8").splitlines()[0])
    assert marker["event"] == "epoch-start"


def test_main_refuses_non_loopback_without_touching_db(tmp_path: Path, capsys) -> None:
    code = main([
        "--host", "0.0.0.0",
        "--database-url", "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_x",
        "--hmac-secret", "0123456789abcdef0123456789abcdef",
        "--pin-out", str(tmp_path / "pin.json"),
        "--otp-sink", str(tmp_path / "otp.jsonl"),
    ])
    assert code == 2
    assert "loopback" in capsys.readouterr().err
    assert not (tmp_path / "pin.json").exists()
    assert not (tmp_path / "otp.jsonl").exists()


def test_otp_sink_proof_round_trip(session_factory, tmp_path: Path) -> None:
    """Real proof service against the file sink: the dispatched OTP lands
    in the root-readable file and verifies (fixed code supplier)."""
    from test_pending_m3_pg import (
        ADDRESS,
        DEST_KEY,
        DEV_VAULT_KEY,
        HMAC_KEY,
        OTP_KEY,
        PROOF_MAC_KEY,
        NOW,
        _finalize,
        _make_user,
        _setup_pending,
    )
    from remanence.pending.proof import check_verification, start_verification
    from remanence.pending.vault import DevSealedVault

    sink_path = str(tmp_path / "otp.jsonl")
    sink = FileSinkEmailSender(path=sink_path)
    vault = DevSealedVault(mode="test", key_id="boot-otp-1", key_32=DEV_VAULT_KEY)
    with session_factory() as session:
        setup = _setup_pending(session)
        _finalize(session, setup, uuid.uuid4(), vault)
        recipient = _make_user(session, f"otp{uuid.uuid4().hex[:8]}")
        session_id = uuid.uuid4()
        session.commit()
        delivery_id, account_id = setup["delivery"].id, recipient.id
        attempt = start_verification(
            session,
            delivery_id=delivery_id,
            account_user_id=account_id,
            session_id=session_id,
            confirm_destination=ADDRESS,
            destination_key_32=DEST_KEY,
            destination_tag_key=HMAC_KEY,
            registry_hmac_key=HMAC_KEY,
            otp_key=OTP_KEY,
            proof_mac_key=PROOF_MAC_KEY,
            email_sender=sink,
            sms_provider=None,
            locale="en",
            now=NOW,
            attempt_id=uuid.uuid4(),
            code_supplier=lambda: "87654321",
        )
        session.commit()
        attempt_id = attempt.id
    lines = [
        json.loads(line) for line in Path(sink_path).read_text(encoding="utf-8").splitlines()
    ]
    codes = [entry["code"] for entry in lines if entry.get("reference") == f"dev-email-{attempt_id}"]
    assert codes == ["87654321"]
    with session_factory() as session:
        verified = check_verification(
            session,
            attempt_id=attempt_id,
            delivery_id=delivery_id,
            account_user_id=account_id,
            session_id=session_id,
            code=codes[0],
            otp_key=OTP_KEY,
            sms_provider=None,
            proof_mac_key=PROOF_MAC_KEY,
            now=NOW,
        )
        session.commit()
        assert verified.state == "VERIFIED"


def _alembic_upgrade_head() -> None:
    from alembic import command
    from alembic.config import Config

    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    command.upgrade(config, "head")


@pytest.fixture()
def migrated_db(monkeypatch: pytest.MonkeyPatch):
    """Unique synthetic PG database migrated to head (created here,
    dropped at teardown; standard isolated pattern)."""
    import psycopg
    from psycopg import sql
    from sqlalchemy.engine import make_url

    source = os.environ.get("REMANENCE_TEST_DATABASE_URL")
    assert source, "REMANENCE_TEST_DATABASE_URL is not set"
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
        db_url = url.set(database=database).render_as_string(hide_password=False)
        monkeypatch.setenv("REMANENCE_DATABASE_URL", db_url)
        monkeypatch.setenv("REMANENCE_BLOB_ROOT", "var/test-blobs")
        _alembic_upgrade_head()
        yield db_url
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


def test_schema_head_check_refuses_unmigrated(monkeypatch: pytest.MonkeyPatch) -> None:
    import psycopg
    from psycopg import sql
    from sqlalchemy.engine import make_url

    source = os.environ.get("REMANENCE_TEST_DATABASE_URL")
    assert source, "REMANENCE_TEST_DATABASE_URL is not set"
    url = make_url(source)
    database = f"remanence_tmp_{uuid4().hex}"
    admin = psycopg.connect(
        host=url.host, port=url.port, user=url.username,
        password=url.password, dbname="postgres", autocommit=True,
    )
    try:
        admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
        db_url = url.set(database=database).render_as_string(hide_password=False)
        with pytest.raises(BootstrapRefusal) as caught:
            check_schema_head(db_url)
        assert "alembic upgrade head" in str(caught.value)
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


def test_launcher_app_composition(migrated_db: str, tmp_path: Path) -> None:
    """The launcher's own composition serves pending routes in-process:
    registry backfilled, lookup fenced, ctx/pin key ids agree. No ports."""
    from fastapi.testclient import TestClient

    args = argparse.Namespace(
        host="127.0.0.1",
        port=18042,
        database_url=migrated_db,
        hmac_secret="0123456789abcdef0123456789abcdef",
        pin_out=str(tmp_path / "pin.json"),
        otp_sink=str(tmp_path / "otp.jsonl"),
        blob_root=str(tmp_path / "blobs"),
        dev_vault_key_hex="0b" * 32,
        mode="dev",
    )
    host, db_name, settings, ctx, pin, signer, vault_ephemeral = build_composition(args)
    assert host == "127.0.0.1" and db_name.startswith("remanence_tmp_")
    assert vault_ephemeral is False
    assert str(ctx["service_key_id"]) == pin["service_key_id"] == str(signer.key_id)
    write_pin_json(args.pin_out, pin)
    touch_otp_sink(args.otp_sink)
    from remanence.db.session import build_engine, build_session_factory
    from remanence.main import create_app

    engine = build_engine(settings)
    factory = build_session_factory(engine)
    try:
        app = create_app(settings=settings, session_factory=factory, pending_ctx=ctx)
        with TestClient(app) as client:
            assert app.state.pending_registry_ready is True
            paths = set(app.openapi()["paths"])
            assert any(p.startswith("/v2/gift-lookups") for p in paths)
            assert client.get("/healthz").status_code in (200, 404, 422)
            lookup = client.post("/v2/gift-lookups", json={"code": "ZZ999"})
            assert lookup.status_code == 404
            assert lookup.json()["code"] == "GIFT_UNAVAILABLE"
    finally:
        engine.dispose()
