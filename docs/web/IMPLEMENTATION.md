# Remanence web implementation

Branch `work/t213-remanence-web-20261017` from BASE `5a6e7fd65f7be752daa1c933134df7918dde33ee`.
Worktree: `/home/vodkolyan/projects/Remanence-web-t213-20261017`.
Build machine: Ubuntu VM `msi-workstation`, Node 22.22.1 unpacked under
`/home/msi-workstation/remanence-web-t213-20261017/tools/node` (official tarball,
SHA-256 `07c8aafa60644fb81adefa1ee7da860eb1920851ffdc9a37020ab0be47fbc10e`).

The integration worktree and the release tree are not modified. Heartbeat stays off.

## Stack

TypeScript + Vite 8 + React 19, in `web/`. Versions are the registry releases
recorded in `web/package.json` and the lockfile. No Next, no Kotlin Multiplatform,
no server-side decryption.

Modules, as they land:

| Area | Path | Role |
| --- | --- | --- |
| UI / session | `web/src/session`, `web/src/ui` | Phase machine. Tokens stay off the UI snapshot. |
| Language | `web/src/preferences`, `web/src/i18n` | EN / RU / UK. Only the language key is stored. |
| Protocol codecs | `web/src/protocol` | AAD, envelopes, manifests. Next. |
| Key owner | `web/src/crypto` | In-memory unlock. Next. |
| Transport | `web/src/transport` | Existing HTTP contracts. Next. |
| Recognition | `web/src/recognition` | SIFT/RootSIFT parity, or an isolated blocker. |
| Presentation | `web/src/presentation` | Frozen collage plan only. |

## Contracts already in BASE

Google (`server/src/remanence/api/google_auth.py`):

- `POST /v1/auth/google/options` → `{flow, nonce, client_id}`
- `POST /v1/auth/google/verify` with the same nonce inside the ID token
- Response status is `SIGNED_IN`, `NEEDS_HANDLE`, or an error reason.
  `EXISTING_ACCOUNT_REQUIRES_LINK` (409) is not a new identity.
- Header `X-Remanence-Profile-Version: 2` is required before a handleless
  account can receive a session. Older clients get 426.
- The SPA uses the `client_id` from options. No client secret.

Recovery (`server/src/remanence/api/recovery.py`, `recovery/web/crypto.mjs`):

- Browser probe `openRecovery()` validates, then wipes plaintext. It is not a session.
- Package framing is `RPK1`, separate from Tink artifact framing and from HPKE.
- PRF output never goes to the server. Passkey manager and Google account chooser
  are different steps.

Wire:

- Artifact AEAD (ADR-005): TINK prefix 5 + IV 12 + ciphertext + tag 16, overhead 33.
  AAD is `postmark/artifact/v1 || 0x00 || ArtifactAadContext`.
- Recipient envelope: Tink HPKE `DHKEM_X25519_HKDF_SHA256 / HKDF_SHA256 / AES_256_GCM`,
  info `postmark/envelope/v1 || 0x00 || RecipientEnvelopeContext`.
  Golden vector: `protocol/fixtures/recipient-envelope-v1.json`.
- Publish signatures are 69-byte TINK-prefixed Ed25519.
- UUID protobuf bytes are 16-byte big-endian RFC UUID layout.
- Unknown versions and RAW prefix fail closed. No fallback.

Product:

- No postcard, no memory. Home is scan and create, not an inbox.
- Android copy for those two entries is the source of the EN/RU/UK home strings.
- Surfaces follow Hold paper `#f4f2ec`, ink `#333951`, lilac `#d6cce9`, sand `#f4e8c7`.

## Security choices for this client

- Access and refresh tokens, ARK, PRF, and private keys live in memory.
  Reload means unlock again.
- `localStorage` may hold `remanence.language` only.
- UI snapshot generation fences late async results. Logout and account switch
  increment it and drop secrets.
- Production responses for auth, recovery, and API must be `Cache-Control: no-store`.
  The static shell must send a CSP that does not allow third-party script, except
  the Google Identity host when that flow is actually wired.
- WebCrypto keys are not Android Keystore. Best-effort buffer clearing is not a
  promise of zeroize.

## Order

1. W0 foundation — this package, session, languages, shell.
2. W1 codec + memory key owner, checked against the committed golden vectors.
3. W2 Google verify and passkey restore against the existing endpoints.
4. W3 owner-scoped ciphertext fetch and recognition parity.
5. W4 frozen presentation render.
6. W5 fixture journey and negative UI states.
7. W6 new-user and sender path only after the receiver path holds.

## W1 crypto adapter

`web/src/protocol` builds artifact AAD and recipient-envelope info with the
same domain prefixes and protobuf fields as `CryptoContextEncoder`.
`web/src/crypto` parses a single TINK keyset only.

- HPKE open uses `@hpke/core` 1.9.0 (MIT), suite
  DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM.
  The library's private-key import is extractable because its decap derives
  the public key through a JWK export. The UI capability does not return that
  key. Seed copies are wiped after import. This is not Android Keystore.
- Artifact decrypt is AES-256-GCM under the 5-byte TINK prefix, 12-byte IV,
  and the artifact AAD. RAW, CRUNCHY, LEGACY, a mismatched key id, and an
  unknown type URL fail before a fallback.
- Publish signatures are the 69-byte TINK Ed25519 form. The signed input is
  `postmark/publish/v1` concatenated with the statement bytes, with no extra
  0x00 separator.
- `openRecovery()` in the browser probe still returns only the diagnostic and
  wipes the seeds. `openIdentity()` is the in-memory entry the key owner uses.
  The owner also requires `UUID.nameUUIDFromBytes(encryption public keyset)`
  to equal the bundle id, which is what Android restore checks.

The committed golden vectors for the envelope, the publish signature, and the
context bytes are the interop gate. There is no committed Android ciphertext
for a capsule artifact, and the Kotlin recovery fixture is produced only into
`build/`. Those two are covered by a JS producer and are labeled as such.

## W2 account entry

Google continues with one `POST /v1/auth/google/options` and one
`POST /v1/auth/google/verify`. The ID token nonce is the server nonce. The
client does not read the token's email and does not call `/register`.
`SIGNED_IN` stores the server account and leaves the phase at authenticated.
`NEEDS_HANDLE`, `EXISTING_ACCOUNT_REQUIRES_LINK`, and `CLIENT_UPGRADE_REQUIRED`
leave the session signed out.

The browser loads `https://accounts.google.com/gsi/client` only after the
person chooses continue. That script is the Google Identity Services boundary.
A real JavaScript origin for this page was not configured, so the button is
not a completed Google sign-in.

Signed-in restore reads `/v1/recovery/status` and, only for `STORED`, calls
`/v1/recovery/setup/options`, a passkey get, `/setup/verify`, and
`GET /v1/recovery/record`. It does not call anonymous `/login` or
`/register`. The PRF stays in the unlock call. `NOT_STARTED` and `PENDING`
do not create a passkey. `PENDING` is the saved-credential continuation and
does not unlock. Cancel, missing PRF, and a browser without WebAuthn are
different results. Keys live in `MemoryKeys` and drop on lock or sign-out.

## Known gaps at the start of W0

`docs/product.md` still lists Web as out of scope. That sentence is the older
MVP boundary. This task allows a separate client and does not relax the crypto
or the physical-scan rule.

The recovery interop JSON is produced by the Android test into `build/` and is
not in git. W1 must not claim that vector until a producer actually writes it.
Committed protocol fixtures are the first interop gate.
