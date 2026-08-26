"""Argon2id password hashing and verification."""

from dataclasses import dataclass

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from argon2.low_level import Type


@dataclass(frozen=True)
class PasswordVerificationResult:
    verified: bool
    needs_rehash: bool


class PasswordService:
    def __init__(self) -> None:
        self._hasher = PasswordHasher(
            time_cost=3,
            memory_cost=65536,
            parallelism=4,
            hash_len=32,
            salt_len=16,
            type=Type.ID,
        )

    def hash_password(self, password: str) -> str:
        return self._hasher.hash(password)

    def verify_password(self, encoded_hash: str, password: str) -> PasswordVerificationResult:
        try:
            self._hasher.verify(encoded_hash, password)
        except VerificationError:
            return PasswordVerificationResult(verified=False, needs_rehash=False)
        except InvalidHashError:
            return PasswordVerificationResult(verified=False, needs_rehash=False)
        try:
            needs_rehash = self._hasher.check_needs_rehash(encoded_hash)
        except InvalidHashError:
            needs_rehash = False
        return PasswordVerificationResult(verified=True, needs_rehash=needs_rehash)