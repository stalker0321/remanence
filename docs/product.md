# Product thesis and MVP boundary

Status: **APPROVED product scope; recognition identity contract follows ADR-012.**

This document is normative for product scope and physical-first user flows. It
does not define architecture, APIs, cryptographic algorithms, or
implementation. For recognition identity, ADR-012 is current: new capsules
are FRONT_ONLY, while existing two-sided v1 capsules remain strict/readable.

## 1. Purpose

The physical postcard is the primary object. The digital capsule stays hidden until a successful physical scan of that postcard.

## 2. North stars

- Send a memory, not just a postcard
- Нет открытки — нет воспоминания
- Не помогать людям делиться чаще. Помогать им делиться реже, но осмысленнее.

## 3. Production-shaped MVP scope

The MVP is production-shaped, not a throwaway prototype. In scope:

- Native Android application
- Real accounts and normalized unique handles backed by an immutable UUID `user_id`
- Explicit recipient resolution
- Capsule content: 3–5 photos plus an optional note
- Sender capture of the postcard FRONT; BACK is optional for new FRONT_ONLY
  capsules and remains strict only for legacy two-sided v1
- Local hierarchical recognition
- Real end-to-end encryption (E2EE)
- Dumb backend for routing and ciphertext storage only
- Two-user first-receipt and later-scan flows

## 4. Product invariants

- The product MUST NOT provide any capsule-entry path without a fresh successful physical scan.
- The product MUST NOT provide an All Memories view, gallery, or any other inventory of capsules independent of a physical scan.
- The product MUST NOT include a feed, followers, likes, comments, public profiles, discovery, streaks, badges, leaderboards, or social reminders.
- The product MUST NOT gamify collection of postcards or memories.
- The product MUST NOT display capsule counts, pending counts, sent counts, or engagement statistics.
- Uncertain recognition MUST NOT guess. Zero candidates require a retry; one
  candidate still requires full verification; multiple candidates must not
  auto-open and remain blocked until the future explicit candidate picker is
  shipped.
- A postcard MUST be treated as a UX recognition token. It MUST NOT be treated as a cryptographic secret.
- Sender and recipient relationships MUST use immutable user IDs. Handles are mutable display/search attributes only.

## 5. Deferred / non-goals

Out of scope for this MVP:

- Music implementation
- Contacts UI
- AR
- Realtime video matching
- iOS
- Web
- Notifications
- Monetization
- Polished recovery
- Commercial scale

## 6. M2 success statement

Two users with installed APKs complete an encrypted physical transfer. First receipt creates the recipient fingerprint. Later reopening of the capsule requires a rescan.

## 7. Minimal surface

Create and Scan are the only required home entry points.

Conceptual Create / Receive / Scan is interpreted for MVP as follows:

- **Create** is a home entry point.
- **Scan** is a home entry point.
- **Receive** is a lifecycle, not a home entry point. Scan handles first receipt and later reopening.

The product MUST NOT present Receive as a pending-capsule list, inbox, or gallery.

Silent receipt of encrypted routing/index material is infrastructure, not a user-visible memory surface. It MUST NOT expose sender names, dates, places, thumbnails, notes, or capsule counts before a plausible physical scan.

## 8. Sender flow

The sender flow MUST follow this order:

1. Authenticate and establish a handle.
2. Resolve the recipient handle to an immutable `user_id` plus the recipient’s active public key.
3. Explicitly confirm the resolved recipient.
4. Capture the postcard FRONT.
5. For a legacy two-sided v1 capsule only, fully prepare and capture the BACK.
   New FRONT_ONLY creation does not require or synthesize BACK.
6. Select 3–5 photos.
7. Optionally add a note.
8. Encrypt and sign locally.
9. Upload ciphertext with resumable upload and finalize.
10. Physically send the postcard.

“Resumable” means that already uploaded encrypted blobs are not uploaded again after interruption. The MVP does not require byte-range multipart upload.

