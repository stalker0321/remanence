"""Opt-in native Google + pending dev launcher (T202, writer-built, root-run).

Combines the reviewed pending dev composition (synthetic custody:
dev AEAD vault, ephemeral service signer, file-sink mock email, mock
SMS) with REAL Google verification and recovery configured for the
native RP/Android origin and the Web audience. This is AVD
preparation, NOT provider acceptance or a remotely usable release.

Fail-closed rules (exit code 2, nothing served):
- bind is FIXED to exact 127.0.0.1:18046 (no override surface);
  mode is dev/test only (prod/beta refused).
- database must be a fresh unique ``remanence_tmp_*`` synthetic DB
  (live/t181_clone refused); schema must already be at alembic head
  (read-only check — never migrates/seeds/drops; one start is a new
  epoch, never resume). All server-side keys are ephemeral per start;
  no key persistence by design.
- recovery ON with fixed RP ``remanence.hryshyn.dev``; allowed
  origins are EXACTLY the RP HTTPS origin plus one explicitly
  supplied canonical ``android:apk-key-hash:<43 base64url>`` origin.
- Google ON with an explicitly supplied public Web client audience
  plus Android presenter ID(s), validated through the ACTUAL
  Settings constructor (never model_copy/update bypass); the real
  Google verifier/nonce/issuer/audience/presenter/email checks are
  preserved untouched. No hardcoded OAuth project IDs or signer
  strings anywhere in product code.
- explicit config wins over environment for every security-relevant
  flag (and the built posture is re-asserted field-by-field);
  dangerous prod/beta or pending conflicts are refused consistently
  with the existing launcher guards.
- silent HTTP access logs; only coarse public startup/error status;
  no passwords/tokens/bodies/secrets logged; KeyboardInterrupt and
  SystemExit propagate (never swallowed).

Run from ``server/`` (root only)::

    uv run python scripts/pending_native_server.py \\
        --database-url postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_<unique> \\
        --hmac-secret <32+char-secret> --pin-out /tmp/remanence-native-pin.json \\
        --otp-sink /tmp/remanence-native-otp.jsonl --blob-root /tmp/remanence-native-blobs \\
        --google-audience <web-client>.apps.googleusercontent.com \\
        --google-presenters <android-client>.apps.googleusercontent.com \\
        --android-origin 'android:apk-key-hash:<43 base64url>'

Root provisions the DB + ``alembic upgrade head`` first, and hands
the pin JSON + Google/audience config to the device setup out-of-band.
"""

from __future__ import annotations

import argparse
import base64
import re
import secrets
import sys
from pathlib import Path

from remanence.pending.dev_bootstrap import (
    BootstrapRefusal as _UpstreamRefusal,
)
from remanence.pending.dev_bootstrap import (
    DEV_VAULT_KEY_ID,
    build_pending_ctx,
    build_pin_json,
    check_schema_head,
    database_name_of,
    generate_service_signer,
    redacted_url,
    resolve_hmac_secret,
    touch_otp_sink,
    write_pin_json,
)

NATIVE_HOST = "127.0.0.1"
NATIVE_PORT = 18046
NATIVE_RP_ID = "remanence.hryshyn.dev"
NATIVE_RP_ORIGIN = "https://remanence.hryshyn.dev"

# Mirrors Settings.require_datastore_for_non_test exactly (same
# pattern, same error class family): audience/presenter IDs are public
# config, so refusal messages name the field, never values.
_GOOGLE_ID_PATTERN = r"[A-Za-z0-9_-]+\.apps\.googleusercontent\.com"
_ANDROID_ORIGIN_PREFIX = "android:apk-key-hash:"


class NativeRefusal(Exception):
    """Fail-closed native launcher refusal (main() maps to exit code 2)."""


def fixed_bind() -> tuple[str, int]:
    """The ONLY bind this launcher ever serves (no override surface)."""
    return NATIVE_HOST, NATIVE_PORT


def resolve_google_audience(raw: object) -> str:
    if not isinstance(raw, str) or not re.fullmatch(_GOOGLE_ID_PATTERN, raw):
        raise NativeRefusal(
            "refusing --google-audience: must be <id>.apps.googleusercontent.com"
        )
    return raw


