"""Opaque incoming-page cursor codec. Not an auth credential."""

from __future__ import annotations

import base64
import struct
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Final

from remanence.capsules.encoding import decode_canonical_base64url


INCOMING_CURSOR_VERSION: Final = 1
INCOMING_CURSOR_PAYLOAD_BYTES: Final = 25
INCOMING_CURSOR_B64_LENGTH: Final = 34
_INT64_MIN: Final = -(1 << 63)
_INT64_MAX: Final = (1 << 63) - 1
_EPOCH: Final = datetime(1970, 1, 1, tzinfo=timezone.utc)
_MICROS_PER_DAY: Final = 86_400_000_000


class IncomingCursorCodecError(Exception):
    """Malformed, noncanonical, or out-of-range incoming cursor."""


@dataclass(frozen=True, slots=True)
class IncomingCursor:
    ready_at: datetime
    capsule_id: uuid.UUID

    def __repr__(self) -> str:
        return "IncomingCursor(<redacted>)"


def encode_incoming_cursor(*, ready_at: datetime, capsule_id: uuid.UUID) -> str:
    if not isinstance(capsule_id, uuid.UUID):
        raise IncomingCursorCodecError
    micros = _utc_to_micros(ready_at)
    payload = (
        bytes((INCOMING_CURSOR_VERSION,))
        + struct.pack(">q", micros)
        + capsule_id.bytes
    )
    if len(payload) != INCOMING_CURSOR_PAYLOAD_BYTES:
        raise IncomingCursorCodecError
    encoded = base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
    if len(encoded) != INCOMING_CURSOR_B64_LENGTH:
        raise IncomingCursorCodecError
    if decode_canonical_base64url(encoded, expected_length=INCOMING_CURSOR_PAYLOAD_BYTES) != payload:
        raise IncomingCursorCodecError
    return encoded


def decode_incoming_cursor(value: object) -> IncomingCursor:
    try:
        payload = decode_canonical_base64url(
            value, expected_length=INCOMING_CURSOR_PAYLOAD_BYTES
        )
    except (ValueError, TypeError):
        raise IncomingCursorCodecError from None
    if len(payload) != INCOMING_CURSOR_PAYLOAD_BYTES:
        raise IncomingCursorCodecError
    if payload[0] != INCOMING_CURSOR_VERSION:
        raise IncomingCursorCodecError
    micros = struct.unpack(">q", payload[1:9])[0]
    try:
        ready_at = _micros_to_utc(micros)
        capsule_id = uuid.UUID(bytes=payload[9:25])
    except (OverflowError, OSError, TypeError, ValueError):
        raise IncomingCursorCodecError from None
    encoded = encode_incoming_cursor(ready_at=ready_at, capsule_id=capsule_id)
    if encoded != value:
        raise IncomingCursorCodecError
    return IncomingCursor(ready_at=ready_at, capsule_id=capsule_id)


def _utc_to_micros(value: object) -> int:
    if (
        not isinstance(value, datetime)
        or value.tzinfo is None
        or value.utcoffset() != timedelta(0)
    ):
        raise IncomingCursorCodecError
    delta = value - _EPOCH
    micros = delta.days * _MICROS_PER_DAY + delta.seconds * 1_000_000 + delta.microseconds
    if type(micros) is not int or not _INT64_MIN <= micros <= _INT64_MAX:
        raise IncomingCursorCodecError
    return micros


def _micros_to_utc(micros: object) -> datetime:
    if type(micros) is not int or not _INT64_MIN <= micros <= _INT64_MAX:
        raise IncomingCursorCodecError
    try:
        value = _EPOCH + timedelta(microseconds=micros)
    except OverflowError:
        raise IncomingCursorCodecError from None
    if _utc_to_micros(value) != micros:
        raise IncomingCursorCodecError
    return value
