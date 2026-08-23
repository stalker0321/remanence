"""Opaque session token generation and hashing."""

import hashlib
import secrets

ACCESS_TOKEN_PREFIX = "pm_at_"
REFRESH_TOKEN_PREFIX = "pm_rt_"


def generate_access_token() -> str:
    return ACCESS_TOKEN_PREFIX + secrets.token_urlsafe(nbytes=32)


def generate_refresh_token() -> str:
    return REFRESH_TOKEN_PREFIX + secrets.token_urlsafe(nbytes=32)


def hash_opaque_token(token: str) -> bytes:
    return hashlib.sha256(token.encode("ascii")).digest()