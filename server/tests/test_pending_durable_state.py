"""T205 durable state + bootstrap units: deterministic, no runtime.

No DB, no sockets, no server launch, no browser: invented fixtures
in tmp_path, real Tink crypto, schema/DB seams mocked. Root owns all
runtime/secrets/DB provisioning and the independent review.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import threading
import uuid
from pathlib import Path
from uuid import uuid4

import pytest
import tink
from tink import signature, tink_config

import remanence.pending.durable_bootstrap as durable
import remanence.pending.durable_state as state
from remanence.pending.durable_state import (
    BUNDLE_KEYS,
    BUNDLE_SCHEMA_VERSION,
    DurableRefusal,
    build_bundle,
    check_db_empty,
    generate_epoch_material,
    init_bundle,
    load_bundle,
)


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch):
    for key in list(os.environ):
        if key.upper().startswith("REMANENCE_"):
            monkeypatch.delenv(key, raising=False)


def _binding(**overrides) -> dict:
    base = {
        "db_name": "remanence_tmp_native_persistent_t1",
        "db_host": "127.0.0.1",
        "db_port": "55481",
        "blob_root": "/tmp/durable-blobs-t1",
        "mode": "dev",
        "google_audience": "test-web.apps.googleusercontent.com",
        "google_presenters": ["test-android.apps.googleusercontent.com"],
        "recovery_rp_id": "remanence.hryshyn.dev",
        "recovery_origins": [
            "https://remanence.hryshyn.dev",
            "android:apk-key-hash:" + "A" * 43,
        ],
    }
    base.update(overrides)
    return base


def _material():
    return generate_epoch_material()


def test_material_shape_and_tink_profile() -> None:
    """Inventory shape: every symmetric key exactly 32 CSPRNG bytes;
    the service identity is a real single-key Tink Ed25519 profile."""
    material = _material()
    for name in ("destination_key", "otp_key", "proof_mac_key",
                 "evidence_key", "locator_code_key", "vault_key"):
        assert isinstance(material[name], bytes) and len(material[name]) == 32
    uuid.UUID(str(material["service_key_id"]))
    assert material["vault_key_id"] == "durable-test-vault-1"
    public_raw = base64.b64decode(material["service_public_keyset"])
    state._validate_public_keyset(public_raw)
    from tink.proto import tink_pb2

    keyset = tink_pb2.Keyset.FromString(public_raw)
    assert len(keyset.key) == 1 and keyset.primary_key_id != 0
    assert keyset.primary_key_id == keyset.key[0].key_id


def test_import_rejects_foreign_or_malformed_keysets() -> None:
    foreign = _material()
    handle = state.import_service_signer(foreign["service_private_keyset"])
    assert handle is not None
    with pytest.raises(DurableRefusal):
        state.import_service_signer(b"not-a-keyset")
    with pytest.raises(DurableRefusal):
        state.import_service_signer(base64.b64decode(foreign["service_public_keyset"]))
    # Public cleartext API only: no private _create/_keyset mechanics.
    import inspect as _inspect

    from tink import cleartext_keyset_handle

    assert list(_inspect.signature(cleartext_keyset_handle.read).parameters) == [
        "keyset_reader",
    ]
    assert list(_inspect.signature(cleartext_keyset_handle.write).parameters) == [
        "keyset_writer",
        "keyset_handle",
    ]


def test_round_trip_stable_uuid_pin_and_keys(tmp_path: Path) -> None:
    """True independent reconstruction from the same saved state:
    stable UUID/pin/keys; sign-before/verify-after through the
    UNCHANGED server verifier; vault seal-before/open-after; HMAC
    continuity across the boundary."""
    from remanence.pending import domains
    from remanence.pending.domains import domain_prefix
    from remanence.pending.locator_store import code_hmac
    from remanence.pending.publication import verify_signature_69
    from remanence.pending.vault import DevSealedVault, build_escrow_aad
    from remanence.protocol.delivery_v2 import delivery_v2_pb2 as dv2
    from test_pending_m3_pg import _publish_package

    material = _material()
    binding = _binding()
    hmac_key = b"epoch-hmac-" + b"0" * 21
    bundle = build_bundle(material=material, binding=binding, hmac_key=hmac_key)
    state_dir = str(tmp_path / "state")
    init_bundle(state_dir, bundle)

    domain_input = domains.domain_prefix(domains.CLAIM_BINDING_DOMAIN) + b"canonical-R"
    init_signer = state.import_service_signer(material["service_private_keyset"]).primitive(
        signature.PublicKeySign
    )
    before_sig = init_signer.sign(domain_input)
    # Real escrow package (pure fixture builder, no DB): sealed before,
    # opened after, under the resumed vault key.
    capsule_id, target_id, sender_id = uuid4(), uuid4(), uuid4()
    statement_raw, payload_raw, _ = _publish_package(
        capsule_id=capsule_id, target_id=target_id, sender_id=sender_id,
        sender_bundle_id=uuid4(), service_key_id=uuid4(),
    )
    statement = dv2.PendingPublishStatementV2.FromString(statement_raw)
    aad = build_escrow_aad(
        capsule_id=capsule_id.bytes,
        delivery_target_id=target_id.bytes,
        sender_user_id=sender_id.bytes,
        publish_statement_sha256=hashlib.sha256(statement_raw).digest(),
        address_commitment=bytes(statement.address_commitment),
        key_commitment=bytes(statement.key_commitment),
        claim_policy_version=1,
        claim_expires_at_epoch_seconds=statement.claim_expires_at_epoch_seconds,
    )
    vault_before = DevSealedVault(mode="test", key_id="rt-1", key_32=material["vault_key"])
    sealed = vault_before.seal(dv2.PendingEscrowPlaintextV1.FromString(payload_raw), aad)
    assert sealed.ciphertext

    resumed = load_bundle(state_dir, expected={**binding, "hmac_key": hmac_key})
    assert resumed["service_key_id"] == material["service_key_id"]
    assert resumed["service_public_keyset"] == material["service_public_keyset"]
    for name in ("destination_key", "otp_key", "proof_mac_key",
                 "evidence_key", "locator_code_key", "vault_key", "hmac_key"):
        assert resumed[name] == (material[name] if name != "hmac_key" else hmac_key)
    # Sign-before (init handle) verifies after (resumed pin) and back.
    public_raw = base64.b64decode(resumed["service_public_keyset"])
    verify_signature_69(public_raw, domain_input, before_sig)
    after_sig = resumed["service_signer"].sign(domain_input)
    assert len(after_sig) == 69
    verify_signature_69(public_raw, domain_input, after_sig)
    # Vault seal-before opens-after under the resumed key.
    vault_after = DevSealedVault(mode="test", key_id="rt-1", key_32=resumed["vault_key"])
    opened = vault_after.open(sealed, aad)
    assert opened.SerializeToString(deterministic=True) == payload_raw
    # HMAC continuity: same tag function, resumed keys.
    from remanence.pending.registry import lookup_tag

    assert lookup_tag("email", "a@example.test", resumed["hmac_key"]) == \
        lookup_tag("email", "a@example.test", hmac_key)
    assert code_hmac("AB3DE", resumed["hmac_key"]) == code_hmac("AB3DE", hmac_key)


def test_init_refuses_existing_and_second_init_wins_once(tmp_path: Path) -> None:
    material = _material()
    bundle = build_bundle(material=material, binding=_binding(), hmac_key=b"k" * 32)
    state_dir = str(tmp_path / "state")
    init_bundle(state_dir, bundle)
    with pytest.raises(DurableRefusal):
        init_bundle(state_dir, bundle)
    with pytest.raises(DurableRefusal):
        build_bundle(material=material, binding=_binding(), hmac_key=b"short")


def test_concurrent_init_exactly_one_winner(tmp_path: Path) -> None:
    material = _material()
    bundle = build_bundle(material=material, binding=_binding(), hmac_key=b"k" * 32)
    state_dir = str(tmp_path / "race")
    outcomes: list[str] = []

    def _attempt() -> None:
        try:
            init_bundle(state_dir, bundle)
            outcomes.append("created")
        except DurableRefusal:
            outcomes.append("refused")
        except FileExistsError:
            outcomes.append("refused")

    threads = [threading.Thread(target=_attempt) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=30)
    assert outcomes.count("created") == 1
    assert outcomes.count("refused") == 7
    resumed = load_bundle(state_dir, expected={**_binding(), "hmac_key": b"k" * 32})
    assert resumed["service_key_id"] == material["service_key_id"]


def _tamper_file(path: str, byte_index: int = 10) -> None:
    with open(path, "r+b") as handle:
        handle.seek(byte_index)
        byte = handle.read(1)
        handle.seek(byte_index)
        handle.write(bytes([byte[0] ^ 0xFF]))


def test_resume_rejects_every_corruption_class(tmp_path: Path) -> None:
    material = _material()
    binding = _binding()
    hmac_key = b"k" * 32
    state_dir = str(tmp_path / "state")
    target = init_bundle(state_dir, build_bundle(material=material, binding=binding, hmac_key=hmac_key))
    before = Path(target).read_bytes()

    def _reload_ok():
        resumed = load_bundle(state_dir, expected={**binding, "hmac_key": hmac_key})
        assert resumed["service_key_id"] == material["service_key_id"]

    _reload_ok()
    # Tampered bytes.
    _tamper_file(target)
    with pytest.raises(DurableRefusal):
        load_bundle(state_dir, expected={**binding, "hmac_key": hmac_key})
    assert Path(target).read_bytes() != before
    # Restore a fresh bundle for the remaining classes.
    import shutil

    shutil.rmtree(state_dir)
    target = init_bundle(state_dir, build_bundle(material=material, binding=binding, hmac_key=hmac_key))
    # Wrong version / wrong key set / missing file.
    raw = json.loads(Path(target).read_text(encoding="utf-8"))
    for mutate in (
        lambda doc: doc.update(schema_version=2),
        lambda doc: doc.pop("vault_key_b64"),
        lambda doc: doc.update(extra_field=1),
    ):
        broken = dict(raw)
        mutate(broken)
        Path(target).write_text(json.dumps(broken), encoding="utf-8")
        with pytest.raises(DurableRefusal):
            load_bundle(state_dir, expected={**binding, "hmac_key": hmac_key})
    Path(target).write_bytes(
        json.dumps(raw).encode("utf-8")
    )
    os.chmod(target, 0o600)
    _reload_ok()
    # Binding mismatches, one at a time (db, blob, mode, google, rp, origins, hmac).
    mismatches = [
        {"db_name": "remanence_tmp_native_persistent_OTHER"},
        {"blob_root": "/tmp/other-blobs"},
        {"mode": "test"},
        {"google_audience": "other.apps.googleusercontent.com"},
        {"google_presenters": ["other.apps.googleusercontent.com"]},
        {"recovery_rp_id": "other.example"},
        {"recovery_origins": ["https://other.example"]},
        {"hmac_key": b"0" * 32},
    ]
    for mismatch in mismatches:
        with pytest.raises(DurableRefusal):
            load_bundle(state_dir, expected={**binding, **mismatch, "hmac_key": mismatch.get("hmac_key", hmac_key)})


def test_resume_rejects_permissions_and_symlinks(tmp_path: Path) -> None:
    material = _material()
    binding = _binding()
    state_dir = str(tmp_path / "state")
    target = init_bundle(state_dir, build_bundle(material=material, binding=binding, hmac_key=b"k" * 32))
    expected = {**binding, "hmac_key": b"k" * 32}
    load_bundle(state_dir, expected=expected)
    os.chmod(target, 0o640)
    with pytest.raises(DurableRefusal):
        load_bundle(state_dir, expected=expected)
    os.chmod(target, 0o600)
    load_bundle(state_dir, expected=expected)
    os.chmod(state_dir, 0o770)
    with pytest.raises(DurableRefusal):
        load_bundle(state_dir, expected=expected)
    os.chmod(state_dir, 0o700)
    try:
        os.symlink(state_dir, str(tmp_path / "state-link"))
    except OSError:
        pytest.skip("symlinks unsupported here")
    with pytest.raises(DurableRefusal):
        load_bundle(str(tmp_path / "state-link"), expected=expected)


def test_resume_rejects_missing_and_empty(tmp_path: Path) -> None:
    with pytest.raises(DurableRefusal):
        load_bundle(str(tmp_path / "absent"), expected={})
    empty_dir = str(tmp_path / "empty")
    os.mkdir(empty_dir, 0o700)
    with pytest.raises(DurableRefusal):
        load_bundle(empty_dir, expected={})


def _stub_engine(counts: list[int]):
    remaining = list(counts)

    class _Result:
        def __init__(self, value: int) -> None:
            self._value = value

        def scalar(self):
            return self._value

    class _Connection:
        def __enter__(self):
            return self

        def __exit__(self, *args) -> bool:
            return False

        def execute(self, stmt):
            return _Result(remaining.pop(0))

    class _Engine:
        def connect(self):
            return _Connection()

    return _Engine()


def test_check_db_empty_stubbed() -> None:
    from remanence.pending.durable_state import app_table_names

    names = app_table_names()
    # Metadata-derived coverage (not a hand-kept four): anonymous
    # Google AUTH challenges and the other rows live here too, while
    # migration bookkeeping stays exempt.
    assert "google_flows" in names
    assert "alembic_version" not in names
    assert len(names) >= 28
    check_db_empty(_stub_engine([0] * len(names)))
    # Nonzero ONLY outside the old four-table set still blocks init:
    # an anonymous Google challenge with no users/capsules/deliveries.
    only_google = [0] * len(names)
    only_google[names.index("google_flows")] = 1
    with pytest.raises(DurableRefusal) as caught:
        check_db_empty(_stub_engine(only_google))
    assert "not empty" in str(caught.value)

    class _Broken:
        def connect(self):
            raise ConnectionError("fake outage")

    with pytest.raises(DurableRefusal):
        check_db_empty(_Broken())


def test_session_recovery_inventory_needs_no_server_keys() -> None:
    """Inventory evidence: opaque session tokens are CSPRNG random with
    DB-side SHA-256 lookup (no server secret, no JWT); recovery and
    Google flow challenges are per-request DB rows. Nothing here
    belongs in the bundle — asserted, not assumed."""
    from remanence.auth.tokens import generate_access_token, hash_opaque_token

    first, second = generate_access_token(), generate_access_token()
    assert first != second and first.startswith("pm_at_")
    assert hash_opaque_token(first) != hash_opaque_token(second)
    assert len(hash_opaque_token(first)) == 32


def test_durable_guards_and_fixed_bind(tmp_path: Path) -> None:
    assert durable.fixed_bind() == ("127.0.0.1", 18047)
    assert (durable.DURABLE_HOST, durable.DURABLE_PORT) == ("127.0.0.1", 18047)
    flags = {opt for action in durable.build_parser()._actions for opt in action.option_strings}
    assert "--host" not in flags and "--port" not in flags
    good = "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_native_persistent_e1"
    assert durable.durable_database_name_of(good) == "remanence_tmp_native_persistent_e1"
    for bad in (
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/t181_clone",
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_epoch9",
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence",
        "sqlite:///tmp/x.db",
        "",
    ):
        with pytest.raises(state.DurableRefusal):
            durable.durable_database_name_of(bad)


def _canonical_origin() -> str:
    import base64 as _base64

    encoded = _base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
    assert len(encoded) == 43
    return "android:apk-key-hash:" + encoded


def test_durable_composition_init_then_resume_stable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Init then resume through the real launcher composition (schema
    + emptiness seams mocked, no DB): identical pin bytes, UUID, and
    keys — the continuity claim, proved twice from one bundle."""
    import argparse

    import remanence.pending.durable_bootstrap as _boot

    monkeypatch.setattr(_boot, "check_schema_head", lambda url: None)
    monkeypatch.setattr(_boot, "check_db_empty", lambda engine: None)

    def _args(state_dir: str, init: bool) -> argparse.Namespace:
        return argparse.Namespace(
            database_url="postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_native_persistent_e9",
            hmac_secret="test-epoch-hmac-secret-32-bytes!!",
            pin_out=str(tmp_path / "pin.json"),
            otp_sink=str(tmp_path / "otp.jsonl"),
            blob_root=str(tmp_path / "blobs"),
            mode="dev",
            google_audience="test-web.apps.googleusercontent.com",
            google_presenters="test-android.apps.googleusercontent.com",
            android_origin=_canonical_origin(),
            state_dir=state_dir,
            init=init,
        )

    state_dir = str(tmp_path / "epoch-state")
    first = _boot.build_composition(_args(state_dir, init=True))
    _, _, _, _, _, pin_init, material_init = first
    second = _boot.build_composition(_args(state_dir, init=False))
    _, _, _, _, _, pin_resumed, material_resumed = second
    import json as _json
    from datetime import datetime as _datetime

    def _identity(pin: dict) -> dict:
        return {key: value for key, value in pin.items() if key != "generated_at"}

    assert _json.dumps(_identity(pin_init), sort_keys=True) == _json.dumps(
        _identity(pin_resumed), sort_keys=True
    )
    # Fresh timestamps each start (honest per-start metadata, not identity).
    assert _datetime.fromisoformat(pin_init["generated_at"])
    assert _datetime.fromisoformat(pin_resumed["generated_at"])
    assert material_resumed["service_key_id"] == material_init["service_key_id"]
    for name in ("hmac_key", "destination_key", "otp_key", "proof_mac_key",
                 "evidence_key", "locator_code_key", "vault_key"):
        expected = material_init[name] if name != "hmac_key" else b"test-epoch-hmac-secret-32-bytes!!"
        assert material_resumed[name] == expected
    # And the resumed custody verifies through the real verifier.
    from remanence.pending import domains
    from remanence.pending.publication import verify_signature_69

    challenge = domains.domain_prefix(domains.CLAIM_BINDING_DOMAIN) + b"epoch-R"
    proof = material_resumed["service_signer"].sign(challenge)
    verify_signature_69(
        base64.b64decode(material_resumed["service_public_keyset"]),
        challenge, proof,
    )


