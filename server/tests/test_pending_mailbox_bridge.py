"""T195 mailbox-bridge units on invented fixtures only.

No DB, no sockets, no server launch, no actual sink reads: every test
uses fabricated non-secret data in tmp_path. Root runs all runtime
checks against the real epoch.
"""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest

from remanence.pending.mailbox_bridge import (
    BAD_REQUEST,
    BRIDGE_HOST,
    BRIDGE_PORT,
    NONLOCAL_REFUSED,
    OTP_UNAVAILABLE,
    BridgeRefusal,
    check_attempt_row,
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


def _otp_line(reference: str, code: str = "123456", age_minutes: int = 1) -> str:
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


def test_sink_path_validation(tmp_path: Path) -> None:
    good = _secure_sink(tmp_path, '{"a": 1}')
    assert validate_sink_path(good) == os.path.abspath(good)
    assert read_sink_bytes(good) == b'{"a": 1}\n'
    with pytest.raises(BridgeRefusal):
        validate_sink_path(str(tmp_path / "missing.jsonl"))
    with pytest.raises(BridgeRefusal):
        validate_sink_path("")
    link = tmp_path / "link.jsonl"
    try:
        os.symlink(good, link)
    except OSError:
        pytest.skip("symlinks unsupported here")
    with pytest.raises(BridgeRefusal):
        validate_sink_path(str(link))
    exposed_dir = tmp_path / "exposed"
    exposed_dir.mkdir(mode=0o770)
    os.chmod(exposed_dir, 0o770)
    exposed = exposed_dir / "otp.jsonl"
    exposed.write_bytes(b"{}\n")
    os.chmod(exposed, 0o600)
    with pytest.raises(BridgeRefusal):
        validate_sink_path(str(exposed))
    readable = tmp_path / "epoch" / "readable.jsonl"
    readable.write_bytes(b"{}\n")
    os.chmod(readable, 0o640)
    with pytest.raises(BridgeRefusal):
        validate_sink_path(str(readable))
    big = tmp_path / "epoch" / "big.jsonl"
    big.write_bytes(b"x" * (1024 * 1024 + 1))
    os.chmod(big, 0o600)
    with pytest.raises(BridgeRefusal):
        validate_sink_path(str(big))


def test_sink_parser_bounds() -> None:
    marker = '{"event": "epoch-start", "at": 123.0}'
    line = _otp_line("dev-email-x")
    assert parse_sink_records(f"{marker}\n{line}\n".encode()) == [
        {"event": "epoch-start", "at": 123.0},
        {"reference": "dev-email-x", "code": "123456", "locale": "en",
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
    assert select_code([{**base, "code": "654321"}], reference=ref, now=now) == "654321"
    assert select_code(
        [{**base, "code": "654321", "dispatched_at": zulu_ts},
         {**base, "code": "654321"}], reference=ref, now=now,
    ) == "654321"
    bad_records = [
        [],
        [{**base, "code": "654321", "reference": "dev-email-other"}],
        [{**base, "code": "654321"}, {**base, "code": "111111"}],
        [{**base, "code": "12345"}],
        [{**base, "code": "1234567"}],
        [{**base, "code": "12345a"}],
        [{**base, "code": "١٢٣٤٥٦"}],
        [{**base, "code": None}],
        [{**base, "code": "654321", "dispatched_at": (now + timedelta(minutes=30)).isoformat()}],
        [{**base, "code": "654321", "dispatched_at": (now - timedelta(hours=25)).isoformat()}],
        [{**base, "code": "654321", "dispatched_at": "yesterday"}],
        [{**base, "code": "654321", "dispatched_at": (now - timedelta(minutes=2)).replace(tzinfo=None).isoformat()}],
        [{**{k: v for k, v in base.items() if k != "dispatched_at"}, "code": "654321"}],
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
    sink = bytearray((_otp_line(reference, code="424242") + "\n").encode())
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
    assert (status, payload) == (200, {"code": "424242"})
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
    sentinel_code = "987654"
    delivery_id, owner_id = uuid4(), uuid4()
    now = _now()
    row = _row(delivery_id=delivery_id, account_user_id=owner_id)
    reference = f"dev-email-{row.id}"
    sink_text = (
        _otp_line(reference, code=sentinel_code) + "\n"
        + json.dumps({"reference": "dev-email-noise", "code": "000000",
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
