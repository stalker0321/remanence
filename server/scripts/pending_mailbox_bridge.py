"""Synthetic OTP mailbox client bridge entrypoint (root runs this later).

Lets the Android TEST client fetch a LOCAL synthetic email code over
loopback; REAL R9/R11 on the epoch server verify it. See
``remanence.pending.mailbox_bridge`` for the frozen gating contract.

Run from ``server/`` (root only, pending epoch left running)::

    uv run python scripts/pending_mailbox_bridge.py \\
        --delivery-id <allowed-delivery-uuid> \\
        --owner-id <allowed-account-owner-uuid> \\
        --sink-path /tmp/remanence-pending-epoch-<epoch>/otp.jsonl

with REMANENCE_DATABASE_URL set to the shared epoch DB.
Nothing here runs at import time.
"""

from __future__ import annotations

from remanence.pending.mailbox_bridge import main

if __name__ == "__main__":
    raise SystemExit(main())