def test_main_refusal_carries_no_secrets(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys
) -> None:
    import remanence.pending.durable_bootstrap as _boot

    monkeypatch.setattr(_boot, "check_schema_head", lambda url: None)
    monkeypatch.setattr(_boot, "check_db_empty", lambda engine: None)
    sentinel = f"SENTINEL-{uuid4().hex}"
    state_dir = tmp_path / "epoch-state"
    state_dir.mkdir(mode=0o700)  # pre-existing dir: --init must refuse (never serve)
    args = {
        "--database-url": "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_native_persistent_e9",
        "--hmac-secret": sentinel,
        "--pin-out": str(tmp_path / "pin.json"),
        "--otp-sink": str(tmp_path / "otp.jsonl"),
        "--blob-root": str(tmp_path / "blobs"),
        "--mode": "dev",
        "--google-audience": "test-web.apps.googleusercontent.com",
        "--google-presenters": "test-android.apps.googleusercontent.com",
        "--android-origin": _canonical_origin(),
        "--state-dir": str(state_dir),
        "--init": None,
    }
    argv = []
    for key, value in args.items():
        argv += [key] if value is None else [key, value]
    code = _boot.main(argv)
    assert code == 2
    captured = capsys.readouterr()
    assert "state dir exists" in captured.err
    assert sentinel not in captured.err and sentinel not in captured.out
    assert not Path(tmp_path / "pin.json").exists()


