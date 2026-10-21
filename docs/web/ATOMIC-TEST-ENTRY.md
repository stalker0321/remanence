# Atomic registration — separate TEST entry

T418 builds the existing T410 atomic controller/App with the **real** existing
Google Identity Services and WebAuthn PRF browser adapters. The ordinary
`web/index.html`, `src/main.tsx`, controller/crypto/22e08 contract, server flags
and public registration gate are unchanged. No provider-success fixture is
included in this entry. TEST is an explicit operator selection, not a claim
that a remote backend has been independently identified as TEST.

## Build artifact, not deployment

Use the installed pinned tools/dependencies, from the repository's `web/`:

```sh
npm run typecheck
npm run build:atomic-test
```

Output: `web/atomic-test-dist/`. This is a separate root; `npm run build`
still builds the ordinary gated app. `vite.atomic-test.config.ts` refuses
any mode except `atomic-test`. An optional `-- --base=/approved-test-path/`
selects an absolute same-origin directory, not a CDN or a query flag.
The standalone TEST entry deliberately does not register a service worker.
No automatic preview/listener/deployment command is part of this build.

The artifact includes `atomic-test-config.json` with
`{"environment":"UNCONFIGURED"}`. This is deliberately **closed**, not a
working backend default or a fabricated OAuth ID. On a separately approved
TEST hosting copy, provide the following **public** JSON fields before serving:

| Exact name | Required value / existing source |
| --- | --- |
| `environment` | Exactly `TEST`; missing/other fails before App/API/providers. |
| `appOrigin` | Exact approved page origin, including nondefault port, no path/trailing slash/query/credentials. HTTPS, or HTTP loopback for local tests. Runtime location must equal it. |
| `apiOrigin` | Explicit approved API origin, same rules. Both atomic setup and ordinary authenticated recovery/readback use this **same** target. HTTPS page cannot use HTTP API. No implicit production or legacy fallback. |
| `rpId` | Existing backend `REMANENCE_RECOVERY_RP_ID`, matching the page host or its RP parent domain. Received create/get options must match it before the credential prompt. The browser still performs native RP validation. |

The file is fetched from the built base directory, same origin, no-store,
omitted cookies, no redirects; at most4096 bytes and exact field names.
Serve `application/json`. It is static public configuration, **not** a secret
store or an auth credential. Do not put OAuth secrets, tokens or recovery
material in it. No URL/localStorage switch can enable the normal entry.
Do not serve this on an origin controlled by an unrelated existing worker.
The entry refuses an already controlled document before config/API/provider use
(and checks again after loading config). It never unregisters workers or clears
their caches. Use a separate TEST origin, including a distinct loopback port.
TEST settings explicitly report online-only operation in EN/RU/UK; network
status still follows online/offline events without registering an offline shell.

There is **no new Google ID variable**: the existing backend
`POST /v1/auth/google/options` returns `client_id` from its inspected/configured
`REMANENCE_GOOGLE_CLIENT_IDS[0]`; the adapter forwards that public ID and the
server nonce unchanged to GIS. Invalid/unconfigured ID shape is a clear error
before GIS. Use only the already approved public client ID with the page's
authorized JavaScript origin; do not invent an ID, change OAuth, or use a
different target just to make a prompt appear.

Google authenticates the account. Credential creation/assertion separately
uses the browser's existing WebAuthn selection UI; this code does not force a
Google storage provider or couple it to the selected Google account. Actual
PRF is required. Missing PRF/unsupported WebAuthn/cancel remain explicit failures,
never fallback key generation or unsafe old `/google/register`.

## Existing backend prerequisites — not applied by this task

- Frozen corrected server918bd956/full T410 server subtree, additive migration
 0018 already installed by a separately authorized TEST operator; no live DB work.
- Mode DEV/TEST, existing `REMANENCE_GOOGLE_SIGN_IN_ENABLED` and
 `REMANENCE_RECOVERY_ENABLED`, actual approved public client IDs/presenter IDs,
 `REMANENCE_RECOVERY_RP_ID` and exact `REMANENCE_RECOVERY_ORIGINS` for this page.
- To admit **new** accounts, the existing explicit
 `REMANENCE_REGISTRATION_ATOMIC_REQUIRED` and
 `REMANENCE_REGISTRATION_ATOMIC_ADMISSION_ENABLED` gates must already be set
 under appropriate authority. Default OFF is not changed here. Closed
 admission returns403; a latched legacy fence is never cleared by this client.
 Existing login/resume/readback are not a reason to enable new admission.
- API transport must be reachable from the page. Same-origin routing or an
 already authorized cross-origin CORS policy must allow existing JSON/profile/
 authorization headers. Do not alter a server/proxy configuration in this task.
- Host CSP must already permit the existing GIS script/frame behavior and
 selected API; native secure-context/WebAuthn/RP/origin rules still apply.
 A public static flag is not authorization to activate PROD/BETA.

Open the approved artifact's `#/account`. Normal path: Google → handle → real
credential/PRF → atomic finalize → actual stored record GET/unwrap → READY.
Existing Google identity: SIGNED_IN → existing passkey restore, **no new key
bundle/create/finalize**. Refresh deliberately drops RAM and requires login/
restore again. UNKNOWN and expired410 semantics are the existing controller's,
not a new recovery protocol. Unsupported configuration is shown in an English
TEST diagnostic; product journey strings remain the existing EN/RU/UK catalog.

## Bounded verification / handoff

`test/atomic-test-entry.test.ts` covers config/adapter boundaries and untouched
normal entry. `test/browser/atomicTestEntryCheck.mjs` serves the **actual two
built artifacts** on temporary loopback fixtures, with a separately targeted
synthetic HTTP API and explicit Google/global native-credential doubles.
Existing browser adapters, WebAuthn serialization, actual App/controller and
generation/sealing/GET unwrap run unchanged. It covers normal READY/readback
barrier, fresh-document existing-account restore, missing PRF/cancel, wrong RP,
invalid Google ID, closed admission, absent/wrong origin config and public gate.
No valid Google token or real WebAuthn credential is claimed by that fixture.

Real providers, OAuth permission/origin readiness, credential-store custody/
synchronization, two devices, Safari/native/PWA/install and actual backend/PG
for this new entry are **not verified here**. Historical T412 proves the
unchanged controller's bounded synthetic-provider actual-stack behavior; its
test counts are not rerun or added to T418. No T390/T399 harness is involved.
For a later authorized manual session use the small T414 checklist and verify
original bundle/key recovery, not merely a successful Google sign-in.
