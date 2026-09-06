"""Focused tests for the isolated recipient tombstone cursor codec."""

import base64
import struct
from datetime import datetime, timezone
from uuid import UUID

import pytest

from remanence.capsules.incoming_cursor import encode_incoming_cursor
from remanence.capsules.tombstone_cursor import (
    TOMBSTONE_CURSOR_B64_LENGTH,
    TOMBSTONE_CURSOR_PAYLOAD_BYTES,
    TOMBSTONE_CURSOR_VERSION,
    TombstoneCursorCodecError,
    decode_tombstone_cursor,
    encode_tombstone_cursor,
)


_CAPSULE_ID = UUID("00010203-0405-0607-0809-0a0b0c0d0e0f")


def test_tombstone_cursor_round_trip_is_opaque_and_canonical() -> None:
    encoded = encode_tombstone_cursor(tombstone_sequence=42, capsule_id=_CAPSULE_ID)
    decoded = decode_tombstone_cursor(encoded)

    assert TOMBSTONE_CURSOR_VERSION == 2
    assert len(encoded) == TOMBSTONE_CURSOR_B64_LENGTH == 34
    assert decoded.tombstone_sequence == 42
    assert decoded.capsule_id == _CAPSULE_ID
    assert str(_CAPSULE_ID) not in encoded
    assert repr(decoded) == "TombstoneCursor(<redacted>)"


def test_tombstone_cursor_rejects_other_namespace_noncanonical_and_bad_ranges() -> None:
    incoming = encode_incoming_cursor(
        ready_at=datetime(2030, 1, 1, tzinfo=timezone.utc),
        capsule_id=_CAPSULE_ID,
    )
    payload = bytearray(base64.urlsafe_b64decode(incoming + "=="))
    assert len(payload) == TOMBSTONE_CURSOR_PAYLOAD_BYTES
    for value in (
        incoming,
        encode_tombstone_cursor(tombstone_sequence=1, capsule_id=_CAPSULE_ID) + "=",
        base64.urlsafe_b64encode(bytes((TOMBSTONE_CURSOR_VERSION,)) + struct.pack(">q", 0) + _CAPSULE_ID.bytes)
        .decode("ascii")
        .rstrip("="),
    ):
        with pytest.raises(TombstoneCursorCodecError):
            decode_tombstone_cursor(value)


@pytest.mark.parametrize("sequence", [0, -1, 1 << 63])
def test_tombstone_cursor_rejects_invalid_sequence(sequence: int) -> None:
    with pytest.raises(TombstoneCursorCodecError):
        encode_tombstone_cursor(tombstone_sequence=sequence, capsule_id=_CAPSULE_ID)


def test_tombstone_cursor_rejects_invalid_input_types() -> None:
    for value in (None, b"", 1, "not-a-cursor"):
        with pytest.raises(TombstoneCursorCodecError):
            decode_tombstone_cursor(value)
