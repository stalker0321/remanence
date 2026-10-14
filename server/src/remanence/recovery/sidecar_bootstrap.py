"""Isolated recovery sidecar bootstrap (T190, writer-built, root-run).

A SEPARATE loopback process sharing the exact isolated DB lets the
setup APK exercise the existing ordinary PasskeyRecovery flow against
real recovery routes WITHOUT restarting the pending epoch (whose
memory keys and recovery-disabled config stay untouched) and without
any gateway/proxy: the APK only retargets its base URL temporarily,
then root restores it.

Fail-closed rules (exit code 2, nothing served):
- bind is FIXED to exact 127.0.0.1:18043 (no override flags);
  mode is dev/test only (prod/beta refused).
- database must be a unique ``remanence_tmp_*`` synthetic DB
  (live/t181_clone refused); schema must already be at alembic head
  (read-only check — this sidecar never migrates, seeds accounts, or
  touches schema).
- recovery ON with RP ``localhost`` and the single allowed browser
  origin ``http://localhost:18043``; ALL pending flags forced OFF and
  NO pending_ctx/signer/vault/HMAC/epoch keys are constructed here.
  A pending flag enabled via environment is refused, not ignored.
- blob storage is a caller-selected scratch dir (required, created if
  absent); no live config or default is changed.
- existing bearer verification stays DB-backed automatically (same
  DB); no auth override exists in this path.
- silent access logs; only coarse public startup/error status. The
  database URL is read from REMANENCE_DATABASE_URL (or --database-url)
  and never printed (host/name only); request bodies and secrets are
  never logged.

Run from ``server/`` (root only, pending epoch left running)::

    uv run python scripts/pending_recovery_sidecar.py \\
        --blob-root /tmp/remanence-recovery-blobs

with REMANENCE_DATABASE_URL set to the SHARED epoch DB
(``postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_<epoch>``).
Root prepares the epoch DB + ``alembic upgrade head`` first.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

SIDECAR_HOST = "127.0.0.1"
SIDECAR_PORT = 18043
RECOVERY_RP_ID = "localhost"
RECOVERY_ORIGINS = ("http://localhost:18043",)


class SidecarRefusal(Exception):
    """Fail-closed sidecar refusal (main() maps to exit code 2)."""


def fixed_bind() -> tuple[str, int]:
    """The ONLY bind this sidecar ever serves (no override surface)."""
    return SIDECAR_HOST, SIDECAR_PORT


def resolve_database_url(explicit: str | None) -> str:
    from remanence.pending.dev_bootstrap import BootstrapRefusal as _UpstreamRefusal
    from remanence.pending.dev_bootstrap import database_name_of

    raw = os.environ.get("REMANENCE_DATABASE_URL") or explicit
    if not raw:
        raise SidecarRefusal(
            "refusing without database URL: set REMANENCE_DATABASE_URL "
            "to the shared epoch DB (or pass --database-url)"
        )
    try:
        database_name_of(raw)
    except _UpstreamRefusal as exc:
        raise SidecarRefusal(str(exc)) from None
    return raw


def resolve_blob_root(explicit: str | None) -> Path:
    if not explicit:
        raise SidecarRefusal(
            "refusing without scratch blob root: pass --blob-root "
            "(a caller-selected scratch dir, never live storage)"
        )
    root = Path(explicit)
    root.mkdir(parents=True, exist_ok=True)
    return root


_PENDING_ENV_KEYS = (
    "REMANENCE_PENDING_PROTOCOL_ENABLED",
    "REMANENCE_PENDING_SENDS_ENABLED",
    "REMANENCE_PENDING_CLAIMS_ENABLED",
    "REMANENCE_PENDING_LOCATOR_ISSUANCE_ENABLED",
)


def _pending_env_enabled() -> str | None:
    """Explicit constructor args beat env in pydantic-settings, so a
    pending-ON environment must be refused HERE (checking the built
    Settings afterwards would see our forced False and miss it)."""
    for key in _PENDING_ENV_KEYS:
        value = os.environ.get(key)
        if value is not None and value.strip().lower() in ("1", "true", "yes", "on"):
            return key
    return None


def build_settings(*, database_url: str, blob_root: Path, mode: str):
    """Recovery-ON, pending-OFF settings. Raises SidecarRefusal if any
    pending flag is enabled via the environment."""
    from remanence.settings import AppMode, Settings

    if mode not in ("dev", "test"):
        raise SidecarRefusal(f"refusing mode {mode!r}: dev/test only")
    offender = _pending_env_enabled()
    if offender is not None:
        raise SidecarRefusal(
            f"refusing pending-enabled environment ({offender}): this sidecar "
            "serves recovery only (unset it to proceed)"
        )
    app_mode = AppMode.DEV if mode == "dev" else AppMode.TEST
    return Settings(
        mode=app_mode,
        database_url=database_url,
        blob_root=blob_root,
        recovery_enabled=True,
        recovery_rp_id=RECOVERY_RP_ID,
        recovery_origins=list(RECOVERY_ORIGINS),
        pending_protocol_enabled=False,
        pending_sends_enabled=False,
        pending_claims_enabled=False,
        pending_locator_issuance_enabled=False,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Isolated recovery sidecar (fixed loopback bind, recovery only)."
    )
    parser.add_argument("--database-url", default=None)
    parser.add_argument("--blob-root", default=None)
    parser.add_argument("--mode", choices=("dev", "test"), default="dev")
    return parser


def main(argv: list[str] | None = None) -> int:
    from remanence.pending.dev_bootstrap import BootstrapRefusal as _UpstreamRefusal
    from remanence.pending.dev_bootstrap import check_schema_head, redacted_url

    args = build_parser().parse_args(argv)
    try:
        host, port = fixed_bind()
        database_url = resolve_database_url(args.database_url)
        blob_root = resolve_blob_root(args.blob_root)
        settings = build_settings(
            database_url=database_url, blob_root=blob_root, mode=args.mode
        )
    except SidecarRefusal as exc:
        print(f"pending-recovery-sidecar: refused: {exc}", file=sys.stderr)
        return 2
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        # Fixed coarse code only: never the exception string (it can
        # carry connection/config input) and never the URL.
        print("pending-recovery-sidecar: failed: STARTUP_SETTINGS_FAILED", file=sys.stderr)
        return 2
    try:
        try:
            check_schema_head(database_url)
        except _UpstreamRefusal as exc:
            raise SidecarRefusal(str(exc)) from None
    except SidecarRefusal as exc:
        print(f"pending-recovery-sidecar: refused: {exc}", file=sys.stderr)
        return 2
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-recovery-sidecar: failed: STARTUP_SCHEMA_FAILED", file=sys.stderr)
        return 2
    try:
        from remanence.db.session import build_engine, build_session_factory
        from remanence.main import create_app

        engine = build_engine(settings)
        factory = build_session_factory(engine)
        # No pending_ctx is ever constructed here: pending flags are OFF,
        # so the pending router stays unmounted and no epoch keys exist in
        # this process.
        app = create_app(settings=settings, session_factory=factory)
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-recovery-sidecar: failed: STARTUP_APP_FAILED", file=sys.stderr)
        return 2
    print(
        "pending-recovery-sidecar: serving "
        f"{host}:{port} db={redacted_url(database_url)} "
        f"recovery_enabled=true rp_id={RECOVERY_RP_ID} "
        f"origins={','.join(RECOVERY_ORIGINS)} pending=off",
        flush=True,
    )
    try:
        import uvicorn

        uvicorn.run(app, host=host, port=port, access_log=False, log_level="warning")
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-recovery-sidecar: failed: STARTUP_SERVE_FAILED", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