def test_cold_process_resume_without_prior_registration(tmp_path: Path) -> None:
    """TRUE new-process regression: init in this process, then a FRESH
    Python child importing ONLY the resume path loads the bundle and
    produces a verifiable signature — no shared Tink registry with
    init (the suite autouse fixture cannot leak across exec)."""
    import json as _json
    import subprocess
    import sys as _sys

    material = _material()
    binding = _binding()
    hmac_key = b"k" * 32
    state_dir = str(tmp_path / "state")
    init_bundle(state_dir, build_bundle(material=material, binding=binding, hmac_key=hmac_key))
    params = {
        "state_dir": state_dir,
        "binding": binding,
        "hmac_b64": base64.b64encode(hmac_key).decode("ascii"),
    }
    params_path = tmp_path / "cold-params.json"
    params_path.write_text(_json.dumps(params), encoding="utf-8")
    child_path = tmp_path / "cold_resume_child.py"
    child_path.write_text(
        "import base64, json, sys\n"
        "params = json.load(open(sys.argv[1], encoding='utf-8'))\n"
        "from remanence.pending.durable_state import load_bundle\n"
        "from remanence.pending import domains\n"
        "material = load_bundle(params['state_dir'], expected={\n"
        "    **params['binding'],\n"
        "    'hmac_key': base64.b64decode(params['hmac_b64']),\n"
        "})\n"
        "challenge = domains.domain_prefix(domains.CLAIM_BINDING_DOMAIN) + b'cold-R'\n"
        "sig = material['service_signer'].sign(challenge)\n"
        "assert len(sig) == 69, len(sig)\n"
        "print(f'COLD_OK sig={len(sig)} uuid={material[\"service_key_id\"]}')\n",
        encoding="utf-8",
    )
    src = str(Path(__file__).resolve().parents[1] / "src")
    env = {**os.environ, "PYTHONPATH": src}
    completed = subprocess.run(
        [_sys.executable, str(child_path), str(params_path)],
        capture_output=True, text=True, timeout=120, env=env,
    )
    assert completed.returncode == 0, completed.stderr[-2000:]
    assert "COLD_OK sig=69" in completed.stdout
    assert str(material["service_key_id"]) in completed.stdout


