"""Restartable isolated native test backend launcher (T205, root-run).

Owner-approved NEW backend with persisted server keys (synthetic test
custody ONLY). Same native Google + recovery + pending composition as
T202, but epoch material lives in an explicit versioned state bundle
(`durable_state.py`) instead of per-start ephemeral randomness:
restarts reconstruct the IDENTICAL custody (stable service UUID/pin
and keys) against the SAME database. NEVER restart the old
memory-only epochs on their DBs with new secrets — this backend
takes a NEW `remanence_tmp_native_persistent_*` DB and a NEW state
dir, leaving every old epoch untouched.

Fail-closed rules (exit code 2, nothing served):
- bind is FIXED to exact 127.0.0.1:18047 (no override surface);
  mode is dev/test only (prod/beta refused).
- `--init` creates state EXCLUSIVELY (absent dir, atomic write) into
  a fresh empty NEW database (read-only head + emptiness checks —
  never migrates/seeds/drops/imports old DBs). Without `--init`,
  resume requires the existing valid bundle BOUND to the exact
  configured DB/deployment (name, blob root, mode, Google
  audience/presenters, recovery RP/origins, HMAC equality);
  missing/corrupt/permissive/symlinked/mismatched state fails
  closed and NEVER generates replacement keys.
- recovery ON with fixed RP ``remanence.hryshyn.dev`` and exactly
  the RP HTTPS origin + one canonical android origin; Google ON
  with explicit Web audience + Android presenters through the real
  Settings constructor (never bypassed); no hardcoded OAuth IDs.
- silent HTTP access logs; only coarse public startup/error status;
  no passwords/tokens/bodies/secrets logged; KeyboardInterrupt and
  SystemExit propagate (never swallowed).

Run from ``server/`` (root only)::

    uv run python scripts/pending_durable_server.py --init \\
        --database-url postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_native_persistent_<unique> \\
        --hmac-secret <32+char-secret> --pin-out /tmp/remanence-durable-pin.json \\
        --otp-sink /tmp/remanence-durable-otp.jsonl --blob-root /tmp/remanence-durable-blobs \\
        --google-audience <web-client>.apps.googleusercontent.com \\
        --google-presenters <android-client>.apps.googleusercontent.com \\
        --android-origin 'android:apk-key-hash:<43 base64url>' \\
        --state-dir /tmp/remanence-durable-state-<unique>

Omit --init to resume the same state dir + DB. Root provisions the
DB + ``alembic upgrade head`` first, installs the OS service after
immutable review (never a transient exec session for custody), and
hands the stable pin JSON to the device setup out-of-band.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from sqlalchemy.engine import make_url

from remanence.pending.dev_bootstrap import BootstrapRefusal as _UpstreamRefusal
from remanence.pending.dev_bootstrap import (
    build_pending_ctx,
    build_pin_json,
    check_schema_head,
    database_name_of,
    redacted_url,
    resolve_hmac_secret,
    touch_otp_sink,
    write_pin_json,
)
from remanence.pending.durable_state import (
    DurableRefusal,
    build_bundle,
    check_db_empty,
    generate_epoch_material,
    init_bundle,
    load_bundle,
)
from remanence.pending.native_bootstrap import (
    NativeRefusal,
    build_settings,
    resolve_android_origin,
    resolve_google_audience,
    resolve_google_presenters,
)

DURABLE_HOST = "127.0.0.1"
DURABLE_PORT = 18047

_DB_SHAPE = re.compile(r"^remanence_tmp_native_persistent_[A-Za-z0-9_][A-Za-z0-9_.\-]*$")


def fixed_bind() -> tuple[str, int]:
    """The ONLY bind this launcher ever serves (no override surface)."""
    return DURABLE_HOST, DURABLE_PORT


def durable_database_name_of(database_url: str) -> str:
    """Durable backend takes ONLY new persistent-epoch DBs (never old
    memory-only epochs, never live/t181_clone)."""
    try:
        name = database_name_of(database_url)
    except _UpstreamRefusal as exc:
        raise DurableRefusal(str(exc)) from None
    if name == "t181_clone":
        raise DurableRefusal("refusing protected t181_clone: use a new persistent DB")
    if not _DB_SHAPE.match(name):
        raise DurableRefusal(
            "refusing database: durable backend uses "
            "remanence_tmp_native_persistent_* only (never old epochs)"
        )
    return name


def binding_from_args(
    args: argparse.Namespace,
    *,
    database_url: str,
    google_audience: str,
    google_presenters: list[str],
    android_origin: str,
) -> dict:
    url = make_url(database_url)
    return {
        "db_name": url.database or "",
        "db_host": url.host or "",
        "db_port": str(url.port or ""),
        "blob_root": str(Path(args.blob_root).resolve()),
        "mode": args.mode,
        "google_audience": google_audience,
        "google_presenters": list(google_presenters),
        "recovery_rp_id": "remanence.hryshyn.dev",
        "recovery_origins": [
            "https://remanence.hryshyn.dev",
            android_origin,
        ],
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Restartable isolated native test backend (fixed loopback bind)."
    )
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--hmac-secret", default=None)
    parser.add_argument("--pin-out", required=True)
    parser.add_argument("--otp-sink", required=True)
    parser.add_argument("--blob-root", required=True)
    parser.add_argument("--mode", choices=("dev", "test"), default="dev")
    parser.add_argument("--google-audience", required=True)
    parser.add_argument("--google-presenters", required=True)
    parser.add_argument("--android-origin", required=True)
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--init", action="store_true",
                        help="exclusive first-time state creation (absent dir + empty DB)")
    return parser


def build_composition(args: argparse.Namespace):
    """Validate guards and compose (settings, pending_ctx, pin, bundle
    material). No sockets, no serving — unit-testable. Init generates
    fresh epoch material into an absent state dir; resume reconstructs
    byte-identical custody from the bound bundle (never regenerates).
    Raises DurableRefusal (and pydantic ValidationError from the real
    Settings constructor, mapped coarse by main())."""
    from sqlalchemy import create_engine

    from remanence.pending.providers import FileSinkEmailSender, MockSmsVerify
    from remanence.pending.vault import DevSealedVault

    host, port = fixed_bind()
    db_name = durable_database_name_of(args.database_url)
    try:
        check_schema_head(args.database_url)
    except _UpstreamRefusal as exc:
        raise DurableRefusal(str(exc)) from None
    try:
        hmac_key = resolve_hmac_secret(args.hmac_secret)
    except _UpstreamRefusal as exc:
        raise DurableRefusal(str(exc)) from None
    try:
        audience = resolve_google_audience(args.google_audience)
        presenters = resolve_google_presenters(args.google_presenters)
        android_origin = resolve_android_origin(args.android_origin)
    except NativeRefusal as exc:
        raise DurableRefusal(str(exc)) from None
    blob_root = Path(args.blob_root)
    blob_root.mkdir(parents=True, exist_ok=True)
    binding = binding_from_args(
        args,
        database_url=args.database_url,
        google_audience=audience,
        google_presenters=presenters,
        android_origin=android_origin,
    )
    engine = create_engine(args.database_url)
    try:
        if args.init:
            check_db_empty(engine)
            material = generate_epoch_material()
            bundle = build_bundle(material=material, binding=binding, hmac_key=hmac_key)
            init_bundle(args.state_dir, bundle)
            material["hmac_key"] = hmac_key
        else:
            material = load_bundle(
                args.state_dir,
                expected={**binding, "hmac_key": hmac_key},
            )
    finally:
        engine.dispose()
    mode_value = args.mode
    sender = FileSinkEmailSender(path=args.otp_sink, mode=mode_value)
    ctx = build_pending_ctx(
        hmac_key=material["hmac_key"],
        destination_key=material["destination_key"],
        otp_key=material["otp_key"],
        proof_mac_key=material["proof_mac_key"],
        evidence_key=material["evidence_key"],
        vault=DevSealedVault(
            mode=mode_value, key_id=material["vault_key_id"], key_32=material["vault_key"]
        ),
        service_key_id=material["service_key_id"],
        service_signer=material["service_signer"],
        email_sender=sender,
        sms_provider=MockSmsVerify(mode=mode_value),
        locator_code_key=material["locator_code_key"],
    )
    settings = build_settings(
        database_url=args.database_url,
        blob_root=blob_root,
        mode=mode_value,
        hmac_secret=hmac_key.decode("utf-8"),
        google_audience=binding["google_audience"],
        google_presenters=binding["google_presenters"],
        android_origin=android_origin,
    )
    pin = build_pin_json(
        service_key_id=material["service_key_id"],
        public_keyset_b64=material["service_public_keyset"],
        vault_key_id=material["vault_key_id"],
    )
    return host, port, db_name, settings, ctx, pin, material


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        host, port, db_name, settings, ctx, pin, material = build_composition(args)
        try:
            write_pin_json(args.pin_out, pin)
        except _UpstreamRefusal as exc:
            raise DurableRefusal(str(exc)) from None
        try:
            touch_otp_sink(args.otp_sink)
        except _UpstreamRefusal as exc:
            raise DurableRefusal(str(exc)) from None
    except DurableRefusal as exc:
        print(f"pending-durable-server: refused: {exc}", file=sys.stderr)
        return 2
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-durable-server: failed: STARTUP_SETTINGS_FAILED", file=sys.stderr)
        return 2
    try:
        from remanence.db.session import build_engine, build_session_factory
        from remanence.main import create_app

        engine = build_engine(settings)
        factory = build_session_factory(engine)
        app = create_app(settings=settings, session_factory=factory, pending_ctx=ctx)
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-durable-server: failed: STARTUP_APP_FAILED", file=sys.stderr)
        return 2
    print(
        "pending-durable-server: serving "
        f"{host}:{port} db={redacted_url(args.database_url)} "
        f"rp_id=remanence.hryshyn.dev google_audience={settings.google_client_ids[0]} "
        f"service_key_id={material['service_key_id']} vault={material['vault_key_id']} "
        f"pin_out={args.pin_out} otp_sink={args.otp_sink}",
        flush=True,
    )
    print(
        "pending-durable-server: durable epoch — restarts reconstruct this "
        "exact custody from the bound state bundle (never regenerate, never "
        "resume old memory-only epochs); hand the stable pin JSON to the "
        "device setup out-of-band",
        flush=True,
    )
    try:
        import uvicorn

        uvicorn.run(app, host=host, port=port, access_log=False, log_level="warning")
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-durable-server: failed: STARTUP_SERVE_FAILED", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
