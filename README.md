# Remanence

A postcard you hold opens a memory you sent. The sender picks photos, a few
words and maybe a song, and Remanence lays them out as a finished sheet. The
sender then photographs the physical postcard. When the recipient points a
camera at the same card, the sheet opens on their device.

- Send a memory, not just a postcard.
- No postcard, no memory.
- Share less often, but with meaning.

Photos, notes and songs are encrypted on the sender's device for the
recipient. The server stores ciphertext and the routing it needs. It never
holds the keys.

## Status, November 2026

| Part | State |
| --- | --- |
| Android | `0.2.0-beta.15` (versionCode 50) on the Play internal track. Make, send, scan and open postcards, with the album, history, music, recovery and languages. |
| Website | Live at [remanence.to](https://remanence.to). Sign in with Google or a passkey, make and send a postcard, scan and open one, history, settings, installable as a PWA. The site cannot attach a song yet (see below). |
| Server | FastAPI and PostgreSQL. Accounts, key directory, capsule drafts and finalize, delivery feeds, music search, send access, recovery. |
| First gifts | Sending to a phone number without an account ([ADR-020](docs/decisions/ADR-020-first-gift-card-seal.md)). Built on all three clients and behind flags. Not yet run with real SMS. See [`docs/pending-gifts-status.md`](docs/pending-gifts-status.md). |

Known gaps:

- The website makes only one of the 14 layouts (grammar 5). Its song band is
  too short for a two-line title and artist in every style, so the song picker
  stays hidden. Everything after the picker already carries a song.
- The privacy policy has to change before first gifts go to everyone. The draft
  is in [`docs/privacy-policy-draft-2026-11.md`](docs/privacy-policy-draft-2026-11.md).

## Repository

| Path | What it is |
| --- | --- |
| [`android/`](android/) | Kotlin and Jetpack Compose app. `app` plus `core:model`, `core:crypto`, `core:data` and `core:recognition`. |
| [`web/`](web/) | React and TypeScript client built with Vite. Its own README covers checks and builds. |
| [`server/`](server/) | Python 3.13 backend: FastAPI, SQLAlchemy and Alembic. |
| [`protocol/`](protocol/) | Protobuf schema and the shared byte vectors that all three clients test against. |
| [`deploy/`](deploy/) | Release scripts for the backend, the website and Play, plus backups. |
| [`docs/`](docs/) | Product, architecture, security, protocol and decisions. |

## Checks

```sh
# Android (JDK 17; run large Robolectric suites in small batches)
cd android && ./gradlew :core:model:test :core:crypto:test :core:data:test :core:recognition:test
cd android && ./gradlew :app:testDebugUnitTest --tests '<package or class>'

# Website (Node 22)
cd web && npm ci && npm run typecheck && npm test && npm run build

# Server (Python 3.13 through uv, PostgreSQL from compose.yaml)
cd server && uv run pytest
```

Toolchain versions are pinned in [`docs/development.md`](docs/development.md).

## Documents

- [`docs/product.md`](docs/product.md): product scope and the physical-first rules
- [`docs/architecture.md`](docs/architecture.md): clients, server, storage and sync
- [`docs/security.md`](docs/security.md): threat model, keys, end-to-end encryption and recovery limits
- [`docs/protocol.md`](docs/protocol.md): protobuf, REST and database contracts
- [`docs/recognition.md`](docs/recognition.md): on-device SIFT/RootSIFT matching
- [`docs/decisions/`](docs/decisions/): accepted architecture decisions (ADR-001 to ADR-020)
- [`docs/versioning.md`](docs/versioning.md): product, Android, wire and artifact versions
- [`docs/copy-style.md`](docs/copy-style.md): interface writing rules
- [`CHANGELOG.md`](CHANGELOG.md): what changed in each release
