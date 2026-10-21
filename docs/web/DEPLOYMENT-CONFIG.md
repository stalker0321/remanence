# Normal root PWA deployment configuration (DEV/TEST candidate)

The normal `web/dist` artifact contains the full app and the existing atomic
registration controller. It ships **disabled**; this is not a production security
approval. The backend's existing environment/registration gates remain authoritative.
Never mix its HTML/assets/worker with the separate `atomic-test-dist` artifact.

Serve `app-config.json` beside `index.html`, with `Content-Type: application/json`
and `Cache-Control: no-store` (no CDN cache, service-worker cache, rewrite to HTML,
redirect, cookies, or stale response). Every document fetches it anew with a 3-second
deadline and a 4096-byte limit. No config from query strings or browser storage.

Default build, or deliberately gated deployment:

```json
{"version":1,"atomicRegistration":false}
```

Explicit owner-approved target (replace all example values with deployment values):

```json
{
  "version": 1,
  "atomicRegistration": true,
  "environment": "DEV",
  "appOrigin": "https://app.example.invalid",
  "apiOrigin": "https://app.example.invalid",
  "rpId": "app.example.invalid"
}
```

Exact keys only. Version is numeric `1`; opt-in is boolean; environment is `DEV`
or `TEST`, never `PROD`. Origins must be canonical HTTPS origins with no paths,
credentials, query, fragments, or trailing slash. `appOrigin` must match the actual
document; RP must equal its host or a parent host, using the existing validator.
The existing secure loopback HTTP exception is for local synthetic testing only.
Native WebAuthn and the server still validate the RP; this config is not an RP
public-suffix security attestation. A cross-origin API needs its already-approved
CORS/proxy configuration; no browser bypass is introduced. Google client ID and
nonce come only from backend options, never this file.

The full target shape also accepts `atomicRegistration:false`: normal existing
login/restore uses that API/RP, new registration stays gated. Missing (404) config
uses the existing default API and gated account view. Invalid, cached, timed-out,
offline or mismatched config blocks account/API/provider operations and displays
a localized notice; it does not fall back to another API or legacy registration.
Static UI remains available. Config is fixed for the document lifetime; changes
apply on explicit reload, not in the middle of a draft or ceremony. On loss of
connection or controller change, new operations fail closed. No keys are replaced.

The normal service worker caches only its exact content-hashed public shell graph.
It never caches this config, API responses, keys, photos or records. A controlling
worker must be the same-base `sw.js` and answer the static-policy compatibility
message before config is loaded. This is a compatibility check, not a defense
against a malicious same-origin worker. An old/unrelated controller fails closed.
There is no force-activation, client claim, automatic reload, or destructive cache
cleanup. Finish work and close all old tabs before reopening to activate a waiting
update. Do not reload an unresolved commit just to pick up a deployment setting;
use the existing explicit UNKNOWN/recovery instructions.

The dedicated TEST HTML/config/guard remains separate and still rejects all
worker-controlled documents. Enabling normal PWA registration does not relax it.

Serving: use the entire immutable root artifact; HTML/manifest/worker and config
must be revalidated or no-store (config strictly no-store); hashed assets may be
immutable. Preserve existing legal/association routes and existing API proxy.
Deploy/configure/rollback only through the designated deployment owner. Disabling
config affects new documents, not an instant global revocation; server gates remain
the authority. Real providers, native devices and public production acceptance are
separate gates.
