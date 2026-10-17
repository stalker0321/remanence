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
| Presentation | `web/src/collage` | Frozen GENEX/BEXPR prepare and canvas paint. |

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

## W3 sync and scan boundary

`fetchIncoming` reads one `GET /v1/capsules/incoming` page and the sender
`GET /v1/directory/key-bundles/{id}` record. It checks the publish signature,
the statement hash, and that the signed statement names the same account,
bundles, and blob bindings as the page. Ciphertext stays ciphertext. The
session phase does not change. `downloadCiphertext` checks the body length
and SHA-256 against that binding.

The scan screen can open a camera and keep a JPEG still in memory. It does
not upload that still. There is no SIFT/RootSIFT build in this client. The
recognition port fails closed with `RECOGNITION_UNAVAILABLE`. A grant exists
only after an engine reports one capsule, and admitting it only moves the
phase to postcard-matched. It does not decrypt or render. No browser SIFT
library was downloaded.

## C1 frozen render

`web/src/collage` checks GENEX03 and GENEX04 against the Android test bytes in
`web/test/fixtures/expression-goldens.json`, then prepares a scene only for
contract V3 or V4. Codec goldens that use a fake style digest are rejected.
An older magic throws `UNSUPPORTED` and nothing is drawn. Masks and
`contentRect` are rejected. A missing or mismatched cover degrades to text.
A verified V3 thumbnail replaces the music band. Print-plane shadow stays on
the scene and is not stroked. The canvas is 360×640. CSS scales that bitmap.
Export is `canvas.toBlob` of that bitmap.

`openGrantedPostcard` runs only in `postcard-matched`, with the matching
memory keys and a grant. It opens the envelope, checks `statementSha256`,
decrypts content and JPEG blobs, and prepares the scene. It does not admit.
`showGrantedPostcard` holds object URLs and then admits. Lock and sign-out
revoke those URLs and zero the retained byte copies. Bytes inside the blob
created by `createObjectURL` cannot be zeroed. Closing the sheet calls
`lock()`, so the keys lock with the sheet. Closing the photo viewer does not.

The product build has no fixture route. `web/harness` is a second Vite root
and is absent from `vite build` of `index.html`. Its ciphertext and paintings
are synthetic. Firefox 155 loaded the production bundle and completed an
`@hpke/core` seal/open in the harness. The Node `crypto` fallback chunk was
not requested because the browser has `globalThis.crypto`.

## C2 receive journey

`receivePostcard` runs only from `keys-unlocked`. An empty incoming list
throws `EMPTY` before recognition. A missing content blob, photo blob, or
envelope throws `BODY_MISSING` before `admitScan`. Sign-out or lock during
the match throws `STALE` and does not admit. After a successful open, the
sheet is held and the phase becomes `presentation-admitted`. A cancel after
the match and before the hold leaves the phase at `postcard-matched` and
shows no sheet.

The scan screen fetches one incoming page and then calls that function.
The production engine is still `unavailableRecognition`. The button therefore
reports that recognition is unavailable after a real list, and it does not
draw a postcard. Locked keys never start the fetch. Closing the camera or
leaving the screen aborts the controller. `LocalScan` stops a session that
resolves after that abort.

The harness journey uses the same function with a scripted match and a JS
AES-GCM producer. Firefox 155 painted that admitted sheet and then saw the
object URLs drop on sign-out. That producer is not Android output, and the
camera in that page is not a device.

## C3 recognition boundary

No browser SIFT build is on this VPS or on `msi-workstation`. `emcc` is absent.
The VM tools directory contains Node only. Desktop OpenCV 4.10 is installed as
`libopencv-features2d410` and `/usr/share/java/opencv4/opencv-4100.jar`. That
jar is the JNI library the Android unit tests compile against. The app artifact
depends on `org.opencv:opencv:4.12.0`. Neither is a WASM module the page can
call.

The official builder is OpenCV `4.12.0` `platforms/js/build_js.py`. It enables
`features2d` and refuses to configure unless `EMSDK` or `EMSCRIPTEN` points at
an activated Emscripten SDK. That SDK is not present. Installing it and
compiling OpenCV is the large toolchain this task does not download by default.
`unavailableRecognition` stays the production engine.

The missing operation is `SIFT.create(0, 3, 0.018, 12.0, 1.6)` plus the existing
6×6 / 45 / 1,500 selection and the matcher’s one RootSIFT step. The smallest
follow-up is a VM-local Emscripten pin named by that OpenCV release, one
`features2d` wasm build, and a descriptor comparison against a frozen extractor
fixture. Thresholds stay where they are. A 4.10 desktop jar is not that build.

