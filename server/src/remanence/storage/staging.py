"""Bounded, temporary staging for one incoming ciphertext stream."""

from __future__ import annotations

import hashlib
import os
import re
import sys
import tempfile
from collections.abc import AsyncIterable, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import BinaryIO, ClassVar


_SHA256_HEX = re.compile(r"[0-9a-f]{64}")
_GENERIC_ERROR = "ciphertext staging failed"
_TEMP_PREFIX = ".remanence-staging-"


class CiphertextStagingError(Exception):
    """Base for redacted failures while staging ciphertext."""

    code: ClassVar[str] = "STAGING_FAILED"

    def __init__(self) -> None:
        super().__init__(_GENERIC_ERROR)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


class InvalidStagingExpectationError(CiphertextStagingError):
    """The caller supplied an invalid size, digest, or hard limit."""

    code = "INVALID_EXPECTATION"


class StagingSizeExceededError(CiphertextStagingError):
    """The stream exceeded an expected size or hard byte limit."""

    code = "SIZE_EXCEEDED"


class StagingSizeTruncatedError(CiphertextStagingError):
    """The stream ended before its expected size."""

    code = "SIZE_TRUNCATED"


class StagingHashMismatchError(CiphertextStagingError):
    """The complete stream did not match its expected SHA-256 digest."""

    code = "HASH_MISMATCH"


class StagingIOError(CiphertextStagingError):
    """The staging file or source stream could not be used."""

    code = "STAGING_IO"


class StagedBlob:
    """An explicitly owned, successfully staged ciphertext file."""

    __slots__ = ("_cleaned", "_path", "_sha256_hex", "_size")

    def __init__(self, path: Path, size: int, sha256_hex: str) -> None:
        self._path = path
        self._size = size
        self._sha256_hex = sha256_hex
        self._cleaned = False

    @property
    def size(self) -> int:
        return self._size

    @property
    def sha256_hex(self) -> str:
        return self._sha256_hex

    def __repr__(self) -> str:
        return "StagedBlob(<opaque>)"

    @contextmanager
    def open_reader(self) -> Iterator[BinaryIO]:
        if self._cleaned:
            raise StagingIOError from None
        try:
            reader = self._path.open("rb")
        except OSError:
            raise StagingIOError from None
        try:
            yield reader
        finally:
            try:
                reader.close()
            except OSError:
                raise StagingIOError from None

    def cleanup(self) -> None:
        if self._cleaned:
            return
        try:
            self._path.unlink()
        except FileNotFoundError:
            self._cleaned = True
        except OSError:
            raise StagingIOError from None
        else:
            self._cleaned = True

    def __enter__(self) -> StagedBlob:
        return self

    def __exit__(self, exc_type: object, exc_value: object, traceback: object) -> bool:
        self.cleanup()
        return False


class CiphertextStager:
    """Write one bounded async byte stream to an explicit private staging root."""

    def __init__(self, staging_root: Path) -> None:
        if not isinstance(staging_root, Path):
            raise TypeError("staging root must be a Path")
        self._staging_root = staging_root

    async def stage(
        self,
        chunks: AsyncIterable[bytes],
        *,
        expected_size: int,
        expected_sha256: str,
        max_bytes: int,
    ) -> StagedBlob:
        _validate_expectations(expected_size, expected_sha256, max_bytes)

        fd: int | None = None
        temp_path: Path | None = None
        success = False
        try:
            try:
                self._staging_root.mkdir(mode=0o700, parents=True, exist_ok=True)
                os.chmod(self._staging_root, 0o700)
                fd, temp_name = tempfile.mkstemp(prefix=_TEMP_PREFIX, dir=self._staging_root)
                temp_path = Path(temp_name)
                os.fchmod(fd, 0o600)
            except OSError:
                raise StagingIOError from None

            digest = hashlib.sha256()
            actual_size = 0
            try:
                async for chunk in chunks:
                    if not isinstance(chunk, bytes):
                        raise StagingIOError
                    # Request.stream emits a terminal empty chunk; it carries no data.
                    if not chunk:
                        continue

                    actual_size += len(chunk)
                    if actual_size > expected_size or actual_size > max_bytes:
                        raise StagingSizeExceededError
                    digest.update(chunk)
                    _write_all(fd, chunk)
            except CiphertextStagingError:
                raise
            except Exception:
                raise StagingIOError from None

            if actual_size != expected_size:
                raise StagingSizeTruncatedError
            digest_hex = digest.hexdigest()
            if digest_hex != expected_sha256:
                raise StagingHashMismatchError

            try:
                os.fsync(fd)
                os.close(fd)
                fd = None
            except OSError:
                raise StagingIOError from None

            success = True
            return StagedBlob(temp_path, actual_size, digest_hex)
        finally:
            cleanup_failed = False
            if fd is not None:
                try:
                    os.close(fd)
                except OSError:
                    cleanup_failed = True
            if not success and temp_path is not None:
                try:
                    temp_path.unlink()
                except FileNotFoundError:
                    pass
                except OSError:
                    cleanup_failed = True
            if cleanup_failed and sys.exc_info()[0] is None:
                raise StagingIOError from None


def _validate_expectations(expected_size: int, expected_sha256: str, max_bytes: int) -> None:
    if (
        type(expected_size) is not int
        or expected_size <= 0
        or type(max_bytes) is not int
        or max_bytes <= 0
        or expected_size > max_bytes
        or not isinstance(expected_sha256, str)
        or _SHA256_HEX.fullmatch(expected_sha256) is None
    ):
        raise InvalidStagingExpectationError from None


def _write_all(fd: int, chunk: bytes) -> None:
    view = memoryview(chunk)
    while view:
        try:
            written = os.write(fd, view)
        except OSError:
            raise StagingIOError from None
        if written <= 0:
            raise StagingIOError from None
        view = view[written:]
