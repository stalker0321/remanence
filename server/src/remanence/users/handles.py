"""Canonical handle normalization."""

import re

_ALLOWED = re.compile(r"[a-z0-9_.]{3,30}")
_ASCII_LOWER_OFFSET = ord("a") - ord("A")
INVALID = "invalid handle"


def normalize_handle(raw: str) -> str:
    without_prefix = raw[1:] if raw.startswith("@") else raw
    folded = _ascii_fold(without_prefix)
    if _ALLOWED.fullmatch(folded) is None:
        raise ValueError(INVALID)
    return folded


def _ascii_fold(raw: str) -> str:
    chars = list(raw)
    changed = False
    for index, char in enumerate(chars):
        if "A" <= char <= "Z":
            chars[index] = chr(ord(char) + _ASCII_LOWER_OFFSET)
            changed = True
    return "".join(chars) if changed else raw
