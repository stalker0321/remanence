"""BlobStore protocol and adapters."""

from remanence.storage.base import (
    BlobConflictError,
    BlobInfo,
    BlobIntegrityError,
    BlobNotFoundError,
    BlobStore,
    BlobStoreError,
    InvalidBlobKeyError,
)
from remanence.storage.local import LocalBlobPathResolver, LocalFileBlobStore
from remanence.storage.staging import (
    CiphertextStager,
    CiphertextStagingError,
    InvalidStagingExpectationError,
    StagedBlob,
    StagingHashMismatchError,
    StagingIOError,
    StagingSizeExceededError,
    StagingSizeTruncatedError,
)

__all__ = [
    "BlobConflictError",
    "BlobInfo",
    "BlobIntegrityError",
    "BlobNotFoundError",
    "BlobStore",
    "BlobStoreError",
    "InvalidBlobKeyError",
    "LocalBlobPathResolver",
    "LocalFileBlobStore",
    "CiphertextStager",
    "CiphertextStagingError",
    "InvalidStagingExpectationError",
    "StagedBlob",
    "StagingHashMismatchError",
    "StagingIOError",
    "StagingSizeExceededError",
    "StagingSizeTruncatedError",
]
