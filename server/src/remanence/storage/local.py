"""Safe local blob path resolution and read/stat/delete adapter."""

from __future__ import annotations

import hashlib
import os
import re
import stat
import tempfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import BinaryIO

from remanence.storage.base import (
    BlobConflictError,
    BlobInfo,
    BlobIntegrityError,
    BlobNotFoundError,
    BlobStoreError,
    InvalidBlobKeyError,
)

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
_SHA256_HEX = re.compile(r"[0-9a-f]{64}")
_TEMP_PREFIX = ".remanence-"
_GENERIC_INTEGRITY = "blob integrity check failed"
_GENERIC_EXPECTATION = "invalid blob expectation"
_GENERIC_CONFLICT = "blob already exists"
_GENERIC_READ = "blob read failed"


class LocalFileBlobStore:
    def __init__(self, root: Path) -> None:
        root = Path(root)
        if root.exists() and not root.is_dir():
            raise BlobStoreError("blob root is not a directory")
        root.mkdir(parents=True, exist_ok=True)
        self._resolver = LocalBlobPathResolver(root)

    def put(
        self,
        key: str,
        source: BinaryIO,
        *,
        expected_size: int,
        expected_sha256: str,
    ) -> BlobInfo:
        if type(expected_size) is not int or expected_size < 0:
            raise BlobIntegrityError(_GENERIC_EXPECTATION)
        if not isinstance(expected_sha256, str) or _SHA256_HEX.fullmatch(expected_sha256) is None:
            raise BlobIntegrityError(_GENERIC_EXPECTATION)

        path = self._resolver.resolve(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path = self._resolver.resolve(key)

        fd: int | None = None
        tmp_path: Path | None = None
        try:
            fd, tmp_name = tempfile.mkstemp(prefix=_TEMP_PREFIX, dir=path.parent)
            tmp_path = Path(tmp_name)
            os.fchmod(fd, 0o600)
            digest = hashlib.sha256()
            size = 0
            while True:
                chunk = source.read(_CHUNK_SIZE)
                if not chunk:
                    break
                size += len(chunk)
                digest.update(chunk)
                written = 0
                while written < len(chunk):
                    written += os.write(fd, chunk[written:])
            os.fsync(fd)
            os.close(fd)
            fd = None
            sha256_hex = digest.hexdigest()
            if size != expected_size or sha256_hex != expected_sha256:
                raise BlobIntegrityError(_GENERIC_INTEGRITY)
            streamed = BlobInfo(key=key, size=size, sha256_hex=sha256_hex)
            try:
                os.link(tmp_path, path)
            except FileExistsError:
                existing = self.stat(key)
                if existing != streamed:
                    raise BlobConflictError(_GENERIC_CONFLICT) from None
                return existing
            dir_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(dir_fd)
            finally:
                os.close(dir_fd)
            return streamed
        finally:
            if fd is not None:
                os.close(fd)
            if tmp_path is not None:
                try:
                    tmp_path.unlink()
                except FileNotFoundError:
                    pass

    @contextmanager
    def open_reader(self, key: str) -> Iterator[BinaryIO]:
        path = self._resolver.resolve(key)
        flags = os.O_RDONLY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            fd = os.open(path, flags)
        except (FileNotFoundError, NotADirectoryError, IsADirectoryError):
            raise BlobNotFoundError(key) from None
        except OSError:
            raise BlobStoreError(_GENERIC_READ) from None
        try:
            if not stat.S_ISREG(os.fstat(fd).st_mode):
                raise BlobNotFoundError(key)
            reader = os.fdopen(fd, "rb")
        except OSError:
            os.close(fd)
            raise BlobStoreError(_GENERIC_READ) from None
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
