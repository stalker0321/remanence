"""Tests for opaque session token primitives."""

import base64
import hashlib
import inspect
import re

import pytest

from remanence.auth.tokens import (
    ACCESS_TOKEN_PREFIX,
    REFRESH_TOKEN_PREFIX,
    generate_access_token,
    generate_refresh_token,
    hash_opaque_token,
)

_URLSAFE_RE = re.compile(r"^[A-Za-z0-9_-]+$")


def _payload(token: str, prefix: str) -> str:
    assert token.startswith(prefix)
    return token[len(prefix):]


def test_exact_prefixes() -> None:
    assert ACCESS_TOKEN_PREFIX == "pm_at_"
    assert REFRESH_TOKEN_PREFIX == "pm_rt_"
    assert generate_access_token().startswith(ACCESS_TOKEN_PREFIX)
    assert generate_refresh_token().startswith(REFRESH_TOKEN_PREFIX)


def test_payload_is_nonempty_unpadded_urlsafe_ascii_and_decodes_to_32_bytes() -> None:
    for token, prefix in (
        (generate_access_token(), ACCESS_TOKEN_PREFIX),
        (generate_refresh_token(), REFRESH_TOKEN_PREFIX),
    ):
        payload = _payload(token, prefix)
        assert payload != ""
        assert _URLSAFE_RE.fullmatch(payload) is not None
        payload.encode("ascii")
        assert len(payload) % 4 != 0, "payload must be unpadded base64url"
        padded = payload + "=" * (-len(payload) % 4)
        decoded = base64.urlsafe_b64decode(padded)
        assert len(decoded) == 32


def test_two_tokens_of_each_kind_differ() -> None:
    access_tokens = {generate_access_token() for _ in range(2)}
    refresh_tokens = {generate_refresh_token() for _ in range(2)}
    assert len(access_tokens) == 2
    assert len(refresh_tokens) == 2


def test_digest_is_32_bytes_and_equals_independent_sha256_of_full_token() -> None:
    for token in (generate_access_token(), generate_refresh_token()):
        digest = hash_opaque_token(token)
        assert len(digest) == 32
        assert digest == hashlib.sha256(token.encode("ascii")).digest()


def test_access_and_refresh_with_same_payload_give_different_digests() -> None:
    payload = "shared-payload"
    access = ACCESS_TOKEN_PREFIX + payload
    refresh = REFRESH_TOKEN_PREFIX + payload
    assert hash_opaque_token(access) != hash_opaque_token(refresh)


def test_non_ascii_hash_raises_unicode_encode_error() -> None:
    try:
        hash_opaque_token("pm_at_\u00e9")
    except UnicodeEncodeError:
        pass
    else:
        raise AssertionError("expected UnicodeEncodeError")


def test_source_avoids_random_uuid_logging_and_global_mutable_state() -> None:
    source = inspect.getsource(__import__("remanence.auth.tokens", fromlist=["*"]))
    for banned in ("import random", "from random", "uuid", "logging", "log("):
        assert banned not in source, banned
    assert "secrets.token_urlsafe(nbytes=32)" in source
    assert "hashlib.sha256" in source