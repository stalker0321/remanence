"""Restartable isolated native test backend entrypoint (root runs this later).

Same native Google + recovery + pending composition as T202, with
epoch custody in an explicit versioned state bundle: restarts
reconstruct identical keys against the same database. See
``remanence.pending.durable_bootstrap``. Owner-approved synthetic
test custody ONLY — never production.

Run from ``server/`` (root only; secrets via environment only)::

    REMANENCE_DATABASE_URL="postgresql+psycopg://<user>:<password>@127.0.0.1:55481/remanence_tmp_native_persistent_<unique>" \\
    PENDING_DEV_HMAC_SECRET=<generated-32+chars> \\
    uv run python scripts/pending_durable_server.py --init \\
        --pin-out /tmp/remanence-durable-pin.json \\
        [same --otp-sink/--blob-root/--google-audience/--google-presenters/ \\
         --android-origin as pending_native_server.py] \\
        --state-dir /tmp/remanence-durable-state-<unique>

Omit --init to resume. Nothing here runs at import time.
"""

from __future__ import annotations

from remanence.pending.durable_bootstrap import main

if __name__ == "__main__":
    raise SystemExit(main())
