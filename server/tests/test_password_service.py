"""Tests for the Argon2id password service."""

import base64
import inspect
import re

import pytest
from argon2 import PasswordHasher
from argon2.low_level import Type

from postmark.auth.passwords import PasswordService, PasswordVerificationResult

PASSWORD = "correct horse battery staple"
_PHC_RE = re.compile(
    r"^\$argon2id\$v=19\$m=65536,t=3,p=4\$([A-Za-z0-9+/]+)\$([A-Za-z0-9+/]+)$"
)


def _decode_b64(segment: str) -> bytes:
    padded = segment + "=" * (-len(segment) % 4)
    return base64.b64decode(padded, validate=True)


def test_phc_prefix_and_parameters() -> None:
    service = PasswordService()
    phc = service.hash_password(PASSWORD)
    match = _PHC_RE.fullmatch(phc)
    assert match is not None, phc


def test_phc_salt_and_digest_lengths() -> None:
    service = PasswordService()
    phc = service.hash_password(PASSWORD)
    match = _PHC_RE.fullmatch(phc)
    assert match is not None
    salt = _decode_b64(match.group(1))
    digest = _decode_b64(match.group(2))
    assert len(salt) == 16
    assert len(digest) == 32


def test_same_password_hashes_differently_and_both_verify() -> None:
    service = PasswordService()
    first = service.hash_password(PASSWORD)
    second = service.hash_password(PASSWORD)
    assert first != second
    assert service.verify_password(first, PASSWORD) == PasswordVerificationResult(True, False)
    assert service.verify_password(second, PASSWORD) == PasswordVerificationResult(True, False)


def test_correct_current_password_true_false() -> None:
    service = PasswordService()
    phc = service.hash_password(PASSWORD)
    result = service.verify_password(phc, PASSWORD)
    assert result.verified is True
    assert result.needs_rehash is False


def test_wrong_password_false_false_without_exception() -> None:
    service = PasswordService()
    phc = service.hash_password(PASSWORD)
    result = service.verify_password(phc, "wrong password")
    assert result == PasswordVerificationResult(False, False)


def test_empty_non_phc_and_truncated_hashes_false_false() -> None:
    service = PasswordService()
    for malformed in ("", "not-a-phc", "$argon2id$v=19$m=65536,t=3,p=4$", "garbage"):
        result = service.verify_password(malformed, PASSWORD)
        assert result == PasswordVerificationResult(False, False), malformed


def test_legacy_parameters_verify_true_true() -> None:
    service = PasswordService()
    legacy = PasswordHasher(
        time_cost=2,
        memory_cost=65536,
        parallelism=4,
        hash_len=32,
        salt_len=16,
        type=Type.ID,
    )
    phc = legacy.hash(PASSWORD)
    result = service.verify_password(phc, PASSWORD)
    assert result.verified is True
    assert result.needs_rehash is True


def test_result_is_frozen() -> None:
    result = PasswordVerificationResult(True, False)
    with pytest.raises(Exception):
        result.verified = False  # type: ignore[misc]
    with pytest.raises(Exception):
        result.needs_rehash = True  # type: ignore[misc]


def test_repr_omits_password_and_hash() -> None:
    service = PasswordService()
    phc = service.hash_password(PASSWORD)
    result = service.verify_password(phc, PASSWORD)
    rendered = repr(result)
    assert PASSWORD not in rendered
    assert phc not in rendered


def test_hasher_is_configured_argon2id() -> None:
    service = PasswordService()
    hasher = service._hasher
    assert hasher.time_cost == 3
    assert hasher.memory_cost == 65536
    assert hasher.parallelism == 4
    assert hasher.hash_len == 32
    assert hasher.salt_len == 16
    assert hasher.type is Type.ID


def test_verify_catches_only_documented_exceptions() -> None:
    source = inspect.getsource(PasswordService.verify_password)
    assert "except Exception" not in source
    assert "except BaseException" not in source