"""Common 5-character public locator (T185 M1). No secrets, no I/O.

The locator identifies a gift; it is never a secret, key, or proof.
Crockford-compatible alphabet without I/L/O/U: 32^5 = 33,554,432 codes.
"""

from __future__ import annotations

import secrets

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
CODE_LENGTH = 5
MAX_ALLOCATION_ATTEMPTS = 32

# Confusable mappings accepted on input, never emitted.
_CONFUSABLE_MAP = {"O": "0", "I": "1", "L": "1"}

_ALPHABET_SET = frozenset(ALPHABET)


class LocatorError(ValueError):
    """Raised for malformed locator input."""


def normalize_code(raw: str) -> str:
    """Normalize user input to canonical 5-symbol code.

    ASCII uppercase, strip spaces/hyphens, map O->0 and I/L->1, reject
    anything else (including other Unicode, confusables, wrong length).
    Never fuzzy-matches or picks a nearby code.
    """
    if not isinstance(raw, str):
        raise LocatorError("locator must be a string")
    cleaned = "".join(ch for ch in raw if ch not in (" ", "-"))
    try:
        upper = cleaned.upper()
        upper.encode("ascii")
    except (UnicodeEncodeError, AttributeError) as exc:
        raise LocatorError("locator must be ASCII") from exc
    mapped = "".join(_CONFUSABLE_MAP.get(ch, ch) for ch in upper)
    if len(mapped) != CODE_LENGTH:
        raise LocatorError("locator must be exactly 5 symbols")
    for ch in mapped:
        if ch not in _ALPHABET_SET:
            raise LocatorError(f"invalid locator symbol: {ch!r}")
    return mapped


def is_canonical(code: str) -> bool:
    """True iff already in canonical emitted form (no confusables)."""
    return (
        isinstance(code, str)
        and len(code) == CODE_LENGTH
        and all(ch in _ALPHABET_SET for ch in code)
    )


def generate_candidate() -> str:
    """One uniform CSPRNG candidate (not a secret/OTP)."""
    return "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))


def allocate_unique(exists: object, attempts: int = MAX_ALLOCATION_ATTEMPTS) -> str:
    """Allocate a code not claimed by ``exists(canonical) -> bool``.

    Bounded internal collision retries; raises LocatorError with
    LOCATOR_ALLOCATION_BUSY semantics when exhausted. Never reuses an
    exposed code — callers must include tombstoned codes in ``exists``.
    """
    if attempts < 1 or attempts > MAX_ALLOCATION_ATTEMPTS:
        raise LocatorError("attempts out of bounds")
    for _ in range(attempts):
        candidate = generate_candidate()
        if not exists(candidate):
            return candidate
    raise LocatorError("LOCATOR_ALLOCATION_BUSY")
