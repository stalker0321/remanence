"""Root-only synthetic OTP mailbox client bridge (T195, test utility).

A separately started loopback process lets the Android TEST client
receive a LOCAL synthetic email code through the normal VM input
event; REAL R9/R11 on the epoch server verify it. This bridge never
verifies anything itself and is not a product mailbox API.

Fail-closed rules (exit code 2 at startup, fixed coarse codes at
runtime, nothing request-specific ever logged):
- bind is FIXED to exact 127.0.0.1:18045 (no override surface);
  mode is dev/test only; single-threaded (requests serialize).
- database must be a unique ``remanence_tmp_*`` synthetic DB
  (live/t181_clone refused); schema must already be at alembic head
  (read-only check — never migrates/seeds). ONE allowed delivery UUID
  and ONE allowed account-owner UUID are configured per start.
- per request: attemptId must be UUID; DB pre-check SELECTs ONLY the
  non-secret attempt columns and requires allowed delivery+owner,
  CHALLENGE_PENDING, unexpired, email kind, and provider reference
  exactly ``dev-email-<attemptId>``. No MAC/digest/session/token/
  sealed-destination lookup, no DB mutation.
- the original epoch sink is read ONLY after passing ownership/mode/
  symlink/size checks, bounded, with every line valid JSON; the exact
  reference must yield one consistent six-digit code with a bounded
  timestamp. Unknown/conflicting/malformed/expired records fail with
  the SAME single coarse code as an unknown attempt (no oracle), and
  no line is ever dumped.
- response is ONLY ``{"code": "<six digits>"}`` with no-store, over
  loopback (non-loopback peers refused). No real email/SMS, no
  credential/client reset, no epoch restart, no live config.

Run from ``server/`` (root only, pending epoch left running)::

    uv run python scripts/pending_mailbox_bridge.py \\
        --delivery-id <allowed-delivery-uuid> \\
        --owner-id <allowed-account-owner-uuid> \\
        --sink-path /tmp/remanence-pending-epoch-<epoch>/otp.jsonl

with REMANENCE_DATABASE_URL set to the shared epoch DB.
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import stat
import sys
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

from sqlalchemy import create_engine

BRIDGE_HOST = "127.0.0.1"
BRIDGE_PORT = 18045
MAX_BODY_BYTES = 1024
MAX_FILE_BYTES = 1024 * 1024
MAX_LINE_BYTES = 1024
SINK_REF_PREFIX = "dev-email-"
REQUEST_TIMEOUT_SECONDS = 10
TS_FUTURE_SKEW = timedelta(minutes=5)
TS_MAX_AGE = timedelta(hours=24)

# The ONLY readable sink shape: the original epoch sink. The epoch
# name is root-chosen (single path component); anything else —
# including an otherwise-secure owned file elsewhere — is refused so
# a misconfigured or overridden path can never substitute records.
# The native caller cannot override the sink (no path travels the wire).
_SINK_SHAPE = re.compile(r"^/tmp/remanence-pending-epoch-[A-Za-z0-9_][A-Za-z0-9_.\-]*/otp\.jsonl$")

# Fixed coarse wire codes. OTP_UNAVAILABLE is deliberately the single
# code for every lookup/read failure (unknown attempt, wrong owner or
# delivery, bad state/TTL/kind/reference, missing/conflicting/
# malformed/expired sink record) so callers learn nothing beyond
# "no code for you".
OTP_UNAVAILABLE = "OTP_UNAVAILABLE"
BAD_REQUEST = "BAD_REQUEST"
NOT_FOUND = "NOT_FOUND"
NONLOCAL_REFUSED = "NONLOCAL_REFUSED"


class BridgeRefusal(Exception):
    """Coarse guard failure. Carries a fixed wire code only when raised
    on the request path; startup refusals carry a human message (config
    identifiers only, never request data)."""

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code


def fixed_bind() -> tuple[str, int]:
    """The ONLY bind this bridge ever serves (no override surface)."""
    return BRIDGE_HOST, BRIDGE_PORT


def is_loopback_peer(address: object) -> bool:
    return address in ("127.0.0.1", "::1", "::ffff:127.0.0.1")


def _field(row: object, name: str) -> object:
    if hasattr(row, name):
        return getattr(row, name)
    if isinstance(row, dict):
        return row.get(name)
    return None


def parse_attempt_id(raw: object) -> uuid.UUID:
    try:
        return uuid.UUID(str(raw))
    except (ValueError, AttributeError, TypeError):
        raise BridgeRefusal(BAD_REQUEST) from None


def resolve_uuid(value: object, name: str) -> uuid.UUID:
    try:
        return uuid.UUID(str(value))
    except (ValueError, AttributeError, TypeError):
        raise BridgeRefusal("STARTUP", f"invalid {name}: must be UUID") from None


def check_attempt_row(
    row: object,
    *,
    delivery_id: uuid.UUID,
    owner_id: uuid.UUID,
    now: datetime,
) -> uuid.UUID:
    """Gate a DB attempt row (only the 7 whitelisted columns may be
    present on it). Every failure maps to the single OTP_UNAVAILABLE
    code — no existence oracle."""
    if row is None:
        raise BridgeRefusal(OTP_UNAVAILABLE)
    try:
        row_id = uuid.UUID(str(_field(row, "id")))
    except (ValueError, AttributeError, TypeError):
        raise BridgeRefusal(OTP_UNAVAILABLE) from None
    ok = True
    try:
        ok &= hmac.compare_digest(str(_field(row, "delivery_id")), str(delivery_id))
        ok &= hmac.compare_digest(str(_field(row, "account_user_id")), str(owner_id))
    except TypeError:
        ok = False
    if not ok:
        raise BridgeRefusal(OTP_UNAVAILABLE)
    if _field(row, "state") != "CHALLENGE_PENDING":
        raise BridgeRefusal(OTP_UNAVAILABLE)
    expires_at = _field(row, "expires_at")
    if (
        not isinstance(expires_at, datetime)
        or expires_at.tzinfo is None
        or expires_at <= now
    ):
        raise BridgeRefusal(OTP_UNAVAILABLE)
    if _field(row, "address_kind") != "email":
        raise BridgeRefusal(OTP_UNAVAILABLE)
    reference = _field(row, "provider_reference")
    if not isinstance(reference, str) or not hmac.compare_digest(
        reference, f"{SINK_REF_PREFIX}{row_id}"
    ):
        raise BridgeRefusal(OTP_UNAVAILABLE)
    return row_id


def validate_sink_path(path: str, *, explain: bool = False) -> str:
    """Validate the ORIGINAL epoch sink without modifying it.

    Shape first: the absolute path must match the original
    ``/tmp/remanence-pending-epoch-*/otp.jsonl`` form (single epoch
    component, fixed filename) — any other path is refused even if
    securely owned. Then: every component resolves identically
    (``realpath`` equality — no symlink anywhere in the chain); the
    parent dir exists, is a real dir, is owned by uid 0-or-runner,
    and has no group/other write; the file exists, is a regular file
    (symlinks refused here and again with ``O_NOFOLLOW`` at open),
    same ownership, 0600-class modes, bounded size.

    ``explain=False`` (request path) maps everything to the single
    OTP_UNAVAILABLE code. ``explain=True`` (startup only) names the
    failing check with config identifiers only — never file content.
    """
    def fail(reason: str) -> BridgeRefusal:
        if explain:
            raise BridgeRefusal("STARTUP", f"sink refused: {reason}")
        raise BridgeRefusal(OTP_UNAVAILABLE)

    if not path or not isinstance(path, str):
        raise fail("no --sink-path configured")
    resolved = os.path.abspath(path)
    if not _SINK_SHAPE.match(resolved):
        raise fail("path must be /tmp/remanence-pending-epoch-<epoch>/otp.jsonl")
    try:
        if os.path.realpath(resolved) != resolved:
            raise fail("symlink escape in sink path refused")
    except BridgeRefusal:
        raise
    except OSError:
        raise fail("sink path unresolvable") from None
    resolved_parent = os.path.dirname(resolved)
    try:
        parent_stat = os.lstat(resolved_parent)
    except OSError:
        raise fail("sink parent unreadable") from None
    if (
        not stat.S_ISDIR(parent_stat.st_mode)
        or stat.S_ISLNK(parent_stat.st_mode)
        or parent_stat.st_uid not in (0, os.geteuid())
        or parent_stat.st_mode & 0o022
    ):
        raise fail("sink parent ownership/modes insecure")
    try:
        file_stat = os.lstat(resolved)
    except OSError:
        raise fail("sink file unreadable") from None
    if (
        not stat.S_ISREG(file_stat.st_mode)
        or stat.S_ISLNK(file_stat.st_mode)
        or file_stat.st_uid not in (0, os.geteuid())
        or file_stat.st_mode & 0o077
        or file_stat.st_size > MAX_FILE_BYTES
    ):
        raise fail("sink file ownership/modes/size insecure")
    return resolved


def _check_opened_sink(fd: int) -> None:
    """Re-check guards against the ACTUAL opened descriptor (not only
    the pre-open lstat): regular file, same ownership, 0600-class
    modes. Closes the TOCTOU window between lstat and open for
    metadata (content stays bounded by the read loop + parse)."""
    try:
        opened = os.fstat(fd)
    except OSError:
        raise BridgeRefusal(OTP_UNAVAILABLE) from None
    if (
        not stat.S_ISREG(opened.st_mode)
        or opened.st_uid not in (0, os.geteuid())
        or opened.st_mode & 0o077
    ):
        raise BridgeRefusal(OTP_UNAVAILABLE)


def _drain_fd(fd: int, *, read=os.read, _make_buf=bytearray) -> bytearray:
    """Read the open descriptor into an OWNED mutable buffer, bounded.
    Ownership transfers to the caller ONLY on successful return; on
    ANY failure (OSError, over-bound growth, interruption) the owned
    buffer is wiped in place before the refusal propagates, so a
    partial prefix can never linger for a later read to pick up.
    (Immutable bytes/str copies made downstream stay honestly
    unwipeable — documented, not claimed.) `_make_buf` is a test seam
    (default plain bytearray)."""
    chunks = _make_buf()
    try:
        while len(chunks) <= MAX_FILE_BYTES:
            piece = read(fd, 65536)
            if not piece:
                break
            chunks += piece
        if len(chunks) > MAX_FILE_BYTES:
            raise BridgeRefusal(OTP_UNAVAILABLE)
        return chunks
    except BridgeRefusal:
        for index in range(len(chunks)):
            chunks[index] = 0
        raise
    except (KeyboardInterrupt, SystemExit):
        for index in range(len(chunks)):
            chunks[index] = 0
        raise
    except BaseException:
        for index in range(len(chunks)):
            chunks[index] = 0
        raise BridgeRefusal(OTP_UNAVAILABLE) from None


def read_sink_bytes(path: str) -> bytearray:
    """Read the validated file into a MUTABLE buffer (caller clears
    best-effort after use). O_NOFOLLOW refuses symlink escape at open
    time; descriptor metadata is re-checked after open."""
    validated = validate_sink_path(path)
    try:
        fd = os.open(validated, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError:
        raise BridgeRefusal(OTP_UNAVAILABLE) from None
    try:
        _check_opened_sink(fd)
        return _drain_fd(fd)
    except BaseException:
        try:
            os.close(fd)
        except OSError:
            pass
        raise
    else:
        try:
            os.close(fd)
        except OSError:
            pass


def parse_sink_records(data: bytes) -> list[dict]:
    """Parse sink JSONL: every non-trailing line must be <= 1 KiB and
    valid JSON object, else coarse failure (never dump any line)."""
    if not isinstance(data, (bytes, bytearray)):
        raise BridgeRefusal(OTP_UNAVAILABLE)
    try:
        text = bytes(data).decode("utf-8", errors="strict")
    except (ValueError, UnicodeDecodeError):
        raise BridgeRefusal(OTP_UNAVAILABLE) from None
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    records: list[dict] = []
    for line in lines:
        if len(line.encode("utf-8")) > MAX_LINE_BYTES:
            raise BridgeRefusal(OTP_UNAVAILABLE)
        try:
            record = json.loads(line)
        except (ValueError, UnicodeDecodeError):
            raise BridgeRefusal(OTP_UNAVAILABLE) from None
        if not isinstance(record, dict):
            raise BridgeRefusal(OTP_UNAVAILABLE)
        records.append(record)
    return records


def _parse_ts(raw: object) -> datetime | None:
    if not isinstance(raw, str):
        return None
    try:
        moment = datetime.fromisoformat(raw)
    except ValueError:
        return None
    if moment.tzinfo is None:
        return None
    return moment


def select_code(records: list[dict], *, reference: str, now: datetime) -> str:
    """Find the exact reference; require one consistent six-digit code
    and a bounded sane timestamp on every matching record. Unknown,
    conflicting, malformed, or expired records all fail identically."""
    matches = [record for record in records if record.get("reference") == reference]
    if not matches:
        raise BridgeRefusal(OTP_UNAVAILABLE)
    codes = set()
    for record in matches:
        code = record.get("code")
        if not isinstance(code, str) or len(code) != 6 or not code.isascii() or not code.isdigit():
            raise BridgeRefusal(OTP_UNAVAILABLE)
        moment = _parse_ts(record.get("dispatched_at"))
        if moment is None or moment - now > TS_FUTURE_SKEW or now - moment > TS_MAX_AGE:
            raise BridgeRefusal(OTP_UNAVAILABLE)
        codes.add(code)
    if len(codes) != 1:
        raise BridgeRefusal(OTP_UNAVAILABLE)
    return codes.pop()


def handle_otp_request(
    body: bytes,
    content_type: str,
    peer: object,
    *,
    lookup_attempt,
    read_sink,
    delivery_id: uuid.UUID,
    owner_id: uuid.UUID,
    now: datetime,
) -> tuple[int | None, dict | None]:
    """Route logic with injected I/O (unit-testable, no sockets/DB).
    Returns (status, payload); status None means close the connection
    with no response (oversize). Raises BridgeRefusal carrying the
    fixed wire code otherwise."""
    if not is_loopback_peer(peer):
        raise BridgeRefusal(NONLOCAL_REFUSED)
    if not isinstance(content_type, str) or not content_type.split(";")[0].strip().lower() == "application/json":
        raise BridgeRefusal(BAD_REQUEST)
    if not isinstance(body, (bytes, bytearray)) or len(body) > MAX_BODY_BYTES:
        return None, None
    try:
        parsed = json.loads(bytes(body))
    except ValueError:
        raise BridgeRefusal(BAD_REQUEST) from None
    if (
        not isinstance(parsed, dict)
        or set(parsed) != {"attemptId"}
        or not isinstance(parsed["attemptId"], str)
    ):
        raise BridgeRefusal(BAD_REQUEST)
    attempt_id = parse_attempt_id(parsed["attemptId"])
    try:
        row = lookup_attempt(attempt_id)
    except BridgeRefusal:
        raise
    except Exception:
        # Injected/production lookup failures (including disconnects)
        # map to the single coarse code — never exception strings.
        raise BridgeRefusal(OTP_UNAVAILABLE) from None
    row_id = check_attempt_row(
        row, delivery_id=delivery_id, owner_id=owner_id, now=now
    )
    try:
        raw = read_sink()
    except BridgeRefusal:
        raise
    except Exception:
        raise BridgeRefusal(OTP_UNAVAILABLE) from None
    try:
        code = select_code(
            parse_sink_records(raw),
            reference=f"{SINK_REF_PREFIX}{row_id}",
            now=now,
        )
    finally:
        try:
            for index in range(len(raw)):
                raw[index] = 0
        except (TypeError, IndexError):
            pass
    return 200, {"code": code}


def resolve_database_url(explicit: str | None) -> str:
    from remanence.pending.dev_bootstrap import BootstrapRefusal as _UpstreamRefusal
    from remanence.pending.dev_bootstrap import database_name_of

    raw = os.environ.get("REMANENCE_DATABASE_URL") or explicit
    if not raw:
        raise BridgeRefusal("STARTUP", "refusing without database URL")
    try:
        database_name_of(raw)
    except _UpstreamRefusal as exc:
        raise BridgeRefusal("STARTUP", str(exc)) from None
    return raw


def resolve_mode(explicit: str | None) -> str:
    if explicit not in ("dev", "test"):
        raise BridgeRefusal("STARTUP", f"refusing mode {explicit!r}: dev/test only")
    return explicit


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Synthetic OTP mailbox client bridge (fixed loopback bind)."
    )
    parser.add_argument("--database-url", default=None)
    parser.add_argument("--delivery-id", required=True)
    parser.add_argument("--owner-id", required=True)
    parser.add_argument("--sink-path", required=True)
    parser.add_argument("--mode", choices=("dev", "test"), default="dev")
    return parser


def fetch_attempt_row(engine, attempt_id: uuid.UUID):
    """Production DB read: the 7 whitelisted non-secret columns ONLY,
    by primary key. No MAC/digest/session/token/sealed destination,
    no mutation (SELECT + close)."""
    from sqlalchemy import select

    from remanence.pending.claim_models import AddressProofAttempt

    columns = (
        AddressProofAttempt.id,
        AddressProofAttempt.delivery_id,
        AddressProofAttempt.account_user_id,
        AddressProofAttempt.state,
        AddressProofAttempt.expires_at,
        AddressProofAttempt.provider_reference,
        AddressProofAttempt.address_kind,
    )
    with engine.connect() as connection:
        row = connection.execute(
            select(*columns).where(AddressProofAttempt.id == attempt_id)
        ).first()
    if row is None:
        return None
    return {
        "id": row[0],
        "delivery_id": row[1],
        "account_user_id": row[2],
        "state": row[3],
        "expires_at": row[4],
        "provider_reference": row[5],
        "address_kind": row[6],
    }


def deadline_for(accept_at: float, *, bound: int, now: float) -> float:
    """Remaining seconds of the absolute request lifetime measured from
    accept (monotonic clock). Pure seam: unit-tested without sockets.
    Callers close the connection when the remainder is exhausted."""
    return max(0.0, (accept_at + bound) - now)


class _BoundedServer(HTTPServer):
    """Single-threaded HTTP server with the absolute request lifetime
    enforced from the accepted connection: the accept instant is
    stamped (one connection at a time — no leak, no replay), the
    socket gets an idle timeout, and raw server tracebacks are
    suppressed (coarse silence instead of exception dumps)."""

    def get_request(self):
        import time as _time

        conn, addr = super().get_request()
        self._accept_at = _time.monotonic()
        try:
            conn.settimeout(REQUEST_TIMEOUT_SECONDS)
        except OSError:
            pass
        return conn, addr

    def handle_error(self, *args) -> None:
        return None


def _wire_handler(*, engine, sink_path: str, delivery_id: uuid.UUID, owner_id: uuid.UUID):
    import time as _time

    def lookup_attempt(attempt_id: uuid.UUID):
        return fetch_attempt_row(engine, attempt_id)

    def read_sink() -> bytearray:
        return read_sink_bytes(sink_path)

    class _Handler(BaseHTTPRequestHandler):
        server_version = "MailboxBridge/1"
        sys_version = ""

        def log_message(self, *args) -> None:
            return None

        def log_error(self, *args) -> None:
            return None

        def _peer(self) -> object:
            try:
                return self.client_address[0]
            except (IndexError, AttributeError):
                return None

        def _send_json(self, status: int, payload: dict) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def handle(self) -> None:
            # Absolute request lifetime from the accepted connection
            # (covers header parsing + body + handling): a stalled
            # connection dies here instead of wedging the
            # single-threaded server, and its I/O is cancelled with it.
            # No daemon threads, no replay, no leak.
            remaining = deadline_for(
                getattr(self.server, "_accept_at", _time.monotonic()),
                bound=REQUEST_TIMEOUT_SECONDS,
                now=_time.monotonic(),
            )
            if remaining <= 0:
                try:
                    self.connection.close()
                except OSError:
                    pass
                return
            try:
                self.connection.settimeout(remaining)
            except OSError:
                pass
            super().handle()

        def _guarded(self, work) -> None:
            try:
                work()
            except (BrokenPipeError, ConnectionResetError):
                # Response-disconnect: silent (nothing to answer to).
                return
            except Exception:
                # Any other request-work failure maps coarse; a second
                # send failing (disconnect race) stays silent too.
                try:
                    self._send_json(404, {"error": OTP_UNAVAILABLE})
                except (BrokenPipeError, ConnectionResetError, OSError, ValueError):
                    return

        def do_GET(self) -> None:
            def _get() -> None:
                if self.path != "/health":
                    self._send_json(404, {"error": NOT_FOUND})
                    return
                self._send_json(
                    200,
                    {"ready": True, "evidenceClass": "LOCAL_SYNTHETIC_MAILBOX_CLIENT"},
                )

            self._guarded(_get)

        def do_POST(self) -> None:
            def _post() -> None:
                if self.path != "/otp":
                    self._send_json(404, {"error": NOT_FOUND})
                    return
                try:
                    length_raw = self.headers.get("Content-Length", "")
                    length = int(length_raw)
                except (TypeError, ValueError):
                    self._send_json(400, {"error": BAD_REQUEST})
                    return
                if length < 0 or length > MAX_BODY_BYTES:
                    try:
                        self.connection.shutdown(2)
                    except OSError:
                        pass
                    try:
                        self.close_connection = True
                    except AttributeError:
                        pass
                    return
                try:
                    body = self.rfile.read(length) if length else b""
                except (OSError, ValueError):
                    self._send_json(400, {"error": BAD_REQUEST})
                    return
                try:
                    status, payload = handle_otp_request(
                        body,
                        self.headers.get("Content-Type", ""),
                        self._peer(),
                        lookup_attempt=lookup_attempt,
                        read_sink=read_sink,
                        delivery_id=delivery_id,
                        owner_id=owner_id,
                        now=datetime.now(timezone.utc),
                    )
                except BridgeRefusal as exc:
                    mapping = {OTP_UNAVAILABLE: 404, NONLOCAL_REFUSED: 403}
                    self._send_json(mapping.get(exc.code, 400), {"error": exc.code})
                    return
                if status is None or payload is None:
                    return
                self._send_json(status, payload)

            self._guarded(_post)

        def do_PUT(self) -> None:  # noqa: N802
            self._send_json(404, {"error": NOT_FOUND})

        def do_DELETE(self) -> None:  # noqa: N802
            self._send_json(404, {"error": NOT_FOUND})

        def do_PATCH(self) -> None:  # noqa: N802
            self._send_json(404, {"error": NOT_FOUND})

    return _Handler


def main(argv: list[str] | None = None) -> int:
    from remanence.pending.dev_bootstrap import BootstrapRefusal as _UpstreamRefusal
    from remanence.pending.dev_bootstrap import check_schema_head, redacted_url

    args = build_parser().parse_args(argv)
    try:
        host, port = fixed_bind()
        database_url = resolve_database_url(args.database_url)
        resolve_mode(args.mode)
        delivery_id = resolve_uuid(args.delivery_id, "delivery-id")
        owner_id = resolve_uuid(args.owner_id, "owner-id")
        sink_path = args.sink_path
        if not sink_path or not isinstance(sink_path, str):
            raise BridgeRefusal("STARTUP", "refusing without --sink-path")
    except BridgeRefusal as exc:
        print(f"pending-mailbox-bridge: refused: {exc}", file=sys.stderr)
        return 2
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-mailbox-bridge: failed: STARTUP_SETTINGS_FAILED", file=sys.stderr)
        return 2
    try:
        try:
            check_schema_head(database_url)
        except _UpstreamRefusal as exc:
            raise BridgeRefusal("STARTUP", str(exc)) from None
        validate_sink_path(sink_path, explain=True)
    except BridgeRefusal as exc:
        print(f"pending-mailbox-bridge: refused: {exc}", file=sys.stderr)
        return 2
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-mailbox-bridge: failed: STARTUP_SCHEMA_FAILED", file=sys.stderr)
        return 2
    try:
        engine = create_engine(database_url)
        handler = _wire_handler(
            engine=engine, sink_path=sink_path,
            delivery_id=delivery_id, owner_id=owner_id,
        )
        server = _BoundedServer((host, port), handler)
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-mailbox-bridge: failed: STARTUP_APP_FAILED", file=sys.stderr)
        return 2
    print(
        "pending-mailbox-bridge: serving "
        f"{host}:{port} db={redacted_url(database_url)} "
        f"delivery={delivery_id} owner={owner_id} sink={sink_path}",
        flush=True,
    )
    try:
        server.serve_forever(poll_interval=0.2)
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception:
        print("pending-mailbox-bridge: failed: STARTUP_SERVE_FAILED", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
