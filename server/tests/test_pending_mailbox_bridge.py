"""T195 mailbox-bridge units on invented fixtures only.

No DB, no sockets, no server launch, no actual sink reads: every test
uses fabricated non-secret data in tmp_path. Root runs all runtime
checks against the real epoch.
"""

from __future__ import annotations

import json
import logging
import os
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest

import remanence.pending.mailbox_bridge as mb
from remanence.pending.mailbox_bridge import (
    BAD_REQUEST,
    BRIDGE_HOST,
    BRIDGE_PORT,
    NONLOCAL_REFUSED,
    OTP_UNAVAILABLE,
    REQUEST_TIMEOUT_SECONDS,
    BridgeRefusal,
    _BoundedServer,
    _drain_fd,
    check_attempt_row,
    deadline_for,
    fixed_bind,
    handle_otp_request,
    main,
    parse_attempt_id,
    parse_sink_records,
    read_sink_bytes,
    resolve_database_url,
    resolve_mode,
    resolve_uuid,
    select_code,
    validate_sink_path,
)

_EPOCH_DB = "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_epoch9"


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch):
    for key in list(os.environ):
        if key.upper().startswith("REMANENCE_"):
            monkeypatch.delenv(key, raising=False)


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _row(**overrides) -> SimpleNamespace:
    attempt_id = overrides.pop("id", uuid4())
    base = {
        "id": attempt_id,
        "delivery_id": uuid4(),
        "account_user_id": uuid4(),
        "state": "CHALLENGE_PENDING",
        "expires_at": _now() + timedelta(minutes=9),
        "provider_reference": f"dev-email-{attempt_id}",
        "address_kind": "email",
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def _otp_line(reference: str, code: str = "12345678", age_minutes: int = 1) -> str:
    ts = (_now() - timedelta(minutes=age_minutes)).isoformat()
    return json.dumps({"reference": reference, "code": code, "locale": "en",
                       "dispatched_at": ts})


def test_fixed_bind_is_exact_loopback() -> None:
    assert fixed_bind() == ("127.0.0.1", 18045)
    assert BRIDGE_HOST == "127.0.0.1" and BRIDGE_PORT == 18045


def test_mode_and_uuid_and_db_guards(monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(BridgeRefusal):
        resolve_mode("prod")
    with pytest.raises(BridgeRefusal):
        resolve_mode("beta")
    assert resolve_mode("dev") == "dev"
    with pytest.raises(BridgeRefusal):
        resolve_uuid("not-a-uuid", "delivery-id")
    good = uuid4()
    assert resolve_uuid(str(good), "delivery-id") == good
    with pytest.raises(BridgeRefusal):
        resolve_database_url(None)
    monkeypatch.setenv("REMANENCE_DATABASE_URL", _EPOCH_DB)
    assert resolve_database_url(None) == _EPOCH_DB
    monkeypatch.delenv("REMANENCE_DATABASE_URL", raising=False)
    assert resolve_database_url(_EPOCH_DB) == _EPOCH_DB
    for bad in (
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/t181_clone",
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence",
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/postgres",
        "sqlite:///tmp/x.db",
    ):
        with pytest.raises(BridgeRefusal):
            resolve_database_url(bad)


def test_parse_attempt_id() -> None:
    good = uuid4()
    assert parse_attempt_id(str(good)) == good
    for bad in (None, "", "xyz", 42, str(good) + "00"):
        with pytest.raises(BridgeRefusal) as caught:
            parse_attempt_id(bad)
        assert caught.value.code == BAD_REQUEST


def test_attempt_row_all_failures_share_one_code() -> None:
    delivery_id, owner_id = uuid4(), uuid4()
    now = _now()
    good = _row(delivery_id=delivery_id, account_user_id=owner_id)
    assert check_attempt_row(good, delivery_id=delivery_id, owner_id=owner_id, now=now) == good.id
    assert check_attempt_row(
        {"id": str(good.id), "delivery_id": str(delivery_id),
         "account_user_id": str(owner_id), "state": "CHALLENGE_PENDING",
         "expires_at": good.expires_at, "provider_reference": good.provider_reference,
         "address_kind": "email"},
        delivery_id=delivery_id, owner_id=owner_id, now=now,
    ) == good.id
    faults = [
        None,
        _row(delivery_id=uuid4(), account_user_id=owner_id),
        _row(delivery_id=delivery_id, account_user_id=uuid4()),
        _row(delivery_id=delivery_id, account_user_id=owner_id, state="VERIFIED"),
        _row(delivery_id=delivery_id, account_user_id=owner_id, state="EXPIRED"),
        _row(delivery_id=delivery_id, account_user_id=owner_id,
             expires_at=now - timedelta(seconds=1)),
        _row(delivery_id=delivery_id, account_user_id=owner_id,
             expires_at=now.replace(tzinfo=None)),
        _row(delivery_id=delivery_id, account_user_id=owner_id, address_kind="sms"),
        _row(delivery_id=delivery_id, account_user_id=owner_id,
             provider_reference="dev-email-wrong"),
        _row(delivery_id=delivery_id, account_user_id=owner_id, provider_reference=None),
        _row(delivery_id="not-a-uuid", account_user_id=owner_id),
        SimpleNamespace(),
    ]
    codes = set()
    for fault in faults:
        with pytest.raises(BridgeRefusal) as caught:
            check_attempt_row(fault, delivery_id=delivery_id, owner_id=owner_id, now=now)
        codes.add(caught.value.code)
    assert codes == {OTP_UNAVAILABLE}


def _secure_sink(tmp_path: Path, *lines: str) -> str:
    epoch_dir = tmp_path / "epoch"
    epoch_dir.mkdir(mode=0o700)
    os.chmod(epoch_dir, 0o700)
    sink = epoch_dir / "otp.jsonl"
    sink.write_bytes("\n".join(lines).encode() + b"\n")
    os.chmod(sink, 0o600)
    return str(sink)


def _epoch_shape(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    """Stand-in epoch root under tmp (never the real /tmp pattern):
    enforces the SAME shape mechanics against invented fixtures, while
    the production literal is pinned separately below."""
    root = tmp_path / "syntmp"
    epoch = root / "remanence-pending-epoch-test1"
    epoch.mkdir(mode=0o700, parents=True)
    os.chmod(epoch, 0o700)
    os.chmod(root, 0o700)
    monkeypatch.setattr(
        mb, "_SINK_SHAPE",
        re.compile("^" + re.escape(str(root))
                   + r"/remanence-pending-epoch-[A-Za-z0-9_][A-Za-z0-9_.\-]*/otp\.jsonl$"),
    )
    return root, epoch


def _write_sink(epoch: Path, *lines: str) -> str:
    sink = epoch / "otp.jsonl"
    sink.write_bytes("\n".join(lines).encode() + b"\n")
    os.chmod(sink, 0o600)
    return str(sink)


def test_sink_shape_literal_pins_original_epoch() -> None:
    assert mb._SINK_SHAPE.match("/tmp/remanence-pending-epoch-abc123/otp.jsonl")
    assert mb._SINK_SHAPE.match("/tmp/remanence-pending-epoch-2026.10-14_1/otp.jsonl")
    for bad in (
        "/tmp/not-an-epoch/private.jsonl",
        "/tmp/remanence-pending-epoch-a/../evil/otp.jsonl",
        "/tmp/remanence-pending-epoch-a/b/otp.jsonl",
        "/tmp/remanence-pending-epoch-/otp.jsonl",
        "/tmp/remanence-pending-epoch-a/other.jsonl",
        "relative/remanence-pending-epoch-a/otp.jsonl",
        "/tmp/remanence-pending-epoch-a/otp.jsonl ",
        "/TMP/remanence-pending-epoch-a/otp.jsonl",
    ):
        assert not mb._SINK_SHAPE.match(bad), bad


def test_sink_path_validation(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    root, epoch = _epoch_shape(tmp_path, monkeypatch)
    good = _write_sink(epoch, '{"a": 1}')
    assert validate_sink_path(good) == os.path.abspath(good)
    assert read_sink_bytes(good) == b'{"a": 1}\n'
    # A secure owned file OUTSIDE the epoch shape is refused (the
    # original probe): shape first, ownership second.
    outside = tmp_path / "not-an-epoch.jsonl"
    outside.write_bytes(b'{"a": 1}\n')
    os.chmod(outside, 0o600)
    with pytest.raises(BridgeRefusal) as caught:
        validate_sink_path(str(outside))
    assert caught.value.code == OTP_UNAVAILABLE
    # Startup explain names the check with config identifiers only.
    with pytest.raises(BridgeRefusal) as caught:
        validate_sink_path(str(outside), explain=True)
    assert caught.value.code == "STARTUP"
    assert "remanence-pending-epoch" in str(caught.value)
    with pytest.raises(BridgeRefusal):
        validate_sink_path(str(root / "remanence-pending-epoch-test1" / "missing.jsonl"))
    with pytest.raises(BridgeRefusal):
        validate_sink_path("")
    link = epoch / "link.jsonl"
    try:
        os.symlink(good, link)
    except OSError:
        pytest.skip("symlinks unsupported here")
    with pytest.raises(BridgeRefusal):
        validate_sink_path(str(link))
    # Symlink anywhere in the chain (parent swap) is refused.
    real_dir = tmp_path / "realdir"
    real_dir.mkdir(mode=0o700)
    os.chmod(real_dir, 0o700)
    alias = root / "remanence-pending-epoch-alias"
    try:
        os.symlink(real_dir, alias)
    except OSError:
        pytest.skip("symlinks unsupported here")
    aliased = alias / "otp.jsonl"
    aliased.write_bytes(b"{}\n")
    os.chmod(aliased, 0o600)
    with pytest.raises(BridgeRefusal):
        validate_sink_path(str(aliased))
    _tail_sink_cases(root)
def _case_sink(root: Path, name: str, data: bytes, dmode: int, fmode: int) -> str:
    """One epoch-shaped sink per guard case (shape matches, so the
    exercised check is the ownership/mode/size one, not the shape)."""
    epoch = root / f"remanence-pending-epoch-{name}"
    epoch.mkdir(mode=0o700)
    os.chmod(epoch, 0o700)
    if dmode != 0o700:
        os.chmod(epoch, dmode)
    sink = epoch / "otp.jsonl"
    sink.write_bytes(data)
    os.chmod(sink, fmode)
    return str(sink)


def _tail_sink_cases(root: Path) -> None:
    with pytest.raises(BridgeRefusal):
        validate_sink_path(_case_sink(root, "exposed", b"{}\n", 0o770, 0o600))
    with pytest.raises(BridgeRefusal):
        validate_sink_path(_case_sink(root, "readable", b"{}\n", 0o700, 0o640))
    with pytest.raises(BridgeRefusal):
        validate_sink_path(_case_sink(root, "big", b"x" * (1024 * 1024 + 1), 0o700, 0o600))
    assert validate_sink_path(_case_sink(root, "ok2", b"{}\n", 0o700, 0o600))


def test_sink_parser_bounds() -> None:
    marker = '{"event": "epoch-start", "at": 123.0}'
    line = _otp_line("dev-email-x")
    assert parse_sink_records(f"{marker}\n{line}\n".encode()) == [
        {"event": "epoch-start", "at": 123.0},
        {"reference": "dev-email-x", "code": "12345678", "locale": "en",
         "dispatched_at": json.loads(line)["dispatched_at"]},
    ]
    for bad in (
        b'{"a": 1}\n' + b"x" * 1025 + b"\n",
        b'{"a": 1}\nnot json\n',
        b'{"a": 1}\n[1, 2]\n',
        b'{"a": 1}\n\n{"b": 2}\n',
        b"\xff\xfe invalid\n",
    ):
        with pytest.raises(BridgeRefusal) as caught:
            parse_sink_records(bad)
        assert caught.value.code == OTP_UNAVAILABLE


def test_select_code_matrix() -> None:
    now = _now()
    ref = "dev-email-attempt-9"
    good_ts = (now - timedelta(minutes=2)).isoformat()
    zulu_ts = (now - timedelta(minutes=2)).isoformat().replace("+00:00", "Z")
    base = {"reference": ref, "locale": "en", "dispatched_at": good_ts}
    assert select_code([{**base, "code": "65432109"}], reference=ref, now=now) == "65432109"
    assert select_code(
        [{**base, "code": "65432109", "dispatched_at": zulu_ts},
         {**base, "code": "65432109"}], reference=ref, now=now,
    ) == "65432109"
    bad_records = [
        [],
        [{**base, "code": "65432109", "reference": "dev-email-other"}],
        [{**base, "code": "65432109"}, {**base, "code": "11111111"}],
        [{**base, "code": "12345"}],
        [{**base, "code": "123456"}],
        [{**base, "code": "1234567"}],
        [{**base, "code": "123456789"}],
        [{**base, "code": "12345a"}],
        [{**base, "code": "١٢٣٤٥٦"}],
        [{**base, "code": None}],
        [{**base, "code": "65432109", "dispatched_at": (now + timedelta(minutes=30)).isoformat()}],
        [{**base, "code": "65432109", "dispatched_at": (now - timedelta(hours=25)).isoformat()}],
        [{**base, "code": "65432109", "dispatched_at": "yesterday"}],
        [{**base, "code": "65432109", "dispatched_at": (now - timedelta(minutes=2)).replace(tzinfo=None).isoformat()}],
        [{**{k: v for k, v in base.items() if k != "dispatched_at"}, "code": "65432109"}],
    ]
    codes = set()
    for records in bad_records:
        with pytest.raises(BridgeRefusal) as caught:
            select_code(records, reference=ref, now=now)
        codes.add(caught.value.code)
    assert codes == {OTP_UNAVAILABLE}


def _allowed_setup():
    delivery_id, owner_id = uuid4(), uuid4()
    now = _now()
    row = _row(delivery_id=delivery_id, account_user_id=owner_id)
    reference = f"dev-email-{row.id}"
    body = json.dumps({"attemptId": str(row.id)}).encode()
    sink = bytearray((_otp_line(reference, code="42424242") + "\n").encode())
    calls = {"lookups": [], "reads": 0}

    def lookup_attempt(attempt_id):
        calls["lookups"].append(attempt_id)
        return row

    def read_sink():
        calls["reads"] += 1
        return sink

    return delivery_id, owner_id, now, body, calls, lookup_attempt, read_sink


def test_allowed_path_returns_invented_code_only() -> None:
    delivery_id, owner_id, now, body, calls, lookup_attempt, read_sink = _allowed_setup()
    status, payload = handle_otp_request(
        body, "application/json", "127.0.0.1",
        lookup_attempt=lookup_attempt, read_sink=read_sink,
        delivery_id=delivery_id, owner_id=owner_id, now=now,
    )
    assert (status, payload) == (200, {"code": "42424242"})
    assert len(calls["lookups"]) == 1 and calls["reads"] == 1
    # No verification semantics: the injected fakes observed exactly one
    # metadata read and one sink read — nothing else exists to call.


def test_request_refusals_share_fixed_codes() -> None:
    delivery_id, owner_id, now, body, calls, lookup_attempt, read_sink = _allowed_setup()
    with pytest.raises(BridgeRefusal) as caught:
        handle_otp_request(
            body, "application/json", "192.168.1.2",
            lookup_attempt=lookup_attempt, read_sink=read_sink,
            delivery_id=delivery_id, owner_id=owner_id, now=now,
        )
    assert caught.value.code == NONLOCAL_REFUSED
    assert calls == {"lookups": [], "reads": 0}
    with pytest.raises(BridgeRefusal) as caught:
        handle_otp_request(
            body, "text/plain", "127.0.0.1",
            lookup_attempt=lookup_attempt, read_sink=read_sink,
            delivery_id=delivery_id, owner_id=owner_id, now=now,
        )
    assert caught.value.code == BAD_REQUEST
    status, payload = handle_otp_request(
        b"x" * 1025, "application/json", "127.0.0.1",
        lookup_attempt=lookup_attempt, read_sink=read_sink,
        delivery_id=delivery_id, owner_id=owner_id, now=now,
    )
    assert (status, payload) == (None, None)
    for bad_body in (b"{oops", b"[]", b"{}", b'{"attemptId": 1}',
                     b'{"attemptId": "x", "extra": 1}', b'{"attemptId": "not-a-uuid"}'):
        with pytest.raises(BridgeRefusal) as caught:
            handle_otp_request(
                bad_body, "application/json; charset=utf-8", "127.0.0.1",
                lookup_attempt=lookup_attempt, read_sink=read_sink,
                delivery_id=delivery_id, owner_id=owner_id, now=now,
            )
        assert caught.value.code == BAD_REQUEST
    # Wrong owner/delivery and unknown attempts share the lookup code.
    for other_delivery, other_owner in ((uuid4(), owner_id), (delivery_id, uuid4())):
        with pytest.raises(BridgeRefusal) as caught:
            handle_otp_request(
                body, "application/json", "127.0.0.1",
                lookup_attempt=lookup_attempt, read_sink=read_sink,
                delivery_id=other_delivery, owner_id=other_owner, now=now,
            )
        assert caught.value.code == OTP_UNAVAILABLE
    with pytest.raises(BridgeRefusal) as caught:
        handle_otp_request(
            body, "application/json", "127.0.0.1",
            lookup_attempt=lambda attempt_id: None, read_sink=read_sink,
            delivery_id=delivery_id, owner_id=owner_id, now=now,
        )
    assert caught.value.code == OTP_UNAVAILABLE


def test_logs_never_carry_request_secrets(tmp_path: Path, capsys, caplog) -> None:
    sentinel_code = "98765432"
    delivery_id, owner_id = uuid4(), uuid4()
    now = _now()
    row = _row(delivery_id=delivery_id, account_user_id=owner_id)
    reference = f"dev-email-{row.id}"
    sink_text = (
        _otp_line(reference, code=sentinel_code) + "\n"
        + json.dumps({"reference": "dev-email-noise", "code": "00000000",
                      "locale": f"SENTINEL-{uuid4().hex}", "dispatched_at": now.isoformat()}) + "\n"
    )
    sink = bytearray(sink_text.encode())
    with caplog.at_level(logging.DEBUG):
        status, payload = handle_otp_request(
            json.dumps({"attemptId": str(row.id)}).encode(), "application/json", "127.0.0.1",
            lookup_attempt=lambda attempt_id: row, read_sink=lambda: sink,
            delivery_id=delivery_id, owner_id=owner_id, now=now,
        )
    assert (status, payload) == (200, {"code": sentinel_code})
    combined = capsys.readouterr().out + capsys.readouterr().err
    for record in caplog.records:
        combined += record.getMessage()
    assert sentinel_code not in combined
    assert "SENTINEL-" not in combined


def test_main_refuses_without_database_url(tmp_path: Path, capsys) -> None:
    code = main([
        "--delivery-id", str(uuid4()),
        "--owner-id", str(uuid4()),
        "--sink-path", str(tmp_path / "otp.jsonl"),
    ])
    assert code == 2
    assert "database" in capsys.readouterr().err.lower()


class _TrackingBytearray(bytearray):
    """Observes wiping: any zero fill marks the buffer wiped."""

    def __init__(self) -> None:
        super().__init__()
        self.wiped = False

    def __setitem__(self, key, value) -> None:
        if value == 0 or (isinstance(value, bytes) and value and set(value) == {0}):
            self.wiped = True
        super().__setitem__(key, value)


def test_drain_fd_wipes_owned_buffer_on_failure() -> None:
    """The owned buffer is wiped in place on EVERY failure path
    (mid-stream OSError, over-bound growth) and transferred intact
    ONLY on success — proved with invented data through the seam."""
    from remanence.pending.mailbox_bridge import MAX_FILE_BYTES

    made: list[_TrackingBytearray] = []

    def factory() -> _TrackingBytearray:
        buf = _TrackingBytearray()
        made.append(buf)
        return buf

    calls = {"n": 0}

    def flaky_read(fd: int, size: int) -> bytes:
        calls["n"] += 1
        if calls["n"] == 1:
            return b"fakebytes-partial-"
        raise OSError("fake read failure")

    with pytest.raises(BridgeRefusal) as caught:
        _drain_fd(9, read=flaky_read, _make_buf=factory)
    assert caught.value.code == OTP_UNAVAILABLE
    assert len(made) == 1 and made[0].wiped
    assert bytes(made[0]) == b"\x00" * len(made[0])
    assert len(made[0]) == len(b"fakebytes-partial-")


def test_drain_fd_overflow_wipes_and_refuses() -> None:
    from remanence.pending.mailbox_bridge import MAX_FILE_BYTES

    made: list[_TrackingBytearray] = []

    def factory() -> _TrackingBytearray:
        buf = _TrackingBytearray()
        made.append(buf)
        return buf

    def gushing_read(fd: int, size: int) -> bytes:
        return b"y" * 65536

    with pytest.raises(BridgeRefusal) as caught:
        _drain_fd(9, read=gushing_read, _make_buf=factory)
    assert caught.value.code == OTP_UNAVAILABLE
    assert len(made) == 1 and made[0].wiped
    assert set(bytes(made[0])) == {0}
    assert len(made[0]) > MAX_FILE_BYTES


def test_drain_fd_success_transfers_intact() -> None:
    payloads = [b"fake-one-", b"fake-two"]

    def steady_read(fd: int, size: int) -> bytes:
        return payloads.pop(0) if payloads else b""

    buf = _drain_fd(9, read=steady_read)
    assert bytes(buf) == b"fake-one-fake-two"
    assert type(buf) is bytearray


def test_injected_failures_map_coarse_without_strings() -> None:
    """lookup_attempt/read_sink blowing up with a sentinel-carrying
    exception surfaces ONLY the fixed coarse code at the handler
    boundary — the sentinel never escapes."""
    delivery_id, owner_id = uuid4(), uuid4()
    now = _now()
    row = _row(delivery_id=delivery_id, account_user_id=owner_id)
    body = json.dumps({"attemptId": str(row.id)}).encode()
    sentinel = f"SENTINEL-{uuid4().hex}"

    def boom_lookup(attempt_id):
        raise RuntimeError(f"fake lookup failure carrying {sentinel}")

    def boom_read():
        raise RuntimeError(f"fake read failure carrying {sentinel}")

    def good_sink():
        return bytearray(
            _otp_line(f"dev-email-{row.id}").encode() + b"\n"
        )

    for lookup, read in ((boom_lookup, good_sink), (lambda attempt_id: row, boom_read)):
        with pytest.raises(BridgeRefusal) as caught:
            handle_otp_request(
                body, "application/json", "127.0.0.1",
                lookup_attempt=lookup, read_sink=read,
                delivery_id=delivery_id, owner_id=owner_id, now=now,
            )
        assert caught.value.code == OTP_UNAVAILABLE
        assert sentinel not in str(caught.value)


def test_server_error_boundary_is_silent(capsys) -> None:
    """The actual server-error boundary (handle_error override) emits
    nothing: no raw tracebacks, even for disconnect-shaped errors."""
    assert _BoundedServer.handle_error(object(), None, None) is None
    captured = capsys.readouterr()
    assert captured.out == "" and captured.err == ""


def test_deadline_for_matrix() -> None:
    assert deadline_for(100.0, bound=10, now=95.0) == 15.0
    assert deadline_for(100.0, bound=10, now=109.9) > 0
    assert deadline_for(100.0, bound=10, now=110.0) == 0.0
    assert deadline_for(100.0, bound=10, now=999.0) == 0.0
    assert REQUEST_TIMEOUT_SECONDS == 10


def test_main_explains_sink_shape_without_db(
    tmp_path: Path, capsys, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Startup names the sink-shape check (config identifiers only)
    without touching any DB: the schema check is stubbed, the sink is
    a secure file at a non-epoch path."""
    import remanence.pending.dev_bootstrap as _upstream

    monkeypatch.setattr(_upstream, "check_schema_head", lambda url: None)
    outside = tmp_path / "secure-but-elsewhere.jsonl"
    outside.write_bytes(b"{}\n")
    os.chmod(outside, 0o600)
    code = main([
        "--database-url",
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_explain",
        "--delivery-id", str(uuid4()),
        "--owner-id", str(uuid4()),
        "--sink-path", str(outside),
    ])
    assert code == 2
    err = capsys.readouterr().err
    assert "sink refused" in err
    assert "remanence-pending-epoch" in err


def _fake_stat(mode=0o100600, size=4):
    return os.stat_result((mode, 0, 0, 0, os.geteuid(), 0, size, 0, 0, 0))


def test_read_sink_bytes_fake_descriptor_lifecycle(monkeypatch: pytest.MonkeyPatch) -> None:
    """Fake os.open/fstat/read/close: success transfers the bytes AND
    closes; mid-stream OSError and descriptor refusal both raise
    coarse AND close. No actual files."""
    import remanence.pending.mailbox_bridge as _mb

    monkeypatch.setattr(_mb, "validate_sink_path", lambda path, explain=False: "/fake/otp.jsonl")
    opened, closed = [], []
    reads = {"n": 0}

    def fake_open(path, flags):
        opened.append((path, flags))
        return 99

    def fake_close(fd):
        closed.append(fd)

    monkeypatch.setattr(os, "open", fake_open)
    monkeypatch.setattr(os, "close", fake_close)
    monkeypatch.setattr(os, "fstat", lambda fd: _fake_stat())

    def steady_read(fd, size):
        reads["n"] += 1
        return b'{"a":1}\n' if reads["n"] == 1 else b""

    monkeypatch.setattr(os, "read", steady_read)
    assert read_sink_bytes("/fake/otp.jsonl") == bytearray(b'{"a":1}\n')
    assert opened == [("/fake/otp.jsonl", os.O_RDONLY | os.O_NOFOLLOW)]
    assert closed == [99]

    def flaky_read(fd, size):
        raise OSError("fake mid-stream failure")

    monkeypatch.setattr(os, "read", flaky_read)
    with pytest.raises(BridgeRefusal) as caught:
        read_sink_bytes("/fake/otp.jsonl")
    assert caught.value.code == OTP_UNAVAILABLE
    assert closed == [99, 99]

    monkeypatch.setattr(os, "read", steady_read)
    monkeypatch.setattr(os, "fstat", lambda fd: (_ for _ in ()).throw(OSError("fake fstat failure")))
    with pytest.raises(BridgeRefusal) as caught:
        read_sink_bytes("/fake/otp.jsonl")
    assert caught.value.code == OTP_UNAVAILABLE
    assert closed == [99, 99, 99]


def test_deadline_socket_bounds_multi_recv_trickle() -> None:
    """Genuine transport seam: ONE logical read needing several
    internal recvs, with the clock advancing DURING blocking reads.
    Each recv recomputes the remaining timeout (asserted decreasing)
    and checks post-read, so expiry mid-trickle raises and no further
    recv ever happens. Fake socket + fake clock, no real sockets."""
    import socket as _socket

    from remanence.pending.mailbox_bridge import _DeadlineSocket

    clock = {"now": 0.0}

    class _FakeSocket:
        def __init__(self) -> None:
            self.timeouts: list = []
            self.recvs = 0

        def settimeout(self, value) -> None:
            self.timeouts.append(value)

        def recv(self, bufsize: int, flags: int = 0) -> bytes:
            self.recvs += 1
            clock["now"] += 4.0
            return b"chunk"

    raw = _FakeSocket()
    sock = _DeadlineSocket(raw, 10.0, clock=lambda: clock["now"])
    with pytest.raises(_socket.timeout):
        for _ in range(100):
            sock.recv(5)
    # Pre-checks passed at t=0,4,8 (remaining 10,6,2); the third recv
    # pushed the clock to 12, so the post-read check fired: exactly
    # three recvs, then silence — never indefinite.
    assert raw.recvs == 3
    assert raw.timeouts == [10.0, 6.0, 2.0]


def test_deadline_socket_preexpired_makes_no_calls() -> None:
    import socket as _socket

    from remanence.pending.mailbox_bridge import _DeadlineSocket

    class _SilentSocket:
        def __init__(self) -> None:
            self.calls: list = []

        def settimeout(self, value) -> None:
            self.calls.append(("timeout", value))

        def recv(self, bufsize: int, flags: int = 0):
            self.calls.append(("recv", bufsize))
            return b"x"

    raw = _SilentSocket()
    sock = _DeadlineSocket(raw, 10.0, clock=lambda: 10.0)
    with pytest.raises(_socket.timeout):
        sock.recv(5)
    assert raw.calls == []
    with pytest.raises(_socket.timeout):
        sock.recv_into(bytearray(5))
    assert raw.calls == []


def test_deadline_buffered_readline_dies_mid_line() -> None:
    """A SINGLE buffered readline() needing several internal recvs
    (no newline until EOF) dies mid-line when the clock passes the
    deadline during a blocking read — the actual buffered-reader
    semantics the previous wrapper missed."""
    import io as _io
    import socket as _socket

    from remanence.pending.mailbox_bridge import _DeadlineRawStream

    clock = {"now": 0.0}
    chunks = [b"GET /o", b"tp HTTP", b"/1.1-no-newline"]

    class _FakeSocket:
        def __init__(self) -> None:
            self.recvs = 0

        def settimeout(self, value) -> None:
            return None

        def recv_into(self, buffer, nbytes: int = 0, flags: int = 0) -> int:
            self.recvs += 1
            clock["now"] += 4.0
            if not chunks:
                return 0
            data = chunks.pop(0)
            buffer[: len(data)] = data
            return len(data)

    raw = _io.BufferedReader(_DeadlineRawStream(_FakeSocket(), 10.0, clock=lambda: clock["now"]))
    with pytest.raises(_socket.timeout):
        raw.readline()
    # t=0->4 (6 bytes), t=4->8 (6 more), third recv t=8->12 expires:
    # exactly three internal recvs, then silence.
    assert clock["now"] == 12.0


def test_deadline_socket_makefile_and_send_bounds() -> None:
    import io as _io
    import socket as _socket

    from remanence.pending.mailbox_bridge import _DeadlineSocket

    clock = {"now": 0.0}

    class _FakeSocket:
        def __init__(self) -> None:
            self.sent: list = []

        def settimeout(self, value) -> None:
            return None

        def send(self, data, flags: int = 0) -> int:
            clock["now"] += 6.0
            self.sent.append(bytes(data))
            return len(bytes(data))

        def recv(self, bufsize: int, flags: int = 0) -> bytes:
            return b""

    sock = _DeadlineSocket(_FakeSocket(), 10.0, clock=lambda: clock["now"])
    assert isinstance(sock.makefile("rb"), _io.BufferedReader)
    with pytest.raises(ValueError):
        sock.makefile("r")
    # 200-byte sendall in one send: t=0->6, post-check passes.
    sock.sendall(b"z" * 200)
    assert clock["now"] == 6.0
    # Next send starts at t=6 with remaining 4, pushes past deadline.
    with pytest.raises(_socket.timeout):
        sock.send(b"z" * 10)


class _ScriptedSocket:
    """Fake request socket: scripted inbound chunks, advancing fake
    clock, captured sends. Raw makefile() raises: all buffering MUST
    flow through the deadline wrapper (proves the wiring)."""

    def __init__(self, chunks, clock, step=0.0) -> None:
        self._chunks = [bytes(chunk) for chunk in chunks]
        self._clock = clock
        self._step = step
        self.timeouts = []
        self.recvs = 0
        self.sent = bytearray()
        self.closed = False

    def settimeout(self, value) -> None:
        self.timeouts.append(value)

    def _tick(self) -> None:
        self._clock["now"] += self._step

    def recv(self, bufsize: int, flags: int = 0) -> bytes:
        self.recvs += 1
        self._tick()
        if not self._chunks:
            return b""
        return self._chunks.pop(0)[:bufsize]

    def recv_into(self, buffer, nbytes: int = 0, flags: int = 0) -> int:
        data = self.recv(len(buffer))
        buffer[: len(data)] = data
        return len(data)

    def send(self, data, flags: int = 0) -> int:
        blob = bytes(data)
        self.sent += blob
        return len(blob)

    def makefile(self, *args, **kwargs):
        raise AssertionError("raw makefile must never be used")

    def close(self) -> None:
        self.closed = True


def _lifecycle_handler():
    import remanence.pending.mailbox_bridge as _mb

    return _mb._wire_handler(
        engine=object(), sink_path="/tmp/x",
        delivery_id=uuid4(), owner_id=uuid4(),
    )


def _fake_server(clock, accept_at=100.0):
    return SimpleNamespace(_accept_at=accept_at, _clock=lambda: clock["now"])


def test_full_lifecycle_health_serves() -> None:
    """Real stdlib construction/parsing/response/finish with a fake
    request: GET /health serves 200 JSON through setup→handle→finish,
    raw makefile never touched, socket left for the server loop."""
    clock = {"now": 100.0}
    request = (
        b"GET /health HTTP/1.1\r\nHost: 127.0.0.1:18045\r\n"
        b"Connection: close\r\n\r\n"
    )
    sock = _ScriptedSocket([request], clock)
    handler_cls = _lifecycle_handler()
    handler_cls(sock, ("127.0.0.1", 9999), _fake_server(clock))
    assert b"200" in sock.sent
    assert b"LOCAL_SYNTHETIC_MAILBOX_CLIENT" in sock.sent
    assert b"no-store" in sock.sent
    assert sock.recvs >= 1
    assert sock.closed is False


def test_full_lifecycle_trickle_within_deadline_serves() -> None:
    clock = {"now": 100.0}
    sock = _ScriptedSocket(
        [b"GET /hea", b"lth HTTP/1.1\r\n", b"Host: x\r\n", b"\r\n"], clock, step=2.0
    )
    handler_cls = _lifecycle_handler()
    handler_cls(sock, ("127.0.0.1", 9999), _fake_server(clock))
    assert b"200" in sock.sent
    assert sock.recvs == 4


def test_full_lifecycle_trickle_past_deadline_is_silent() -> None:
    """Past-deadline trickle dies mid-headers: the header block never
    completes, the stdlib itself discards the timed-out connection
    silently (its socket.timeout catch plus our no-op loggers), and
    ZERO response bytes go out — nothing parsed, nothing routed.
    Recvs stop at the deadline (3 scripted reads, never a 4th)."""
    clock = {"now": 100.0}
    sock = _ScriptedSocket(
        [b"GET /health HTTP/1.1\r\n", b"Host: x\r\n", b"X-Pad: 1\r\n",
         b"X-Pad: 2\r\n", b"X-Pad: 3\r\n"],
        clock, step=4.0,
    )
    handler_cls = _lifecycle_handler()
    handler_cls(sock, ("127.0.0.1", 9999), _fake_server(clock))
    assert bytes(sock.sent) == b""
    assert sock.recvs == 3


def test_full_lifecycle_missing_stamp_fails_closed() -> None:
    import socket as _socket

    clock = {"now": 100.0}
    sock = _ScriptedSocket([b"GET /health HTTP/1.1\r\n\r\n"], clock)
    handler_cls = _lifecycle_handler()
    with pytest.raises(_socket.timeout):
        handler_cls(sock, ("127.0.0.1", 9999), SimpleNamespace())
    assert bytes(sock.sent) == b""


def test_serve_otp_post_expiry_gates() -> None:
    from remanence.pending.mailbox_bridge import serve_otp_post

    delivery_id, owner_id = uuid4(), uuid4()
    now = datetime.now(timezone.utc)
    row = SimpleNamespace(
        id=uuid4(), delivery_id=delivery_id, account_user_id=owner_id,
        state="CHALLENGE_PENDING", expires_at=now + timedelta(minutes=9),
        provider_reference="", address_kind="email",
    )
    row.provider_reference = f"dev-email-{row.id}"
    body = json.dumps({"attemptId": str(row.id)}).encode()
    clock = {"now": 0.0}
    calls = {"lookups": 0, "reads": 0}

    def lookup(attempt_id):
        calls["lookups"] += 1
        return row

    wiped = []

    class _Buf(bytearray):
        def __setitem__(self, key, value):
            if value == 0:
                wiped.append(True)
            super().__setitem__(key, value)

    def read_sink():
        calls["reads"] += 1
        buf = _Buf()
        buf += (
            json.dumps({"reference": f"dev-email-{row.id}", "code": "42424242",
                        "locale": "en",
                        "dispatched_at": (now - timedelta(minutes=1)).isoformat()}).encode()
            + b"\n"
        )
        return buf

    base = dict(
        body=body, content_type="application/json", peer="127.0.0.1",
        lookup_attempt=lookup, read_sink=read_sink,
        delivery_id=delivery_id, owner_id=owner_id, now=now,
        deadline=100.0, clock=lambda: clock["now"],
    )
    # Healthy: sends the code.
    assert serve_otp_post(**base) == ("send", 200, {"code": "42424242"})
    assert calls == {"lookups": 1, "reads": 1}
    # Expired before work: silent close, work never starts.
    clock["now"] = 100.0
    assert serve_otp_post(**base)[0] == "close"
    assert calls == {"lookups": 1, "reads": 1}
    # Slow lookup pushes past the deadline: silent close, nothing sent.
    clock["now"] = 0.0

    def slow_lookup(attempt_id):
        calls["lookups"] += 1
        clock["now"] = 200.0
        return row

    assert serve_otp_post(**{**base, "lookup_attempt": slow_lookup})[0] == "close"
    # Slow sink read pushes past the deadline: silent close, no code
    # emitted, and the returned buffer is wiped.
    clock["now"] = 0.0

    def slow_sink():
        calls["reads"] += 1
        clock["now"] = 200.0
        return read_sink()

    assert serve_otp_post(**{**base, "read_sink": slow_sink}) == ("close", None, None)
    assert wiped, "late-abort buffer must be wiped"
    # Oversize and nonlocal stay as before.
    assert serve_otp_post(**{**base, "body": b"x" * 1025}) == ("close", None, None)
    assert serve_otp_post(**{**base, "peer": "10.0.0.1"})[0] == "send"


def test_engine_kwargs_bounded_without_connecting() -> None:
    from sqlalchemy import create_engine
    from sqlalchemy.pool import NullPool

    from remanence.pending.mailbox_bridge import engine_kwargs

    kwargs = engine_kwargs()
    assert kwargs["poolclass"] is NullPool
    assert kwargs["connect_args"]["connect_timeout"] == 5
    assert "statement_timeout" in kwargs["connect_args"]["options"]
    engine = create_engine("postgresql+psycopg://u:p@127.0.0.1:1/db", **kwargs)
    try:
        assert isinstance(engine.pool, NullPool)
    finally:
        engine.dispose()


def test_real_file_sink_record_accepted_at_production_length(tmp_path: Path) -> None:
    """Regression through the ACTUAL FileSinkEmailSender(mode="test"):
    its genuinely emitted record — invented contact, invented code of
    exactly the production OTP_DIGITS length — is accepted by the
    helper parser/selector. Six/seven/nine-digit codes from the same
    sender shape are refused (no truncation/padding/union)."""
    from remanence.pending.proof import OTP_DIGITS
    from remanence.pending.providers import FileSinkEmailSender

    assert OTP_DIGITS == 8
    sink_path = str(tmp_path / "invented-otp.jsonl")
    sender = FileSinkEmailSender(path=sink_path, mode="test")
    attempt_id = uuid4()
    code = "24681357"
    assert len(code) == OTP_DIGITS
    reference = sender.send(
        attempt_id=str(attempt_id),
        stored_destination="invented-contact@example.test",
        code=code,
        locale="en",
    )
    assert reference == f"dev-email-{attempt_id}"
    records = parse_sink_records(Path(sink_path).read_bytes())
    assert (
        select_code(records, reference=reference, now=datetime.now(timezone.utc))
        == code
    )
    for wrong in ("246813", "2468135", "246813579", "2468135a"):
        other = dict(records[0])
        other["code"] = wrong
        with pytest.raises(BridgeRefusal) as caught:
            select_code([other], reference=reference, now=datetime.now(timezone.utc))
        assert caught.value.code == OTP_UNAVAILABLE
