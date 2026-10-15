"""T202 native launcher units: deterministic, no runtime.

No DB, no sockets, no server launch, no browser: guard matrices,
real Settings-constructor validation, composition equality, pin
fields, zero-I/O route mounting, and secret-sentinel absence.
Schema/DB touch is mocked at the seam; the real Google verifier is
never installed or faked.
"""

from __future__ import annotations

import argparse
import base64
import os
from pathlib import Path
from uuid import uuid4

import pytest
from pydantic import ValidationError

import remanence.pending.native_bootstrap as native
from remanence.pending.native_bootstrap import (
    NATIVE_HOST,
    NATIVE_PORT,
    NATIVE_RP_ID,
    NativeRefusal,
    build_composition,
    build_parser,
    build_settings,
    fixed_bind,
    resolve_android_origin,
    resolve_google_audience,
    resolve_google_presenters,
)

_EPOCH_DB = "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_epoch9"
_AUDIENCE = "test-web-client-123.apps.googleusercontent.com"
_PRESENTERS = "test-android-client-456.apps.googleusercontent.com"


def _canonical_origin() -> str:
    raw = bytes(range(32))
    encoded = base64.urlsafe_b64encode(raw).decode().rstrip("=")
    assert len(encoded) == 43
    return "android:apk-key-hash:" + encoded


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch):
    for key in list(os.environ):
        if key.upper().startswith("REMANENCE_"):
            monkeypatch.delenv(key, raising=False)


def test_fixed_bind_exact_and_no_override_surface() -> None:
    assert fixed_bind() == ("127.0.0.1", 18046)
    assert (NATIVE_HOST, NATIVE_PORT) == ("127.0.0.1", 18046)
    assert NATIVE_RP_ID == "remanence.hryshyn.dev"
    flags = {opt for action in build_parser()._actions for opt in action.option_strings}
    assert "--host" not in flags and "--port" not in flags


def test_mode_refusal_matrix(tmp_path: Path) -> None:
    for bad in ("prod", "beta", "staging", "", None):
        with pytest.raises(NativeRefusal):
            build_settings(
                database_url=_EPOCH_DB, blob_root=tmp_path, mode=bad,
                hmac_secret="x" * 32, google_audience=_AUDIENCE,
                google_presenters=[_PRESENTERS], android_origin=_canonical_origin(),
            )
    for ok in ("dev", "test"):
        settings = build_settings(
            database_url=_EPOCH_DB, blob_root=tmp_path, mode=ok,
            hmac_secret="x" * 32, google_audience=_AUDIENCE,
            google_presenters=[_PRESENTERS], android_origin=_canonical_origin(),
        )
        assert settings.mode.value == ok


def test_google_audience_matrix() -> None:
    assert resolve_google_audience(_AUDIENCE) == _AUDIENCE
    for bad in (
        "", None, 42,
        "not-an-id",
        "test-web-client-123.apps.google.com",
        "test-web-client-123.apps.googleusercontent.com.evil.test",
        " test-web-client-123.apps.googleusercontent.com",
        "test web.apps.googleusercontent.com",
    ):
        with pytest.raises(NativeRefusal):
            resolve_google_audience(bad)


def test_google_presenters_matrix() -> None:
    assert resolve_google_presenters(_PRESENTERS) == [_PRESENTERS]
    assert resolve_google_presenters(f" {_PRESENTERS} , other-id_9.apps.googleusercontent.com ") == [
        _PRESENTERS, "other-id_9.apps.googleusercontent.com",
    ]
    for bad in ("", None, 42, "   ", "good.apps.googleusercontent.com, bad!!"):
        with pytest.raises(NativeRefusal):
            resolve_google_presenters(bad)


