"""Opaque, recipient-tombstone-only cursor codec."""

from __future__ import annotations

import base64
import struct
import uuid
from dataclasses import dataclass
from typing import Final

from remanence.capsules.encoding import decode_canonical_base64url


TOMBSTONE_CURSOR_VERSION: Final = 2
TOMBSTONE_CURSOR_PAYLOAD_BYTES: Final = 25
TOMBSTONE_CURSOR_B64_LENGTH: Final = 34
_INT64_MAX: Final = (1 << 63) - 1


class TombstoneCursorCodecError(Exception):
    """Malformed, noncanonical, or out-of-range tombstone cursor."""


@dataclass(frozen=True, slots=True)
class TombstoneCursor:
    tombstone_sequence: int
    capsule_id: uuid.UUID

    def __repr__(self) -> str:
        return "TombstoneCursor(<redacted>)"


def encode_tombstone_cursor(*, tombstone_sequence: int, capsule_id: uuid.UUID) -> str:
    if (
        type(tombstone_sequence) is not int
        or not 0 < tombstone_sequence <= _INT64_MAX
        or not isinstance(capsule_id, uuid.UUID)
    ):
        raise TombstoneCursorCodecError
    payload = (
        bytes((TOMBSTONE_CURSOR_VERSION,))
        + struct.pack(">q", tombstone_sequence)
        + capsule_id.bytes
    )
    encoded = base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
    if len(payload) != TOMBSTONE_CURSOR_PAYLOAD_BYTES:
        raise TombstoneCursorCodecError
    if len(encoded) != TOMBSTONE_CURSOR_B64_LENGTH:
        raise TombstoneCursorCodecError
    if decode_canonical_base64url(encoded, expected_length=len(payload)) != payload:
        raise TombstoneCursorCodecError
    return encoded


def decode_tombstone_cursor(value: object) -> TombstoneCursor:
    try:
        payload = decode_canonical_base64url(
            value, expected_length=TOMBSTONE_CURSOR_PAYLOAD_BYTES
        )
    except (TypeError, ValueError):
        raise TombstoneCursorCodecError from None
    if len(payload) != TOMBSTONE_CURSOR_PAYLOAD_BYTES:
        raise TombstoneCursorCodecError
    if payload[0] != TOMBSTONE_CURSOR_VERSION:
        raise TombstoneCursorCodecError
    sequence = struct.unpack(">q", payload[1:9])[0]
    if not 0 < sequence <= _INT64_MAX:
        raise TombstoneCursorCodecError
    try:
        capsule_id = uuid.UUID(bytes=payload[9:25])
    except (TypeError, ValueError):
        raise TombstoneCursorCodecError from None
    encoded = encode_tombstone_cursor(
        tombstone_sequence=sequence,
        capsule_id=capsule_id,
    )
    if encoded != value:
        raise TombstoneCursorCodecError
    return TombstoneCursor(tombstone_sequence=sequence, capsule_id=capsule_id)
