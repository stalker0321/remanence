"""Delivery-destination normalization v1 (T185 M1). No secrets, no I/O.

Email: pinned case-fold normalization, <=254 UTF-8 bytes for this NEW
pending path (existing auth's 320-char login boundary is untouched).
No Gmail dot removal, plus-stripping, or guessed provider aliases.
Phone: E.164 via explicit country selection; ambiguous local numbers
and extensions rejected. Country is never inferred from IP here —
callers supply an explicit default region or fail closed.
"""

from __future__ import annotations

import re

NORMALIZATION_VERSION = 1
MAX_DELIVERY_EMAIL_BYTES = 254

_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_E164_RE = re.compile(r"^\+[1-9][0-9]{7,14}$")


class NormalizationError(ValueError):
    """Raised for undeliverable / ambiguous destination input."""


def normalize_email_destination(raw: str) -> str:
    """Case-fold + strip; deterministic; no alias guessing."""
    if not isinstance(raw, str):
        raise NormalizationError("email must be a string")
    normalized = raw.strip().casefold()
    if not normalized or len(normalized.encode("utf-8")) > MAX_DELIVERY_EMAIL_BYTES:
        raise NormalizationError("email size out of bounds")
    if _EMAIL_RE.match(normalized) is None:
        raise NormalizationError("malformed email destination")
    local, _, domain = normalized.partition("@")
    if not local or not domain or ".." in normalized:
        raise NormalizationError("malformed email destination")
    return normalized


def normalize_phone_destination(raw: str) -> str:
    """Accept only explicit E.164; reject ambiguous local forms."""
    if not isinstance(raw, str):
        raise NormalizationError("phone must be a string")
    compact = "".join(raw.split())
    if _E164_RE.match(compact) is None:
        raise NormalizationError("phone must be explicit E.164 (+country...)")
    return compact
