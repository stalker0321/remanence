"""Safe local blob path resolution. No I/O besides symlink inspection."""

from __future__ import annotations

import re
from pathlib import Path

from postmark.storage.base import InvalidBlobKeyError

_MAX_KEY_LENGTH = 512
_MAX_SEGMENT_LENGTH = 128
_SEGMENT = re.compile(r"[a-z0-9][a-z0-9._-]*")
_FORBIDDEN_IN_KEY = frozenset({"\\", ":", "\0"})


class LocalBlobPathResolver:
    def __init__(self, root: Path) -> None:
        self._root = root.resolve(strict=False)

    def resolve(self, key: str) -> Path:
        segments = self._segments(key)
        current = self._root
        for segment in segments:
            candidate = current / segment
            if candidate.is_symlink():
                raise InvalidBlobKeyError(key)
            current = candidate
        if not current.is_relative_to(self._root) or current == self._root:
            raise InvalidBlobKeyError(key)
        return current

    def _segments(self, key: str) -> list[str]:
        if not isinstance(key, str):
            raise InvalidBlobKeyError(key)
        if not 1 <= len(key) <= _MAX_KEY_LENGTH:
            raise InvalidBlobKeyError(key)
        if key[0] == "/" or key[-1] == "/":
            raise InvalidBlobKeyError(key)
        if "//" in key:
            raise InvalidBlobKeyError(key)
        if any(ch in _FORBIDDEN_IN_KEY or ord(ch) < 32 or ord(ch) == 127 for ch in key):
            raise InvalidBlobKeyError(key)
        segments = key.split("/")
        for segment in segments:
            if (
                not 1 <= len(segment) <= _MAX_SEGMENT_LENGTH
                or segment in {".", ".."}
                or _SEGMENT.fullmatch(segment) is None
            ):
                raise InvalidBlobKeyError(key)
        return segments
