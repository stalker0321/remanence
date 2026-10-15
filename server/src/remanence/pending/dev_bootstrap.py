"""Isolated dev-only pending bootstrap launcher (T185 dev milestone).

Root runs this later (it is NOT run by the writer): it composes the real
``create_app(settings, session_factory, pending_ctx=...)`` with a fully
synthetic custody context — dev AEAD vault, ephemeral synthetic service
signer, file-sink mock email, mock SMS — and serves it on loopback for
HTTP+device pipeline work. Synthetic data only; no live beta, no real
provider sends, no production enablement.

Fail-closed rules (exit code 2, nothing served):
- bind host must be loopback (127.0.0.1/::1/localhost); mode is
  dev/test only (prod/beta refused); database name must be a unique
  ``remanence_tmp_*`` synthetic DB (``t181_clone`` and any other name
  refused); schema must already be at alembic head (this launcher never
  migrates — root runs the upgrade first; the check is read-only).
- The service signer is generated EPHEMERAL per start and lives ONLY in
  process memory. ONLY the public pin JSON is written (``--pin-out``);
  no private key is ever written to repo/tasks/logs/chat. One run is
  one epoch: ALWAYS restart with a FRESH unique DB — the signer plus
  the proof/destination/vault/code keys all rotate per start, so
  reusing a vault key alone cannot resume an epoch (and no secrets are
  persisted anywhere by design).
- Secrets/keys/codes are never logged (DB host/name only, pin paths,
  key UUIDs/ids only).

Run from ``server/`` (root only, after the scout + review)::

    uv run python scripts/pending_dev_server.py \\
        --database-url postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_<unique> \\
        --hmac-secret <32+char-secret> --pin-out /tmp/remanence-dev-pin.json \\
        --otp-sink /tmp/remanence-dev-otp.jsonl
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import secrets
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path

LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})
DEFAULT_PORT = 18042
DEV_VAULT_NAME = "dev-aead-v1"
DEV_VAULT_KEY_ID = "dev-bootstrap-1"
CLAIM_BINDING_DOMAIN = "postmark/claim-binding/v1"

# Exact allowlist: the pin file may ONLY carry these public fields.
PIN_JSON_KEYS = frozenset(
    {
        "schema_version",
        "note",
        "service_key_id",
        "service_ed25519_public_keyset_b64",
        "algorithm",
        "claim_binding_domain",
        "generated_at",
        "vault_key_id",
    }
)


class BootstrapRefusal(Exception):
    """Fail-closed launcher refusal (main() maps to exit code 2)."""


@dataclass(frozen=True, slots=True)
class DevServiceSigner:
    """Ephemeral in-process service signer. The private primitive NEVER
    leaves process memory; only ``public_keyset_b64`` + ``key_id`` are
    exported via :func:`build_pin_json`."""

    key_id: uuid.UUID
    public_keyset_b64: str
    _signer: object = None

    def __repr__(self) -> str:
        return "DevServiceSigner(<redacted>)"

    def sign(self, data: bytes) -> bytes:
        return self._signer.sign(data)


def refuse_unless_loopback(host: str) -> str:
    if not isinstance(host, str) or host.strip().lower() not in LOOPBACK_HOSTS:
        raise BootstrapRefusal(
            f"refusing non-loopback bind host {host!r}: dev bootstrap serves loopback only"
        )
    return host


def database_name_of(database_url: str) -> str:
    if not database_url or not isinstance(database_url, str):
        raise BootstrapRefusal("refusing empty --database-url")
    if not database_url.startswith("postgresql+psycopg://"):
        raise BootstrapRefusal("refusing --database-url without postgresql+psycopg:// scheme")
    try:
        from sqlalchemy.engine import make_url

        name = make_url(database_url).database or ""
    except Exception as exc:
        raise BootstrapRefusal(f"refusing unparseable --database-url: {type(exc).__name__}") from None
    if name == "t181_clone":
        raise BootstrapRefusal("refusing protected t181_clone: use a unique remanence_tmp_* DB")
    if not name.startswith("remanence_tmp_"):
        raise BootstrapRefusal(
            f"refusing database {name!r}: dev bootstrap uses unique remanence_tmp_* DBs only"
        )
    return name


def redacted_url(database_url: str) -> str:
    """Log-safe DB identity: host + database only, never credentials."""
    try:
        from sqlalchemy.engine import make_url

        url = make_url(database_url)
        return f"{url.host}:{url.port}/{url.database}"
    except Exception:
        return "<unparseable-database-url>"


def _alembic_ini() -> Path:
    # .../server/src/remanence/pending/dev_bootstrap.py -> server/alembic.ini
    return Path(__file__).resolve().parents[3] / "alembic.ini"


def check_schema_head(database_url: str) -> None:
    """Read-only guard: the target DB must already be at alembic head.
    This launcher never migrates. Raises BootstrapRefusal with the exact
    root command otherwise. No tables are created or written here."""
    from alembic.config import Config
    from alembic.script import ScriptDirectory
    from sqlalchemy import create_engine, text

    config = Config(str(_alembic_ini()))
    heads = set(ScriptDirectory.from_config(config).get_heads())
    engine = create_engine(database_url)
    try:
        with engine.connect() as connection:
            try:
                current = {
                    row[0]
                    for row in connection.execute(text("SELECT version_num FROM alembic_version"))
                }
            except Exception as exc:
                raise BootstrapRefusal(
                    "refusing unmigrated database (no alembic_version): root runs "
                    "`uv run alembic upgrade head` with REMANENCE_DATABASE_URL set first"
                ) from None
    finally:
        engine.dispose()
    if current != heads:
        raise BootstrapRefusal(
            f"refusing schema {sorted(current)} != head {sorted(heads)}: root runs "
            "`uv run alembic upgrade head` with REMANENCE_DATABASE_URL set first"
        )


def generate_service_signer() -> DevServiceSigner:
    """Generate one ephemeral Ed25519 service signer (real Tink). The
    private half stays in the returned object (process memory only)."""
    import tink
    from tink import signature, tink_config

    tink_config.register()
    private = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
    buf = io.BytesIO()
    private.public_keyset_handle().write_no_secret(tink.BinaryKeysetWriter(buf))
    return DevServiceSigner(
        key_id=uuid.uuid4(),
        public_keyset_b64=base64.b64encode(buf.getvalue()).decode("ascii"),
        _signer=private.primitive(signature.PublicKeySign),
    )


def build_pin_json(
    *, service_key_id: uuid.UUID, public_keyset_b64: str, vault_key_id: str
) -> dict:
    """Public pin document for the Android deployment config (T186).
    UUID form (never a numeric Tink key id); keys carried next to R are
    never trust roots — only this out-of-band pin is."""
    from datetime import datetime, timezone

    pin = {
        "schema_version": 1,
        "note": (
            "TEST-ONLY dev-bootstrap epoch pin. Synthetic Ephemeral Ed25519 "
            "keypair generated at server start; ONLY the public keyset is "
            "stored here. Isolated loopback + synthetic DB runs ONLY. NEVER "
            "production custody: deployment requires an independently "
            "configured pin history, never this pair. Refresh per run."
        ),
        "service_key_id": str(service_key_id),
        "service_ed25519_public_keyset_b64": public_keyset_b64,
        "algorithm": "ED25519 (Tink 69-byte framing, same as sender publish signatures)",
        "claim_binding_domain": CLAIM_BINDING_DOMAIN,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "vault_key_id": vault_key_id,
    }
    if set(pin) != PIN_JSON_KEYS:
        raise BootstrapRefusal("refusing pin with unexpected fields")
    blob = json.dumps(pin).lower()
    if "private" in blob or "secret" in blob:
        raise BootstrapRefusal("refusing pin carrying non-public material")
    uuid.UUID(pin["service_key_id"])
    return pin


def write_pin_json(path: str, pin: dict) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    if not os.path.isdir(parent):
        raise BootstrapRefusal(f"refusing --pin-out without existing parent dir: {parent}")
    if set(pin) != PIN_JSON_KEYS:
        raise BootstrapRefusal("refusing pin with unexpected fields")
    import json as _json

    with open(path, "w", encoding="utf-8") as handle:
        _json.dump(pin, handle, indent=2)
        handle.write("\n")

def touch_otp_sink(path: str) -> None:
    """Create the OTP sink file 0600 if absent and append an epoch-start
    marker, so root can tail exactly one run. Parent must exist."""
    import json as _json
    import time as _time

    parent = os.path.dirname(os.path.abspath(path))
    if not os.path.isdir(parent):
        raise BootstrapRefusal(f"refusing --otp-sink without existing parent dir: {parent}")
    fd = os.open(path, os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
    try:
        os.write(
            fd,
            (
                _json.dumps({"event": "epoch-start", "at": _time.time()})
                + "\n"
            ).encode("utf-8"),
        )
    finally:
        os.close(fd)


def resolve_hmac_secret(explicit: str | None) -> bytes:
    raw = explicit or os.environ.get("PENDING_DEV_HMAC_SECRET")
    if not raw:
        raise BootstrapRefusal(
            "refusing without HMAC secret: pass --hmac-secret or PENDING_DEV_HMAC_SECRET"
        )
    key = raw.encode("utf-8")
    if len(key) < 32:
        raise BootstrapRefusal("refusing short HMAC secret: use 32+ characters")
    return key


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Isolated dev-only pending server (loopback, synthetic custody)."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--hmac-secret", default=None)
    parser.add_argument("--pin-out", required=True)
    parser.add_argument("--otp-sink", required=True)
    parser.add_argument("--blob-root", default="var/dev-bootstrap-blobs")
    parser.add_argument("--mode", choices=("dev", "test"), default="dev")
    return parser


def build_pending_ctx(
    *,
    hmac_key: bytes,
    destination_key: bytes,
    otp_key: bytes,
    proof_mac_key: bytes,
    evidence_key: bytes,
    vault,
    service_key_id,
    service_signer,
    email_sender,
    sms_provider,
    locator_code_key: bytes,
) -> dict:
    """Assemble the synthetic pending custody context from explicit
    parts (shared by the pending-only and native launchers so both
    wire the exact same keys — no silent divergence). The caller HMAC
    keeps the old parser contract (bytes length >= 32, passed through
    byte-identical: no truncation, hash, or re-encoding); internally
    generated destination/otp/proof/evidence/locator keys remain
    exactly 32 bytes. Anything else fails closed. No I/O, no
    randomness here: callers generate their own per-start epoch
    material."""
    if not isinstance(hmac_key, bytes) or len(hmac_key) < 32:
        raise BootstrapRefusal("refusing hmac_key: must be >= 32 bytes")
    for name, key in (
        ("destination_key", destination_key),
        ("otp_key", otp_key),
        ("proof_mac_key", proof_mac_key),
        ("evidence_key", evidence_key),
        ("locator_code_key", locator_code_key),
    ):
        if not isinstance(key, bytes) or len(key) != 32:
            raise BootstrapRefusal(f"refusing {name}: must be 32 bytes")
    return {
        "hmac_key": hmac_key,
        "destination_key": destination_key,
        "otp_key": otp_key,
        "proof_mac_key": proof_mac_key,
        "evidence_key": evidence_key,
        "vault": vault,
        "service_key_id": service_key_id,
        "service_signer": service_signer,
        "email_sender": email_sender,
        "sms_provider": sms_provider,
        "locator_code_key": locator_code_key,
    }


def build_composition(args: argparse.Namespace):
    """Validate guards and compose (settings, pending_ctx, pin, paths).
    No sockets, no serving — unit-testable. Raises BootstrapRefusal."""
    from remanence.pending.providers import FileSinkEmailSender, MockSmsVerify
    from remanence.pending.vault import DevSealedVault
    from remanence.settings import AppMode, Settings

    host = refuse_unless_loopback(args.host)
    if not isinstance(args.port, int) or not 1 <= args.port <= 65535:
        raise BootstrapRefusal(f"refusing port {args.port!r}")
    db_name = database_name_of(args.database_url)
    check_schema_head(args.database_url)
    hmac_key = resolve_hmac_secret(args.hmac_secret)
    # All server-side keys are ephemeral per start (signer + proof +
    # destination + vault + code): epochs never resume, so restarts
    # ALWAYS take a fresh DB. No secrets are persisted by design.
    vault_key = secrets.token_bytes(32)
    mode = AppMode.DEV if args.mode == "dev" else AppMode.TEST

    signer = generate_service_signer()
    pin = build_pin_json(
        service_key_id=signer.key_id,
        public_keyset_b64=signer.public_keyset_b64,
        vault_key_id=DEV_VAULT_KEY_ID,
    )
    sender = FileSinkEmailSender(path=args.otp_sink, mode=mode.value)
    ctx = build_pending_ctx(
        hmac_key=hmac_key,
        destination_key=secrets.token_bytes(32),
        otp_key=secrets.token_bytes(32),
        proof_mac_key=secrets.token_bytes(32),
        evidence_key=secrets.token_bytes(32),
        vault=DevSealedVault(mode=mode.value, key_id=DEV_VAULT_KEY_ID, key_32=vault_key),
        service_key_id=signer.key_id,
        service_signer=signer,
        email_sender=sender,
        sms_provider=MockSmsVerify(mode=mode.value),
        locator_code_key=secrets.token_bytes(32),
    )
    blob_root = Path(args.blob_root)
    blob_root.mkdir(parents=True, exist_ok=True)
    settings = Settings(
        mode=mode,
        database_url=args.database_url,
        blob_root=blob_root,
        pending_protocol_enabled=True,
        pending_sends_enabled=True,
        pending_claims_enabled=True,
        pending_locator_issuance_enabled=True,
        pending_hmac_secret=hmac_key.decode("utf-8"),
    )
    return host, db_name, settings, ctx, pin, signer


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        host, db_name, settings, ctx, pin, signer = build_composition(args)
        write_pin_json(args.pin_out, pin)
        touch_otp_sink(args.otp_sink)
    except BootstrapRefusal as exc:
        print(f"pending-dev-server: refused: {exc}", file=sys.stderr)
        return 2
    from remanence.db.session import build_engine, build_session_factory
    from remanence.main import create_app

    engine = build_engine(settings)
    factory = build_session_factory(engine)
    app = create_app(settings=settings, session_factory=factory, pending_ctx=ctx)
    print(
        "pending-dev-server: serving "
        f"{host}:{args.port} db={redacted_url(args.database_url)} "
        f"service_key_id={signer.key_id} vault={DEV_VAULT_NAME}/{DEV_VAULT_KEY_ID} "
        f"pin_out={args.pin_out} otp_sink={args.otp_sink}",
        flush=True,
    )
    print(
        "pending-dev-server: one run is one epoch — ALWAYS restart with a "
        "fresh remanence_tmp_* DB (all server-side keys rotate per start) "
        "and hand the fresh pin JSON to the Android config out-of-band",
        flush=True,
    )
    import uvicorn

    uvicorn.run(app, host=host, port=args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
