"""Deterministic PostgreSQL advisory-lock keys for capsule operations."""

import hashlib
import uuid


_IDEMPOTENCY_LOCK_PREFIX = b"remanence/capsule-draft-lock/v1\x00idempotency\x00POST\x00/v1/capsules\x00"
_CAPSULE_LOCK_PREFIX = b"remanence/capsule-draft-lock/v1\x00capsule\x00"
_BLOB_PROMOTION_LOCK_PREFIX = b"remanence/capsule-blob-lock/v1\x00promotion\x00"


def _lock_key(payload: bytes) -> int:
    digest = hashlib.sha256(payload).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=True)


def idempotency_scope_lock_key(owner_user_id: uuid.UUID, idempotency_key: uuid.UUID) -> int:
    return _lock_key(_IDEMPOTENCY_LOCK_PREFIX + owner_user_id.bytes + idempotency_key.bytes)


def capsule_lock_key(capsule_id: uuid.UUID) -> int:
    return _lock_key(_CAPSULE_LOCK_PREFIX + capsule_id.bytes)


def blob_promotion_lock_key(blob_id: uuid.UUID) -> int:
    return _lock_key(_BLOB_PROMOTION_LOCK_PREFIX + blob_id.bytes)
