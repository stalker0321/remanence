"""T384 synthetic accounts, actual signed publication helpers and actual app."""

from datetime import datetime, timezone
from uuid import uuid4

from remanence.main import create_app
from remanence.pending.vault import DevSealedVault
from remanence.send_access.policy import SendAccessMode as Mode
from remanence.settings import AppMode, Settings
from send_access_http_support import bearer
import send_access_support as signed
from test_pending_m3_pg import DEST_KEY, DEV_VAULT_KEY, HMAC_KEY, LOCATOR_CODE_KEY
from test_publication_deadlines import pending_request


def world(factory, monkeypatch):
    now = datetime.now(timezone.utc)
    monkeypatch.setattr(signed, "NOW", now)  # Fixture clock only, no production clock patch.
    with factory() as session:
        sender, recipient = signed.actor(session), signed.actor(session)
        sender.user.handle_normalized = sender.user.handle_display = "t384_sender"
        recipient.user.handle_normalized = recipient.user.handle_display = "t384_recipient"
        headers, _ = bearer(session, sender.user.id)
        session.commit()
    settings = Settings(mode=AppMode.TEST, send_access_mode=Mode.ENFORCE,
                        pending_protocol_enabled=True, pending_sends_enabled=True,
                        pending_hmac_secret=HMAC_KEY.decode("ascii"))
    ctx = dict(hmac_key=HMAC_KEY, locator_code_key=LOCATOR_CODE_KEY, destination_key=DEST_KEY,
               vault=DevSealedVault(mode="test", key_id="t384", key_32=DEV_VAULT_KEY), service_key_id=uuid4())
    app = create_app(settings, session_factory=factory, pending_ctx=ctx)
    return app, sender, recipient, headers


def publication(factory, tmp_path, sender, recipient, arm="v1", *, mode=Mode.ENFORCE):
    with factory() as session:
        item = signed.source(session, tmp_path, arm, sender=sender, recipient=recipient)
        if arm == "v2":
            item.kwargs = pending_request(item.setup, created_at=signed.NOW)
        session.commit()
        signed.publish(session, item, mode=mode, clock=lambda: datetime.now(timezone.utc))
        session.commit()
        return item