def test_android_origin_matrix() -> None:
    good = _canonical_origin()
    assert resolve_android_origin(good) == good
    assert resolve_android_origin(good).startswith("android:apk-key-hash:")
    good_hash = good.removeprefix("android:apk-key-hash:")
    flipped = good_hash[:-1] + ("B" if good_hash[-1] != "B" else "C")
    recanon = base64.urlsafe_b64encode(
        base64.urlsafe_b64decode(flipped + "=")).decode().rstrip("=")
    assert recanon != flipped  # genuinely non-canonical, not accidentally valid
    bad_hash = "android:apk-key-hash:" + flipped
    for bad in (
        "",
        None,
        "android:apk-key-hash:",
        "android:apk-key-hash:" + "A" * 42,
        "android:apk-key-hash:" + "A" * 44,
        "android:apk-key-hash:" + "+" * 43,
        "https://remanence.hryshyn.dev",
        bad_hash,
    ):
        with pytest.raises(NativeRefusal):
            resolve_android_origin(bad)


def test_settings_composition_exact(tmp_path: Path) -> None:
    origin = _canonical_origin()
    settings = build_settings(
        database_url=_EPOCH_DB, blob_root=tmp_path, mode="dev",
        hmac_secret="y" * 32, google_audience=_AUDIENCE,
        google_presenters=[_PRESENTERS], android_origin=origin,
    )
    assert settings.google_sign_in_enabled is True
    assert settings.google_client_ids == [_AUDIENCE]
    assert settings.google_presenter_ids == [_PRESENTERS]
    assert settings.recovery_enabled is True
    assert settings.recovery_rp_id == NATIVE_RP_ID
    assert settings.recovery_origins == ["https://remanence.hryshyn.dev", origin]
    assert settings.pending_protocol_enabled is True
    assert settings.pending_sends_enabled is True
    assert settings.pending_claims_enabled is True
    assert settings.pending_locator_issuance_enabled is True
    assert settings.pending_hmac_secret.get_secret_value() == "y" * 32
    assert settings.pending_kms_key_id is None


def test_real_settings_validators_reject() -> None:
    """The real Settings constructor (never model_copy/update) rejects
    bad Google/recovery config on its own."""
    from remanence.settings import AppMode, Settings

    with pytest.raises(ValidationError):
        Settings(
            mode=AppMode.DEV, google_sign_in_enabled=True,
            recovery_enabled=True, google_client_ids=["not-an-id"],
            google_presenter_ids=[],
        )


def _composition_args(tmp_path: Path, **overrides) -> argparse.Namespace:
    base = dict(
        host="127.0.0.1",
        port=18046,
        database_url=_EPOCH_DB,
        hmac_secret="z" * 32,
        pin_out=str(tmp_path / "pin.json"),
        otp_sink=str(tmp_path / "otp.jsonl"),
        blob_root=str(tmp_path / "blobs"),
        mode="dev",
        google_audience=_AUDIENCE,
        google_presenters=_PRESENTERS,
        android_origin=_canonical_origin(),
    )
    base.update(overrides)
    return argparse.Namespace(**base)


