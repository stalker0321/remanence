# Static shell and installation

This source provides an installable public shell. A cold offline launch does
not restore a session, unlock keys, run OpenCV, decrypt a postcard, or send.
Tokens, keys, drafts and decrypted photos retain their existing in-memory
lifetimes. Only the existing language preference is stored in localStorage.

## Build and inspect

Use the existing pinned dependencies:

```sh
npm run typecheck
npm test
npm run build
npx vite build --base=/test-prefix/ --outDir dist-prefix
```

`--base` is authoritative over `REMANENCE_STATIC_BASE`. The worker requires an
absolute same-origin directory path (`/` or `/test-prefix/`), not a CDN URL or
relative `./` base. API origin and WebAuthn RP/origin are separate settings.

`dist/shell-manifest.json` records every allowed URL, MIME and SHA-256; it is
diagnostic output, not fetched by the worker. `dist/sw.js` embeds that exact
manifest. Version derives from the sorted file hashes, base and worker code.
All emitted JS chunks (including dynamic dependencies), graph styles/assets,
exact CSS-referenced public fonts, built HTML, boot script, manifest and brand
icons are included. OpenCV JS/WASM and license files remain online resources;
their vendored bytes are unchanged. No glob over the public directory is used.

## Cache and lifecycle contract

Each origin's CacheStorage contains separate
`remanence-static-<SHA256(base)>-<version>` caches. Installation fetches the
explicit graph with omitted credentials, redirect rejection and HTTP-cache
bypass. It verifies same-origin exact response URL, status 200, basic response
type, expected MIME and SHA-256. Only absent Vary or Accept-Encoding variance
is accepted. It validates the whole graph before opening the version cache.
Any cache-write failure removes only a newly created partial version; rejection
leaves the old active worker intact.

The fetch handler reads only exact same-origin GET allowlist URLs without
queries, authorization or range headers. `/` (or the exact prefix directory)
aliases that version's built index.html; hash routes use that document.
Unknown routes have no SPA/offline catch-all. API/auth/recovery/music/blob/
photo/artwork/capsule/OpenCV paths, other origins and non-GET requests bypass
the handler. Runtime fetch responses never enter CacheStorage.

The worker registers after page load with its base scope and
`updateViaCache: none`. Registration failure leaves the online app usable.
No skipWaiting, clients.claim, reload, background sync or new account storage
is used. A waiting update explains how to finish work and close all app
windows/tabs; unsent drafts are lost on closing under the existing memory-only
contract. Activation prunes only exact old versions of its own base namespace.
Other app caches, another base, IndexedDB and localStorage are untouched.
Eviction falls back to ordinary network and is not a durability promise.

Install controls appear only after the browser supplies a beforeinstallprompt
capability, consumed once by an explicit action. Dismissal/errors do not affect
the app. Other browsers use their own installation/share menu where available;
there is no universal native prompt claim. appinstalled or standalone display
is reflected separately from merely accepting a prompt.

## Future host deployment requirements (not applied by this task)

- HTTPS (loopback is only for tests), correct same-origin static base and MIME.
- Serve sw.js with `Cache-Control: no-cache` and JavaScript MIME. Do not redirect
  it or widen its scope using Service-Worker-Allowed.
- Serve index.html/boot/manifest/icons consistently with the graph. No injected
  per-user HTML, nonce rewrites, edge script injection or auth-varying responses:
  exact build hashes deliberately reject them.
- Permit same-origin workers in CSP (`worker-src 'self'`) and retain the
  existing script/style/font and provider policy. Never loosen API policy.
- Deploy the complete graph atomically; retain prior hashed assets while old
  tabs may remain open (including the first uncontrolled visit). Publish the
  new sw.js last. A missing/wrong/redirected asset rejects installation.
- API/auth/recovery retain `Cache-Control: no-store` and existing transport
  behavior. A shell response is never an offline API substitute.
- Verify root and any prefix separately, including a prefix-only server whose
  root paths return 404. Browser quota/eviction and installation support vary.

Local reproduction on the assigned pinned test host:

```sh
node test/browser/installShellCheck.mjs "$PWD" /new/unique/h01-proof
node test/browser/staticShellCheck.mjs "$PWD" /new/unique/sw-proof
```

The second runner uses real production builds and CacheStorage in Chromium.
Its version markers, network faults, unrelated storage markers and synthetic
install events live only in the loopback fixture. Synthetic events check UI
behavior, not actual OS installation. Preserve failing proof directories.

Sources: [Service Workers specification](https://w3c.github.io/ServiceWorker/)
(living editor draft, not a universal shipped-feature claim),
[Chrome lifecycle](https://developer.chrome.com/docs/workbox/service-worker-lifecycle),
[Vite plugin API](https://vite.dev/guide/api-plugin),
[beforeinstallprompt](https://developer.mozilla.org/en-US/docs/Web/API/Window/beforeinstallprompt_event).
