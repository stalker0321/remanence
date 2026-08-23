# Product thesis and MVP boundary

Status: **DRAFT — Codex-owned; product scope is frozen when the architecture gate passes.**

This document is normative for product scope and physical-first user flows. It does not define architecture, APIs, cryptographic algorithms, or implementation.

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
- Sender capture of the postcard front and a prepared back
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
- Uncertain recognition MUST NOT guess. The product MUST require a retry or an explicit candidate chooser.
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
4. Capture the postcard front.
5. Fully prepare the postcard: write, address, and apply postage.
6. Capture the prepared back.
7. Select 3–5 photos.
8. Optionally add a note.
9. Encrypt and sign locally.
10. Upload ciphertext with resumable upload and finalize.
11. Physically send the postcard.

“Resumable” means that already uploaded encrypted blobs are not uploaded again after interruption. The MVP does not require byte-range multipart upload.

After publication, the sender may see only the operational result of the current send flow. The MVP MUST NOT retain a browsable sent-capsule history or a sender gallery.

## 9. First receipt

First receipt MUST proceed as follows:

1. Authenticated silent sync of encrypted pending recognition material.
2. Still captures of the postcard front and back.
3. Local matching against pending candidates.
4. If the match is not confidently unique, retry or an ambiguity chooser.
5. Local decrypt of envelope and content.
6. Open the capsule fullscreen.
7. Generate and persist the recipient front/back fingerprint.

## 10. Later scan

Later reopening MUST proceed as follows:

1. Scan both sides of the physical postcard.
2. Prefer the recipient fingerprint; fall back to the sender fingerprint.
3. Apply the same safe ambiguity rules as first receipt: no guessing; retry or an explicit chooser if not confidently unique.
4. Issue an in-memory scan grant and open the capsule.

App restart, process death, expired grant, or normal navigation back MUST require a new scan.

The product MUST NOT provide a capsule deep link or history route.

## 11. Ambiguity chooser

The ambiguity chooser MUST appear only after a plausible scan.

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
- Key loss behavior and the future recovery path are architecture/security concerns; incomplete recovery UI does not weaken the rule that the server never receives a private identity key.

## 15. MVP success boundary

M0 and M1 may use local/development infrastructure, but M2 is not successful with mocked accounts, mocked encryption, server-side matching, hardcoded recipients, fixture-only photos, or a capsule screen reachable without scan.

M2 requires two real accounts and two physical Android installations to complete the sender, first-receipt, app-restart, and later-scan scenario. If only one physical device is available during development, that is a documented test limitation, not a passing M2 result.
