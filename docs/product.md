# Product thesis and MVP boundary

This document is normative for product scope. It does not define flows, architecture, APIs, cryptographic algorithms, or implementation.

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
