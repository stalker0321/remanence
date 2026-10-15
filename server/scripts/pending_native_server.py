"""Opt-in native Google + pending dev server entrypoint (root runs this later).

Combines the reviewed pending dev composition with REAL Google
verification and recovery for the native RP/Android origin. See
``remanence.pending.native_bootstrap``. AVD preparation only — not
provider acceptance or a remotely usable release.

Run from ``server/`` (root only)::

    uv run python scripts/pending_native_server.py \\
        --database-url postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_<unique> \\
        --hmac-secret <32+char-secret> --pin-out /tmp/remanence-native-pin.json \\
        --otp-sink /tmp/remanence-native-otp.jsonl --blob-root /tmp/remanence-native-blobs \\
        --google-audience <web-client>.apps.googleusercontent.com \\
        --google-presenters <android-client>.apps.googleusercontent.com \\
        --android-origin 'android:apk-key-hash:<43 base64url>'

Nothing here runs at import time.
"""

from __future__ import annotations

from remanence.pending.native_bootstrap import main

if __name__ == "__main__":
    raise SystemExit(main())