def test_composition_keys_pin_and_schema_seam(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seen = {}
    monkeypatch.setattr(
        native, "check_schema_head",
        lambda url: seen.setdefault("schema_url", url),
    )
    args = _composition_args(tmp_path)
    host, port, db_name, settings, ctx, pin, signer = build_composition(args)
    assert (host, port) == ("127.0.0.1", 18046)
    assert db_name == "remanence_tmp_epoch9"
    assert seen == {"schema_url": _EPOCH_DB}
    for key in ("hmac_key", "destination_key", "otp_key", "proof_mac_key",
                "evidence_key", "locator_code_key"):
        assert isinstance(ctx[key], bytes) and len(ctx[key]) == 32
    assert len({ctx[key] for key in ("destination_key", "otp_key", "proof_mac_key",
                                     "evidence_key", "locator_code_key")}) == 5
    assert str(ctx["service_key_id"]) == pin["service_key_id"] == str(signer.key_id)
    assert set(pin) == {
        "schema_version", "note", "service_key_id",
        "service_ed25519_public_keyset_b64", "algorithm",
        "claim_binding_domain", "generated_at", "vault_key_id",
    }


def test_zero_io_route_mounting(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """create_app mounts v1 + pending + recovery + Google routes with
    zero I/O (no factory, no lifespan run, no connections)."""
    from remanence.main import create_app

    monkeypatch.setattr(
        native, "check_schema_head", lambda url: None,
    )
    args = _composition_args(tmp_path)
    _, _, _, settings, _, _, _ = build_composition(args)
    # Minimal ctx: create_app only reads hmac_key for the mount gate
    # (full ctx shape is pinned by the composition test above).
    app = create_app(settings=settings, pending_ctx={"hmac_key": b"z" * 32})
    paths = set(app.openapi()["paths"])
    assert any(path.startswith("/v2/send-routes") for path in paths)
    assert "/v1/recovery/status" in paths
    assert "/recovery-test/" in paths
    assert any(path.startswith("/v1/auth/google/") for path in paths)
    assert "/healthz" in paths


def test_main_sentinel_absence_on_refusal(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys
) -> None:
    """A sentinel audience failing validation exits coarse WITHOUT the
    value appearing on either stream (Settings echoes offending input)."""
    monkeypatch.setattr(native, "check_schema_head", lambda url: None)
    sentinel = f"SENTINEL-{uuid4().hex}"
    args = _composition_args(tmp_path, google_audience=sentinel)
    code = native.main([
        "--database-url", args.database_url,
        "--hmac-secret", args.hmac_secret,
        "--pin-out", args.pin_out,
        "--otp-sink", args.otp_sink,
        "--blob-root", args.blob_root,
        "--mode", args.mode,
        "--google-audience", args.google_audience,
        "--google-presenters", args.google_presenters,
        "--android-origin", args.android_origin,
    ])
    assert code == 2
    captured = capsys.readouterr()
    assert "refused" in captured.err
    assert sentinel not in captured.err
    assert sentinel not in captured.out
    assert not Path(args.pin_out).exists()
    assert not Path(args.otp_sink).exists()


def test_main_maps_settings_validation_error_coarse(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys
) -> None:
    """Defense-in-depth mapping: a Settings ValidationError (whose
    message echoes the offending value) becomes a fixed coarse code
    with the sentinel on neither stream."""
    from pydantic import ValidationError as _ValidationError

    monkeypatch.setattr(native, "check_schema_head", lambda url: None)

    def _boom(**kwargs):
        raise _ValidationError.from_exception_data(
            "Settings", [{"type": "value_error", "loc": ("x",),
                          "msg": "value MARKER-ECHO-SECRET is not allowed",
                          "input": "MARKER-ECHO-SECRET"}])

    monkeypatch.setattr(native, "build_settings", _boom)
    args = _composition_args(tmp_path)
    code = native.main([
        "--database-url", args.database_url,
        "--hmac-secret", args.hmac_secret,
        "--pin-out", args.pin_out,
        "--otp-sink", args.otp_sink,
        "--blob-root", args.blob_root,
        "--mode", args.mode,
        "--google-audience", args.google_audience,
        "--google-presenters", args.google_presenters,
        "--android-origin", args.android_origin,
    ])
    assert code == 2
    captured = capsys.readouterr()
    assert "STARTUP_SETTINGS_FAILED" in captured.err
    assert "MARKER-ECHO-SECRET" not in captured.err
    assert "MARKER-ECHO-SECRET" not in captured.out


def test_shared_ctx_builder_validates_keys() -> None:
    from remanence.pending.dev_bootstrap import BootstrapRefusal, build_pending_ctx

    good = {name: bytes([7]) * 32 for name in (
        "hmac_key", "destination_key", "otp_key",
        "proof_mac_key", "evidence_key", "locator_code_key")}
    ctx = build_pending_ctx(
        **good, vault=None, service_key_id=None, service_signer=None,
        email_sender=None, sms_provider=None,
    )
    for name, value in good.items():
        assert ctx[name] is value
    bad = dict(good, otp_key=bytes([7]) * 31)
    with pytest.raises(BootstrapRefusal):
        build_pending_ctx(
            **bad, vault=None, service_key_id=None, service_signer=None,
            email_sender=None, sms_provider=None,
        )
