# Android MVP preparation — morning handoff

Local code/test preparation only. No Google OAuth setup, live migration, hosted
deployment, native provider acceptance, PWA or Apple sign-in is claimed.

## Owner inputs

1. SSH access to the existing Windows VM, using the configured `win-hyperv`
   alias; do not assume it is Ubuntu. Discover actual AVDs/SDK/network afresh.
2. Google Cloud project: Google Auth Platform brand/consent configuration, owner
   Google account as an OAuth test user while the app is in testing, OAuth Web
   client ID, Android client for the actual test package/signer SHA-1. Play
   Console membership is not this configuration. Client IDs are public; do not
   paste passwords, refresh tokens, private keys or client secrets in chat.
3. Music machine: exact work directory/drive, free disk, RAM and CPU. About
   150 GB free is not automatic admission for old+new DB/index/temporary data.

Apple sign-in deferred. Google auth on future iOS/browser remains in scope, but
no iPhone test is available; mark it NOT_TESTED.

## Isolated DEV backend — preparation, not deployed

Use a separate test database and blob directory; NEVER apply this migration to
the existing hosted product database during initial acceptance. Serve behind an
approved new path on `https://remanence.hryshyn.dev/`, preserving the product
and `/keys-test/` routes. RP ID stays `remanence.hryshyn.dev` (no path).

Required settings:

```text
REMANENCE_MODE=dev
REMANENCE_RECOVERY_ENABLED=true
REMANENCE_GOOGLE_SIGN_IN_ENABLED=true
REMANENCE_GOOGLE_CLIENT_IDS=["<WEB_CLIENT_ID>.apps.googleusercontent.com"]
REMANENCE_GOOGLE_PRESENTER_IDS=["<ANDROID_CLIENT_ID>.apps.googleusercontent.com"]
REMANENCE_RECOVERY_RP_ID=remanence.hryshyn.dev
REMANENCE_RECOVERY_ORIGINS=["https://remanence.hryshyn.dev","<ACTUAL_APK_ANDROID_ORIGIN>"]
REMANENCE_DATABASE_URL=<ISOLATED_TEST_DATABASE>
REMANENCE_BLOB_ROOT=<ISOLATED_TEST_BLOBS>
```

`uv sync --locked`; `uv run alembic upgrade head` only on that isolated DB.
Add a DAL association for actual `.recoverytest` package/signer while preserving
existing entries. See `recovery-passkey-integration.md` for origin/DAL details.
Google's Web client ID is returned by the server, not hardcoded into the APK.
No Google client secret is required for this Android ID-token verification flow.
The Google button uses Credential Manager's explicit Google option with server
nonce; do not replace it with an unverified client email/profile response.

## Identifiable APK and AVD

The preserved local APK is loopback-bound: not ready to install against the
hosted service. Rebuild with the actual test path, both flags enabled, canonical
test signer and a recorded version/commit/hash under the shared Gradle lock.
Neither release nor the normal application package is enabled by these flags.

Use project `android-qa` instructions and existing QA assets. Immediately before
each run: `ssh win-hyperv "adb devices -l"`; query `adb -s <serial> emu avd name`
for every device. Record package/activity/APK hash and serial -> AVD mapping.
Use `-s` for all ADB actions. Never expose ADB 5037 to the network. Capture
screenshots and UI hierarchy before/after transitions, redacting user secrets.
Do not assume fixed 5554/5556 ownership, Google Play image/provider availability
or that passing emulator tests establishes Google vault sync durability.

Install only the separate test package; preserve normal Remanence, existing PRF
probe and Block Store entries. No `pm clear`, uninstall, reset or wipe without a
bounded fresh-install scenario explicitly agreed for the disposable test data.

## Acceptance scenarios

1. Disposable Google account -> Google consent -> handle -> system passkey
   choice -> actual PRF/readback unwrap -> Continue -> existing Create/Scan.
   Test Google Password Manager and Bitwarden independently. Google account
   selection must not force the passkey storage provider/account.
2. Cancel passkey creation, cancel assertion, disable network, rotate Activity,
   force-stop/relaunch. Account and permanent handle remain; no Home/Create/Scan
   or incoming/upload jobs are admitted until verified recovery.
3. Interrupt after credential registration, after record upload and after local
   readback. Resume pending/stored setup without replacing identity or recovery
   record. Unsupported PRF never shows ready; alternative pre-record enrollment
   is explicit and bounded. Do not delete provider credentials globally.
4. Verify handle conflict can be corrected, successful registration cannot be
   replayed, handle mutation is rejected. Existing email collision must NOT
   auto-link the Google subject to an old password account.
5. Settings: EN/RU/UK/system across Activity recreation and relaunch; immutable
   handle; readiness status; logout revokes the session and clears provider
   credential-selection state best effort, without deleting passkeys/identity.
6. Existing encrypted capsule -> clean disposable installation/other device ->
   SAME Google account sign-in -> SAME passkey from its independent provider ->
   restore SAME HPKE/Ed25519 identity -> scan and open SAME old ciphertext.
   Passkey creation or a new fixture opening alone is not acceptance.
7. Windows Chrome/Firefox/Opera and Android Chrome: test actual PRF/identity
   unwrap using the isolated `/recovery-test/`, with original device unavailable.
   This is not a full browser product. Google web login UI remains future work.

Code fallback is optional but testable for already protected accounts. A new
Google account has NO password: authenticate through Google before code restore.
If all usable provider credentials and the optional code are lost, data is lost.
Do not promise recovery solely from Google OAuth login.

## Music target preflight

On the selected machine with Python 3.13 and locked server dependencies:

```sh
uv run python scripts/music_catalog_prepare.py plan \
  --work-dir /ABSOLUTE/DEDICATED/CATALOG/DIRECTORY \
  --staging-gib 60 --index-gib 20 --rollback-gib 30 --reserve-gib 10
```

Numbers are explicit planning budgets, NOT measured full-build size estimates.
Windows may use `D:/remanence-catalog` and a single-line command. `plan` requests
small official metadata plus HEAD only; it does not create the work directory.
Only after actual disk/RAM review: action `fetch` with `--execute`. Download uses
official HTTPS plus SHA256, not GPG signature validation; failed/corrupt partials
remain for diagnosis. An interrupted process may leave a lock: inspect before
removing the exact lock file. Do not clean a disk or old snapshot automatically.

Full catalog still needs: disk-backed joins of allowlisted core tables, stable
MusicBrainz-derived IDs, measured per-table resource budgets/schema validation,
normalized staging revision, index build, validation queries including owner
songs, atomic active switch, retained previous-good revision, rollback, bounded
retention and scheduled newest-snapshot checks. These are NOT implemented by
the downloader; production search stays on the existing backend until accepted.

## Remaining public-MVP gates

Real provider/device/browser evidence, security review, lifecycle/revocation and
recovery-code reissue, explicit legacy account linking/migration, safe account
deletion/storage management and Google brand-compliant final button styling.
Settings currently shows working language/status/logout controls only. No
placeholder controls or claim that the entire public MVP is complete.