def resolve_google_presenters(raw: object) -> list[str]:
    if not isinstance(raw, str):
        raise NativeRefusal("refusing --google-presenters: comma-separated client IDs required")
    presenters = [part.strip() for part in raw.split(",")]
    presenters = [part for part in presenters if part]
    if not presenters:
        raise NativeRefusal("refusing --google-presenters: at least one client ID required")
    for presenter in presenters:
        if not re.fullmatch(_GOOGLE_ID_PATTERN, presenter):
            raise NativeRefusal(
                "refusing --google-presenters: every ID must be <id>.apps.googleusercontent.com"
            )
    return presenters


def resolve_android_origin(raw: object) -> str:
    """Exactly one canonical android:apk-key-hash origin (mirrors the
    Settings validator, including the canonical re-encode check)."""
    if not isinstance(raw, str) or not raw.startswith(_ANDROID_ORIGIN_PREFIX):
        raise NativeRefusal(
            "refusing --android-origin: must be android:apk-key-hash:<43 base64url>"
        )
    value = raw.removeprefix(_ANDROID_ORIGIN_PREFIX)
    try:
        canonical = (
            base64.urlsafe_b64encode(base64.urlsafe_b64decode(value + "="))
            .decode()
            .rstrip("=")
        )
    except Exception:
        raise NativeRefusal("refusing --android-origin: malformed key hash") from None
    if not re.fullmatch(r"[A-Za-z0-9_-]{43}", value) or canonical != value:
        raise NativeRefusal("refusing --android-origin: non-canonical key hash")
    return raw


def build_settings(
    *,
    database_url: str,
    blob_root: Path,
    mode: str,
    hmac_secret: str,
    google_audience: str,
    google_presenters: list[str],
    android_origin: str,
):
    """Fresh Settings via the ACTUAL constructor (validators run; never
    model_copy/update bypass). Afterwards the security posture is
    re-asserted field-by-field so no environment value can silently
    merge in: explicit config wins, verifiably."""
    from remanence.settings import AppMode, Settings

    if mode not in ("dev", "test"):
        raise NativeRefusal(f"refusing mode {mode!r}: dev/test only")
    app_mode = AppMode.DEV if mode == "dev" else AppMode.TEST
    settings = Settings(
        mode=app_mode,
        database_url=database_url,
        blob_root=blob_root,
        pending_protocol_enabled=True,
        pending_sends_enabled=True,
        pending_claims_enabled=True,
        pending_locator_issuance_enabled=True,
        pending_hmac_secret=hmac_secret,
        recovery_enabled=True,
        recovery_rp_id=NATIVE_RP_ID,
        recovery_origins=[NATIVE_RP_ORIGIN, android_origin],
        google_sign_in_enabled=True,
        google_client_ids=[google_audience],
        google_presenter_ids=list(google_presenters),
    )
    if settings.mode in (AppMode.PROD, AppMode.BETA):
        raise NativeRefusal("refusing prod/beta: dev/test only")
    if settings.google_client_ids != [google_audience]:
        raise NativeRefusal("refusing google audience mismatch")
    if settings.google_presenter_ids != list(google_presenters):
        raise NativeRefusal("refusing google presenter mismatch")
    if settings.recovery_origins != [NATIVE_RP_ORIGIN, android_origin]:
        raise NativeRefusal("refusing recovery origin mismatch")
    if settings.recovery_rp_id != NATIVE_RP_ID or not settings.recovery_enabled:
        raise NativeRefusal("refusing recovery misconfiguration")
    if settings.pending_kms_key_id is not None:
        raise NativeRefusal("refusing unexpected KMS custody in dev launcher")
    return settings


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Opt-in native Google + pending dev server (fixed loopback bind)."
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
    return parser


