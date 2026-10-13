"""Frozen v2 canonical domains (T185 M1). No secrets, no I/O."""

from __future__ import annotations

# Every NEW domain input is ASCII(domain) || 0x00 || canonical_message.
# NUL delimiter intentionally differs from the frozen v1 publish prefix.
_DOMAIN_SEPARATOR = b"\x00"

DELIVERY_ADDRESS_DOMAIN = "postmark/delivery-address/v1"
DELIVERY_KEY_DOMAIN = "postmark/delivery-key/v1"
ARTIFACT_V2_DOMAIN = "postmark/artifact/v2"
PENDING_PUBLISH_DOMAIN = "postmark/pending-publish/v2"
CLAIM_BINDING_DOMAIN = "postmark/claim-binding/v1"
CLAIMED_ENVELOPE_DOMAIN = "postmark/claimed-envelope/v2"
CLAIM_CHALLENGE_DOMAIN = "postmark/claim-challenge/v1"
CLAIM_INTENT_DOMAIN = "postmark/claim-intent/v2"

ALL_V2_DOMAINS: tuple[str, ...] = (
    DELIVERY_ADDRESS_DOMAIN,
    DELIVERY_KEY_DOMAIN,
    ARTIFACT_V2_DOMAIN,
    PENDING_PUBLISH_DOMAIN,
    CLAIM_BINDING_DOMAIN,
    CLAIMED_ENVELOPE_DOMAIN,
    CLAIM_CHALLENGE_DOMAIN,
    CLAIM_INTENT_DOMAIN,
)


def domain_prefix(domain: str) -> bytes:
    """Exact domain framing bytes: ASCII(domain) || 0x00."""
    encoded = domain.encode("ascii")
    return encoded + _DOMAIN_SEPARATOR


def domain_digest(domain: str, canonical_message: bytes) -> bytes:
    """SHA-256(domain_prefix || canonical_message). Pure helper for fixtures."""
    import hashlib

    return hashlib.sha256(domain_prefix(domain) + canonical_message).digest()
