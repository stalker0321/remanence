# Privacy policy and Play Data safety: draft for first gifts and the website

Status: draft, 9 November 2026. Not published. The live policy is served by
`server/src/remanence/api/legal.py` (`/legal/privacy`, dated 12 October 2026).
It must change in the same release that turns on first gifts for everyone
(`docs/pending-gifts-status.md`, step 6), and it already lags the website.

What changed since 12 October, and why the live text is no longer complete:

1. The website signs people in and keeps them signed in on that device.
2. A sender can send a first gift to a phone number (ADR-020), so we now hold a
   phone number for people who do not have an account.
3. That gift is not end-to-end encrypted until the recipient claims it.
4. SMS goes through a provider.

## Proposed text

The sections below replace or extend the live ones. Unchanged sections (optional
design and crop feedback, safety and retention) stay as they are.

### Remanence privacy policy

Policy of 9 November 2026. It covers the Remanence Android application, the
website at remanence.to and the service behind both.

**Account and delivery data.** We store your account ID, email, unique handle,
your Google account identifier when you use Google sign-in, public
encryption and signing keys, and session records. The service stores encrypted
postcards and recovery records, plus what it needs to route them: sender,
recipient, identifiers, sizes, timestamps and delivery state.

**End-to-end encryption.** Photographs and notes are encrypted on your device
before they leave it. Remanence never receives your private encryption keys or
your passkey's secret output. Google sign-in proves who you are; it cannot
decrypt anything. Your passkey manager stores the passkey under its own policy.
If you lose every way to recover your account, your encrypted postcards may be
lost too. The exception is a first gift, described below.

**Using the website.** When you sign in at remanence.to, the browser keeps
your session and your device keys on that device until you sign out. The keys
are stored so that the page can use them but cannot read them out, and they
never leave the device. Sign out on a shared computer when you are done.
Signing out removes them from that browser.

**First gifts to a phone number.** You can send a postcard to someone who does
not use Remanence yet by giving us their phone number. The postcard comes with
a code you write on the card by hand.

- We store the phone number encrypted, together with a keyed fingerprint that
  lets us find the gift when the recipient enters their number. We delete the
  number when the gift is claimed, cancelled or expired. A gift that is not
  claimed expires after 180 days.
- To prove they hold that number, the recipient receives a one-time code by
  SMS. We send it through an SMS provider, who receives the phone number and
  the message for delivery. We only send an SMS when a gift exists for the
  number, and we limit how many we send to each number.
- Until the recipient claims it, a first gift is protected by the code on the
  card and by the SMS check together. It is not end-to-end encrypted in that
  time: someone who controls our servers could, with very large effort, guess
  the code for one particular gift. Once the recipient claims it, the gift is
  encrypted to their own account like any other postcard.
- The sender can cancel an unclaimed gift at any time from their history.

**Camera, files and sharing.** We use the camera to photograph and recognise
postcards; on the website the photo is processed in the browser and does not
leave your device. You choose photographs yourself through the system picker.
Encrypted data may be cached on your device. Exporting an image creates a
readable copy; we cannot erase copies that you or others have exported or
received.

**Other services.** Google handles sign-in, and passkeys if you use its
password manager. The SMS provider delivers first-gift codes. Music search uses
MusicBrainz data; fetching cover art or opening a music service contacts those
providers, who may see the request and your network address. There is no
advertising and no third-party analytics.

## SMS provider wording

Name the provider actually in use when this is published:

- Today (testing only): messages are sent from the owner's own phone through
  SMS Gateway for Android in cloud mode, with end-to-end encryption between the
  server and the phone, so the relay sees neither number nor code. This must
  not be the production setup.
- Planned: BulkGate (Czech Republic, EU) once the company number (KvK) exists.
  Check their data processing terms and whether they keep message logs, and
  for how long, before naming them.

## Google Play Data safety form

Changes to the current answers:

| Data type | Collected | Shared | Purpose | Optional | Notes |
|---|---|---|---|---|---|
| Phone number | Yes | Yes, with the SMS provider | App functionality (delivering a first gift) | Yes, only when a sender uses it | Entered by the sender, about a recipient. Encrypted at rest, deleted on claim, cancel or expiry |
| Messages: other in-app messages | No change | No | | | Postcards stay encrypted on the device before upload |

Questions in the form:

- Is all data encrypted in transit? Yes.
- Can users ask for deletion? Yes: `/legal/delete-account`. A sender can also
  cancel an unclaimed gift, which deletes the number.
- Data about people who are not users: the phone number belongs to the
  recipient, not the sender. Play counts it as data the app collects, so declare
  it.

## Before publishing

- Decide the provider line above.
- Ask a lawyer whether the recipient needs to be told before the sender types
  their number. The SMS itself is the first contact today. A short "who sent
  this and how to stop it" line in the SMS may be required.
- Update the date at the top and in `legal.py`, and redeploy the backend.
