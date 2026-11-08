"""Canonical handle normalization."""

import re

_ALLOWED = re.compile(r"[a-z0-9_.]{3,30}")
_ASCII_LOWER_OFFSET = ord("a") - ord("A")
INVALID = "invalid handle"

# remanence.to/<nick> opens Create for that nick, so a nick cannot be a page,
# a file or a word the product may need. Shorter app paths (/c, /me) cannot
# collide: a handle has at least three characters.
RESERVED = frozenset("""
    about account accounts admin administrator android api app apps artwork assets auth
    blob blobs blog boot.js capsule capsules contact create download email favicon.svg
    fonts gift gifts help history home icons inbox index.html ios legal login logout mail
    manifest.webmanifest music news null official open opencv photo photos postcard
    postcards press pricing privacy profile recovery register remanence review root scan
    security send settings shop signin signup staff static status store support sw.js
    system team terms undefined user users web www
""".split())


class HandleReserved(ValueError):
    """A well-formed handle nobody can claim; reported like a taken one."""


def ensure_claimable(handle_normalized: str) -> None:
    if handle_normalized in RESERVED:
        raise HandleReserved(INVALID)


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