def test_database_url_env_fallback_and_precedence(monkeypatch: pytest.MonkeyPatch) -> None:
    import remanence.pending.durable_bootstrap as _boot

    env_url = "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_native_persistent_env"
    explicit_url = "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_native_persistent_exp"
    monkeypatch.delenv("REMANENCE_DATABASE_URL", raising=False)
    with pytest.raises(DurableRefusal):
        _boot.resolve_database_url(None)
    monkeypatch.setenv("REMANENCE_DATABASE_URL", env_url)
    assert _boot.resolve_database_url(None) == env_url
    assert _boot.resolve_database_url(explicit_url) == explicit_url
    monkeypatch.setenv(
        "REMANENCE_DATABASE_URL",
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/t181_clone",
    )
    with pytest.raises(DurableRefusal):
        _boot.resolve_database_url(None)


def test_main_happy_path_env_only_db_url(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys
) -> None:
    """Bounded main happy path with uvicorn.run stubbed (no sockets):
    env-only DB URL (no --database-url argv), successful composition,
    fixed bind, redacted startup line, pin written — and neither the
    DB password nor the HMAC secret on either stream."""
    import uvicorn

    import remanence.pending.durable_bootstrap as _boot

    monkeypatch.setattr(_boot, "check_schema_head", lambda url: None)
    monkeypatch.setattr(_boot, "check_db_empty", lambda engine: None)
    served = {}

    def _fake_run(app, *, host, port, access_log, log_level):
        served.update(app=app, host=host, port=port,
                      access_log=access_log, log_level=log_level)

    monkeypatch.setattr(uvicorn, "run", _fake_run)
    db_password = f"pw-{uuid4().hex}"
    hmac_secret = f"hmac-{uuid4().hex}-32-chars-minimum-ok"
    monkeypatch.setenv(
        "REMANENCE_DATABASE_URL",
        f"postgresql+psycopg://t181:{db_password}@127.0.0.1:55481/remanence_tmp_native_persistent_e9",
    )
    code = _boot.main([
        "--hmac-secret", hmac_secret,
        "--pin-out", str(tmp_path / "pin.json"),
        "--otp-sink", str(tmp_path / "otp.jsonl"),
        "--blob-root", str(tmp_path / "blobs"),
        "--mode", "dev",
        "--google-audience", "test-web.apps.googleusercontent.com",
        "--google-presenters", "test-android.apps.googleusercontent.com",
        "--android-origin", _canonical_origin(),
        "--state-dir", str(tmp_path / "epoch-state"),
        "--init",
    ])
    assert code == 0
    assert served["host"] == "127.0.0.1" and served["port"] == 18047
    assert served["access_log"] is False
    assert served["app"] is not None
    captured = capsys.readouterr()
    assert "127.0.0.1:18047" in captured.out
    assert "remanence_tmp_native_persistent_e9" in captured.out
    assert db_password not in captured.out and db_password not in captured.err
    assert hmac_secret not in captured.out and hmac_secret not in captured.err
    import json as _json

    pin = _json.loads(Path(tmp_path / "pin.json").read_text(encoding="utf-8"))
    assert uuid.UUID(pin["service_key_id"])
    assert (tmp_path / "epoch-state" / "bundle.json").is_file()