## C4 registration ticket

`continueWithGoogle` still does not call `/register`. A `NEEDS_HANDLE` body
with a 43-character registration token is held in `RegistrationDraft`, which
is memory in the page and is not stored. The returned outcome does not carry
the token. `EXISTING_ACCOUNT_REQUIRES_LINK`, a signed-in account, and a second
Google ceremony wipe that draft, including any identity it already created.

The handle form is shown only while that ticket is held and the phase is
`signed-out`. Cancel wipes the draft. Reload drops it because nothing was
written down. `registerNewAccount` folds the handle the way the server does,
generates one X25519/Ed25519 identity on the first submit, and posts
`/v1/auth/google/register`. A 409 `REGISTRATION_CONFLICT` keeps the ticket and
the same keysets. Success signs in only when the echoed bundle id and handle
match, then drops the ticket and keeps the identity for a later enrollment.
An authenticated session cannot call register. Enrollment, PRF, and the
recovery record are not in this slice. Production send stays off.

Firefox 155 generated one of those identities in the harness, checked the
bundle id against the encryption keyset, and then wiped the seed copies.
WebCrypto key objects themselves cannot be zeroed.

## C4 enrollment

`enrollHeldIdentity` runs the existing ceremony on the identity already held
after registration. `NOT_STARTED` creates one discoverable credential, verifies
it, then setup, PRF, and one `POST /v1/recovery/record`. `PENDING` continues
that credential and does not create another. `STORED` skips create and the
POST, reads the record, and returns restored. A draft whose bundle does not
match the signed-in account throws `IDENTITY_ABSENT` before any request.

The package is RPK1, AES-GCM, and WebCrypto HKDF, then `openIdentity` checks
both the PRF and the recovery code before the record is returned. The code is
32 random bytes, shown once in the account view, and cleared on sign-out. It
is not written to localStorage or the clipboard. If the POST succeeds and the
readback throws, the code is wiped and is not shown. The next open uses the
stored record through PRF. This record is a JS producer. It is not an Android
`AccountRecoveryCrypto` export. The browser smoke did not click Save passkey.

## C4 recipient

Create asks `GET /v1/directory/handles/{handle}` only while the keys are unlocked.
The handle is folded before the request. The reply must name that same handle,
the same user on the summary and the key bundle, the active suite, and no extra
fields. Confirmation copies that snapshot into `RecipientDraft`. For myself
looks up the signed-in handle and keeps the snapshot only when the user id
matches. A second lookup, a restart, a lock, or a sign-out drops it. The page
shows the handle, not the keyset. Nothing is uploaded.

The Firefox smoke did not submit the form. Dotted handles are allowed in the
API path. A `.` or `..` path segment is still refused.

## C4 front still

After confirmation, Create uses the same camera port as scan. One still is
kept with that recipient's user id, key bundle, and directory version. A
camera that resolves after cancel is stopped. Closing the camera, locking
the keys, or choosing another recipient zeroes the bytes. The bytes are not
posted. The still is not a SIFT fingerprint, and the page does not draw a
second copy of it.

## C4 note

The note field counts UTF-8 bytes with `TextEncoder`, not characters.
`note_max_utf8_bytes` in `protocol/fixtures/limits-v1.json` is 1000. A note
of 500 `я` fits. One more byte does not. The over-limit text stays in the
field and `accepted()` returns null. An empty note is accepted as no note.
Lock and sign-out clear the field. It is not written to localStorage.

## C4 photos

The create draft accepts a file only when it is at most
`normalized_photo_max_plaintext_bytes` (8388608) and starts with the JPEG
marker. The list holds at most five. Three to five can be accepted. Remove
and reorder do not upload anything. Lock zeroes the draft bytes. Preview
object URLs are revoked on remove, lock, and unmount; the blob copy behind an
object URL cannot be zeroed. The long edge is not measured and is not resized.

## Known gaps at the start of W0

`docs/product.md` still lists Web as out of scope. That sentence is the older
MVP boundary. This task allows a separate client and does not relax the crypto
or the physical-scan rule.

The recovery interop JSON is produced by the Android test into `build/` and is
not in git. W1 must not claim that vector until a producer actually writes it.
Committed protocol fixtures are the first interop gate.