After publication, the sender may see only the operational result of the current send flow. The MVP MUST NOT retain a browsable sent-capsule history or a sender gallery.

## 9. First receipt

First receipt MUST proceed as follows:

1. Authenticated silent sync of encrypted pending recognition material.
2. Capture the postcard FRONT. Capture BACK only when the explicit identity
   mode requires or permits it.
3. Match locally against the owner-scoped design-to-capsule candidates.
4. Zero candidates require retry; one candidate proceeds only after full E2EE
   verification; multiple candidates never auto-open and await the future
   ambiguity picker.
5. Locally decrypt the verified envelope and content.
6. Open the capsule fullscreen.
7. Generate and persist the recipient FRONT fingerprint, retaining optional
   explicitly captured BACK evidence without changing identity mode.

## 10. Later scan

Later reopening MUST proceed as follows:

1. Scan the FRONT of the physical postcard; use BACK only under its explicit
   identity mode.
2. Prefer the recipient FRONT fingerprint; fall back to the sender FRONT
   fingerprint.
3. Apply the same safe design-to-many rules as first receipt: no guessing or
   auto-open for multiple candidates; retry or use the future explicit picker.
4. Issue an in-memory scan grant and open the capsule.

App restart, process death, expired grant, or normal navigation back MUST require a new scan.

The product MUST NOT provide a capsule deep link or history route.

## 11. Ambiguity chooser (future M2-F1)

The ambiguity chooser is a future milestone, not part of the current FRONT_ONLY
migration. Until M2-F1 is shipped, multiple plausible capsules MUST remain
blocked rather than guessed. Once shipped, the chooser MUST appear only after
a plausible scan.

It MUST show only minimal locally decrypted hints:

- sender handle snapshot
- date/year
- optional place label

Selection MUST be explicit. The chooser is not a gallery.

The optional place label exists only as encrypted chooser context. It is not required capsule content and MUST NOT become a searchable location index.

## 12. Failure behavior

- Unresolved recipient MUST block creation.
- Crypto integrity failure MUST show nothing.
- No match MUST ask the user to recapture.
- Offline later scan is permitted only when ciphertext and keys are locally cached. Otherwise the product MUST explain that connectivity is required.

## 13. Meaning of the physical gate

The physical gate is an intentional product and honest-client constraint, not DRM. A successful current scan issues a short-lived in-memory capability to present one capsule. The application MUST invalidate that capability on logout, expiry, leaving the presentation flow, or process death.

A rooted device, modified client, unlocked-device attacker, screenshot, or direct extraction from process memory is outside this product guarantee. Cryptographic access is controlled by the recipient account identity; visual recognition controls the official application experience.

## 14. Account and identity assumptions

- MVP authentication uses a private email plus password. Email is not part of the public product identity.
- The current public handle is unique after ASCII lowercase normalization and matches `[a-z0-9_.]` with a documented length limit.
- Recipient confirmation MUST bind the current display handle to immutable `user_id` and the selected active public-key ID.
- Password reset and E2EE recovery are separate. Recovering authentication MUST NOT be presented as recovering encrypted memories.
- Public release requires ADR-010 durable account-key recovery. M2 remains an
  explicitly unrecoverable product proof until that milestone passes; this
  limitation must never be hidden from testers.
- Recovery uses a platform-neutral account recovery root with independently
  replaceable adapters. Google, Apple, passkeys, and device transfer are not
  themselves the Remanence cryptographic identity.
- Key loss behavior and recovery UI do not weaken the rule that the server never receives a private identity key, plaintext account recovery root, or an unwrap secret.

## 15. MVP success boundary

M0 and M1 may use local/development infrastructure, but M2 is not successful with mocked accounts, mocked encryption, server-side matching, hardcoded recipients, fixture-only photos, or a capsule screen reachable without scan.

M2 requires two real accounts and two physical Android installations to complete the sender, first-receipt, app-restart, and later-scan scenario. If only one physical device is available during development, that is a documented test limitation, not a passing M2 result.