def test_table_coverage_fresh_process(tmp_path: Path) -> None:
    """Fresh-process coverage: a clean interpreter importing ONLY the
    coverage function must see all 30 application tables, including
    the two the hand-kept list once missed
    (`anonymous_telemetry_reports`, `capsule_idempotency_records`).
    This does NOT rely on pytest-global Base.metadata seeding, which
    masked the omission in-process."""
    import json as _json
    import subprocess
    import sys as _sys

    child_path = tmp_path / "coverage_child_probe.py"
    child_path.write_text(
        "import json\n"
        "from remanence.pending.durable_state import app_table_names\n"
        "print(json.dumps(sorted(app_table_names())))",
        encoding="utf-8",
    )
    src = str(Path(__file__).resolve().parents[1] / "src")
    env = {**os.environ, "PYTHONPATH": src}
    completed = subprocess.run(
        [_sys.executable, str(child_path)],
        capture_output=True, text=True, timeout=120, env=env,
    )
    assert completed.returncode == 0, completed.stderr[-2000:]
    names = _json.loads(completed.stdout.strip().splitlines()[-1])
    assert "anonymous_telemetry_reports" in names
    assert "capsule_idempotency_records" in names
    assert "alembic_version" not in names
    # Exact head count: any migration adding a table must update this
    # guard consciously (fail loudly, not silently uncovered).
    assert len(names) == 30
