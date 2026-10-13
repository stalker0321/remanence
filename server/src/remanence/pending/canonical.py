"""Strict canonical protobuf parsing (T185). Byte-exactness, not just shape.

Comparing a re-serialized parse against a stripped copy is NOT enough:
protobuf preserves unknown fields and normalizes non-minimal encodings
through a parse/serialize round trip, so duplicates and non-minimal
bytes stay accepted. The only sound check at a trust boundary is
raw-bytes equality against a deterministic re-serialization, PLUS the
unknown-field strip comparison (defense in depth).
"""

from __future__ import annotations


class CanonicalError(ValueError):
    """Raised for non-canonical wire bytes."""


def parse_strict(message_cls, raw: bytes, *, max_bytes: int = 8192):
    """Parse and require raw == deterministic serialization with zero
    unknown fields. Rejects duplicate singular fields, non-minimal
    varints, non-canonical order effects, trailing data, and smuggled
    unknown fields."""
    if not raw or len(raw) > max_bytes:
        raise CanonicalError("empty or oversize message")
    try:
        message = message_cls.FromString(raw)
    except Exception as exc:
        raise CanonicalError("malformed message") from exc
    if message.SerializeToString(deterministic=True) != bytes(raw):
        raise CanonicalError("non-canonical encoding")
    probe = message_cls()
    probe.CopyFrom(message)
    probe.DiscardUnknownFields()
    if probe.SerializeToString(deterministic=True) != bytes(raw):
        raise CanonicalError("unknown fields present")
    return message
