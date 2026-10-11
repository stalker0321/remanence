"""Shared contact admission lock; no API-layer dependency."""
from sqlalchemy import text

TERMS_VERSION = "2026-10-11"


def owner_lock(db, owner):
    key = int.from_bytes(owner.bytes[:8], "big", signed=True)
    db.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": key})
