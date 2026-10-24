# Pending gifts — status before release (2026-10-24)

A pending gift is a capsule for someone who is not yet a user: the sender
enters an email (or phone); the postcard carries a public five-character code;
the recipient signs up, proves the address with a one-time code, claims the
gift and then opens it by scanning the postcard like any other capsule.

Design: Codex tasks T183 (architecture, revision 2), T184 (integration),
T185 (server), T186 (Android) in `~/remanence-agent-control/tasks/`.

## How it works

- **Routing.** A verified address that already belongs to an account takes the
  normal v1 path (no escrow). Only an address with no verified owner uses
  **pending v2**.
- **Pending v2.** Each gift gets a random delivery target. The sender signs the
  target, an address commitment and the encrypted artifacts. The capsule key is
  escrowed for the service (vault + KMS custody): this gift is **not** strictly
  end-to-end encrypted until it is claimed. After the address is proven, the
  service binds the target to the account and HPKE-wraps that one gift key for
  its bundle. The sender's signature never changes.
- **Locator.** The five-character Crockford code identifies the gift only; it is
  never a key or a proof. It is retired in the background after the first
  successful opening; the claim itself is durable.

Code: server `server/src/remanence/pending/` (~8.4k lines), Android
`android/app/src/main/kotlin/dev/hryshyn/remanence/pending/` (~7k lines),
protocol fixtures under `protocol/`. Everything is behind flags that default
OFF; production/beta refuse to start pending without configured custody.

## What has been verified

- Server: unit, SQL-race and real-crypto tests (part of the 1507-test suite).
- Android, in an isolated `.pendingtest` package on an emulator, with a local
  synthetic mailbox: existing-account readiness, publication of a gift, receive
  → proof → claim → admission → real matching → photos shown, background code
  retirement, reopening after retirement without code or proof
  (T184 `r42`–`r44` findings, independently reviewed).

## What is missing for release

1. **Email (and optional SMS) provider.** Only a local mail sink exists. Needs a
   provider account, sending domain (SPF/DKIM/DMARC), templates, bounce/spam
   handling, rate limits and cost limits.
2. **Production key custody.** The vault requires a configured independent KMS
   key (`REMANENCE_PENDING_KMS_KEY_ID` in an allow-list). Nothing is provisioned.
   Decide where (e.g. Cloud KMS) and who can use the key.
3. **Real UI journey.** The native tests mount a standalone activity with a
   canonical FRONT input. Code entry, onboarding navigation, layout, EN/RU/UK and
   accessibility inside the real app have not been exercised; neither has a real
   new Google account + passkey in that flow.
4. **Recognition format.** The verified runs used the old v1 FRONT fingerprint.
   Pending candidates now parse both formats, but the pending path has not been
   run with the frozen-matcher (S3) fingerprints.
5. **Offline reopen.** Reopening a pending gift needs network for the
   directory and photo blobs (no photo cache). Known experimental limit.
6. **Privacy copy.** The escrow weakens confidentiality for pending gifts; the
   product, privacy policy and sender UI must say so plainly.

## Recommendation

Keep the design: routing to v1 whenever the address is already verified keeps
escrow to the minimum, and the claim/locator split is sound. Before enabling:
provision an email provider and KMS custody (1–2), run one full real-device
journey with S3 fingerprints (3–4), and write the escrow disclosure (6). SMS can
come later; email alone covers the product.
