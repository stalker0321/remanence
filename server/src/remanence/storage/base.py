"""Synchronous BlobStore contract. No storage implementation."""

from dataclasses import dataclass
from typing import BinaryIO, ContextManager, Protocol, runtime_checkable


@dataclass(frozen=True, slots=True)
class BlobInfo:
    key: str
    size: int
    sha256_hex: str


class BlobStoreError(Exception):
    """Base error for BlobStore operations."""


class InvalidBlobKeyError(BlobStoreError):
    """Raised when a blob key is invalid or unrepresentable."""


class BlobNotFoundError(BlobStoreError):
    """Raised when a blob key does not exist."""


class BlobConflictError(BlobStoreError):
    """Raised when a key already stores different content."""


class BlobIntegrityError(BlobStoreError):
    """Raised when streamed content does not match expected size or SHA-256."""


@runtime_checkable
class BlobStore(Protocol):
    def put(
        self,
        key: str,
        source: BinaryIO,
        *,
        expected_size: int,
        expected_sha256: str,
    ) -> BlobInfo:
        """Stream from the current source position, validate expected length and SHA-256, and publish atomically.

        Exact existing content is idempotent. Different content at the same key raises BlobConflictError.
        Invalid or unrepresentable keys raise InvalidBlobKeyError.
        """
        ...

    def open_reader(self, key: str) -> ContextManager[BinaryIO]:
        """Open a reader for a stored blob.

        Missing keys raise BlobNotFoundError. Invalid or unrepresentable keys raise InvalidBlobKeyError.
        """
        ...

    def stat(self, key: str) -> BlobInfo:
        """Return metadata for a stored blob.

        Missing keys raise BlobNotFoundError. Invalid or unrepresentable keys raise InvalidBlobKeyError.
        """
        ...

    def delete(self, key: str) -> None:
        """Delete a blob. Missing keys are a no-op.

        Invalid or unrepresentable keys raise InvalidBlobKeyError.
        """
        ...
