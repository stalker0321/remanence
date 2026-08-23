"""Safe local blob path resolution and read/stat/delete adapter."""

from __future__ import annotations

import hashlib
import os
import re
import stat
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import BinaryIO

from postmark.storage.base import BlobInfo, BlobNotFoundError, BlobStoreError, InvalidBlobKeyError

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


_CHUNK_SIZE = 64 * 1024


class LocalFileBlobStore:
    def __init__(self, root: Path) -> None:
        root = Path(root)
        if root.exists() and not root.is_dir():
            raise BlobStoreError("blob root is not a directory")
        root.mkdir(parents=True, exist_ok=True)
        self._resolver = LocalBlobPathResolver(root)

    @contextmanager
    def open_reader(self, key: str) -> Iterator[BinaryIO]:
        path = self._resolver.resolve(key)
        flags = os.O_RDONLY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            fd = os.open(path, flags)
        except (FileNotFoundError, OSError):
            raise BlobNotFoundError(key) from None
        try:
            if not stat.S_ISREG(os.fstat(fd).st_mode):
                raise BlobNotFoundError(key)
            reader = os.fdopen(fd, "rb")
        except Exception:
            os.close(fd)
            raise
        try:
            yield reader
        finally:
            reader.close()

    def stat(self, key: str) -> BlobInfo:
        digest = hashlib.sha256()
        size = 0
        with self.open_reader(key) as reader:
            while True:
                chunk = reader.read(_CHUNK_SIZE)
                if not chunk:
                    break
                size += len(chunk)
                digest.update(chunk)
        return BlobInfo(key=key, size=size, sha256_hex=digest.hexdigest())

    def delete(self, key: str) -> None:
        path = self._resolver.resolve(key)
        try:
            mode = path.lstat().st_mode
        except FileNotFoundError:
            return
        if not stat.S_ISREG(mode):
            raise BlobNotFoundError(key)
        try:
            path.unlink()
        except FileNotFoundError:
            return
