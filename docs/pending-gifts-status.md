# First gifts: status

Superseded design: this file used to describe escrow, KMS and a five-character
lookup code. The current design is `docs/decisions/ADR-020-first-gift-card-seal.md`.

## Turning it on

Everything stays off until all of these are done, in this order.

1. **BulkGate.** Create the account, add credit, and create an application for
   the transactional API. Note its ID and token. Send a test SMS to a Dutch and
   a Ukrainian number from their console and see how fast they arrive.
   BulkGate needs a company number (KvK). Until then, testing runs on the
   owner's phone with SMS Gateway for Android in cloud mode: set
   `REMANENCE_PENDING_SMSGATE_USERNAME`, `_PASSWORD` and `_PASSPHRASE` (the
   same passphrase in the app's encryption setting, so the relay sees neither
   number nor code). A Twilio trial cannot send custom text; a paid Twilio
   account works through `REMANENCE_PENDING_TWILIO_*`. One provider at a time.
2. **Secrets.** Run `server/.venv/bin/python deploy/pending-secrets.py` once.
   Keep the output somewhere safe. Add its server lines and the BulkGate ID and
   token to `server.env`. Losing the master secret strands every unclaimed gift.
3. **Server.** Deploy the backend from a commit that has migration
   `0020_card_seal`. The migration runs on restart.
4. **Web.** Add `pendingServicePin` to the live `app-config.json`. Run
   `npm install` once on the NL build host, because `deploy-web.sh` builds with
   `--offline` and `hash-wasm` is new. Then deploy the web.
5. **Android.** Build with the three `-Premanence.pending.*` properties. The MSI
   build uses `--offline`, so fetch `bcprov-jdk18on` there once without it.
6. **Legal, in the same release.** The privacy policy must say that a sender
   gives us a phone number, that we store it encrypted until the gift is claimed,
   cancelled or expired, that BulkGate delivers the SMS, and that a first gift is
   not end-to-end encrypted until claimed. Update Data safety in Play: phone
   number (collected, shared with the SMS provider for delivery).
7. **Copy pass** of `pending_strings.xml` and the `gift*` web strings against
   `docs/copy-style.md`, on a real phone.

## What has been verified

Unit and integration tests only: the threat table in ADR-020 over HTTP on a real
PostgreSQL, shared byte-for-byte vectors across Python, Kotlin and TypeScript,
the Android recipient flow with real crypto and a fake network, and the recipient
admission accepting a self-sealed envelope.

## What has not

No real SMS, no real device, no real browser. The web claim has only been run
against vectors and a fake transport. Argon2 timing on a slow phone is unmeasured.

## Left to do

- Remove the escrow, KMS and locator code once the new path is proven on a device.
- Block a sender from making a capsule for themselves on a postcard they received.
