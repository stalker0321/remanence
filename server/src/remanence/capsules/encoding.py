"""Strict encodings shared by capsule request and persistence boundaries."""

import base64
import binascii
import re


_BASE64URL_RE = re.compile(r"^[A-Za-z0-9_-]+$")


def decode_canonical_base64url(value: object, *, expected_length: int) -> bytes:
    """Decode one unpadded, canonical base64url value of an exact size."""

    if not isinstance(value, str) or not value or "=" in value:
        raise ValueError("invalid base64url")
    if _BASE64URL_RE.fullmatch(value) is None:
        raise ValueError("invalid base64url")
    try:
        padded = value + "=" * (-len(value) % 4)
        decoded = base64.b64decode(padded, altchars=b"-_", validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("invalid base64url") from None
    canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
    if len(decoded) != expected_length or canonical != value:
        raise ValueError("invalid base64url")
    return decoded