def build_composition(args: argparse.Namespace):
    """Validate guards and compose (settings, pending_ctx, pin, paths).
    No sockets, no serving — unit-testable. Raises NativeRefusal (and
    pydantic ValidationError from the real Settings constructor, which
    main() maps coarse without message)."""
    from remanence.pending.providers import FileSinkEmailSender, MockSmsVerify
    from remanence.pending.vault import DevSealedVault

    host, port = fixed_bind()
    try:
        db_name = database_name_of(args.database_url)
    except _UpstreamRefusal as exc:
        raise NativeRefusal(str(exc)) from None
    try:
        check_schema_head(args.database_url)
    except _UpstreamRefusal as exc:
        raise NativeRefusal(str(exc)) from None
    try:
        hmac_key = resolve_hmac_secret(args.hmac_secret)
    except _UpstreamRefusal as exc:
        raise NativeRefusal(str(exc)) from None
    audience = resolve_google_audience(args.google_audience)
    presenters = resolve_google_presenters(args.google_presenters)
    android_origin = resolve_android_origin(args.android_origin)
    # Ephemeral per-start epoch material (signer + proof + destination
    # + vault + code): never resume an old DB, persist no secrets.
    vault_key = secrets.token_bytes(32)
    mode_value = args.mode
    signer = generate_service_signer()
    pin = build_pin_json(
        service_key_id=signer.key_id,
        public_keyset_b64=signer.public_keyset_b64,
        vault_key_id=DEV_VAULT_KEY_ID,
    )
    sender = FileSinkEmailSender(path=args.otp_sink, mode=mode_value)
    ctx = build_pending_ctx(
        hmac_key=hmac_key,
        destination_key=secrets.token_bytes(32),
        otp_key=secrets.token_bytes(32),
        proof_mac_key=secrets.token_bytes(32),
        evidence_key=secrets.token_bytes(32),
        vault=DevSealedVault(mode=mode_value, key_id=DEV_VAULT_KEY_ID, key_32=vault_key),
        service_key_id=signer.key_id,
        service_signer=signer,
        email_sender=sender,
        sms_provider=MockSmsVerify(mode=mode_value),
        locator_code_key=secrets.token_bytes(32),
    )
    blob_root = Path(args.blob_root)
    blob_root.mkdir(parents=True, exist_ok=True)
    settings = build_settings(
        database_url=args.database_url,
        blob_root=blob_root,
        mode=mode_value,
        hmac_secret=hmac_key.decode("utf-8"),
        google_audience=audience,
        google_presenters=presenters,
        android_origin=android_origin,
    )
    return host, port, db_name, settings, ctx, pin, signer


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        host, port, db_name, settings, ctx, pin, signer = build_composition(args)
        try:
            write_pin_json(args.pin_out, pin)
        except _UpstreamRefusal as exc:
            raise NativeRefusal(str(exc)) from None
        try:
            touch_otp_sink(args.otp_sink)
        except _UpstreamRefusal as exc:
            raise NativeRefusal(str(exc)) from None
    except NativeRefusal as exc:
        print(f"pending-native-server: refused: {exc}", file=sys.stderr)
        return 2
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        # Fixed coarse code only: Settings ValidationErrors echo the
        # offending value, so their text must NEVER print.
        print("pending-native-server: failed: STARTUP_SETTINGS_FAILED", file=sys.stderr)
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
        print("pending-native-server: failed: STARTUP_APP_FAILED", file=sys.stderr)
        return 2
    print(
        "pending-native-server: serving "
        f"{host}:{port} db={redacted_url(args.database_url)} "
        f"rp_id={NATIVE_RP_ID} google_audience={settings.google_client_ids[0]} "
        f"service_key_id={signer.key_id} vault={DEV_VAULT_KEY_ID} "
        f"pin_out={args.pin_out} otp_sink={args.otp_sink}",
        flush=True,
    )
    print(
        "pending-native-server: one run is one epoch — ALWAYS restart with a "
        "fresh remanence_tmp_* DB (all server-side keys rotate per start) "
        "and hand the fresh pin JSON to the Android config out-of-band",
        flush=True,
    )
    try:
        import uvicorn

        uvicorn.run(app, host=host, port=port, access_log=False, log_level="warning")
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-native-server: failed: STARTUP_SERVE_FAILED", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
