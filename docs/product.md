# Product thesis and MVP boundary

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
- Uncertain recognition MUST NOT guess. The product MUST require a retry or an explicit candidate chooser.
- A postcard MUST be treated as a UX recognition token. It MUST NOT be treated as a cryptographic secret.

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

## 12. Failure behavior

- Unresolved recipient MUST block creation.
- Crypto integrity failure MUST show nothing.
- No match MUST ask the user to recapture.
- Offline later scan is permitted only when ciphertext and keys are locally cached. Otherwise the product MUST explain that connectivity is required.
