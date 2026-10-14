// Test-only virtual-credential client shim (T190). Small extension of the
// existing browser-e2e CDP tooling — NOT a QA framework.
//
// An Android TEST client POSTs genuine production WebAuthn options; this
// shim runs them against a real headless-Chromium virtual CTAP2
// authenticator (resident/UV/verified, hasPrf:true) on the fixed
// sidecar origin page and returns the genuine serialized response with
// genuine PRF results nested at
// credential.clientExtensionResults.prf.results.first (32-byte first
// only; omitted when unsupported). Native PasskeyRecovery strips the
// extension secret before the production recovery API; no
// bearer/account-private keys ever reach this shim.
//
// Bounds: fixed bind 127.0.0.1:18044; bounded bodies with abort (no
// response is claimed on abort); explicit server header/request
// timeouts; one ceremony at a time held until the underlying ceremony
// REALLY settles (a timed-out caller gets 504 while later callers get
// 503 — no overlapping ceremonies); fixed ceremony code only (no
// caller JS eval/RPC); the page may reach ONLY the fixed sidecar
// origin. All keys/PRF live in process memory; nothing is exported,
// logged, or written to disk. JS immutable strings cannot be reliably
// zeroized — ArrayBuffers are cleared best-effort and this is
// documented, not claimed.
// Evidence class: CHROMIUM_VIRTUAL_AUTHENTICATOR, GPM/native sync NOT_TESTED.
import http from 'node:http';
import {fileURLToPath} from 'node:url';

export const BIND_HOST = '127.0.0.1';
export const BIND_PORT = 18044;
export const SIDECAR_ORIGIN = 'http://localhost:18043';
export const RECOVERY_PAGE = SIDECAR_ORIGIN + '/recovery-test/';
export const RP_ID = 'localhost';
export const MAX_BODY_BYTES = 65536;
export const REQUEST_TIMEOUT_MS = 60000;
export const HEADERS_TIMEOUT_MS = 10000;

export const ERROR_CODES = Object.freeze({
  NOT_FOUND: 'NOT_FOUND',
  NONLOCAL_REFUSED: 'NONLOCAL_REFUSED',
  TOO_LARGE: 'TOO_LARGE',
  BAD_JSON: 'BAD_JSON',
  BAD_SHAPE: 'BAD_SHAPE',
  RP_SCOPE_REFUSED: 'RP_SCOPE_REFUSED',
  CEREMONY_BUSY: 'CEREMONY_BUSY',
  CEREMONY_TIMEOUT: 'CEREMONY_TIMEOUT',
  CEREMONY_FAILED: 'CEREMONY_FAILED',
  PROVIDER_UNAVAILABLE: 'PROVIDER_UNAVAILABLE',
});

export function errorBody(code) {
  if (!Object.hasOwn(ERROR_CODES, code)) throw Error('UNKNOWN_ERROR_CODE');
  return {error: code};
}

export function isLoopbackPeer(address) {
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
}

// Pure route allowlist (testable without a browser).
export function routeFor(method, pathname) {
  if (method === 'GET' && pathname === '/health') return 'health';
  if (method === 'POST' && pathname === '/create') return 'create';
  if (method === 'POST' && pathname === '/get') return 'get';
  return null;
}

// Wrong RP/options origin scope is refused before any ceremony.
export function checkOptionsScope(kind, options) {
  if (!options || typeof options !== 'object' || Array.isArray(options)) return 'BAD_SHAPE';
  if (typeof options.challenge !== 'string' || options.challenge.length === 0) return 'BAD_SHAPE';
  if (kind === 'create') {
    if (!options.rp || typeof options.rp !== 'object' || options.rp.id !== RP_ID) return 'RP_SCOPE_REFUSED';
    return null;
  }
  if (kind === 'get') {
    if (options.rpId !== undefined && options.rpId !== RP_ID) return 'RP_SCOPE_REFUSED';
    return null;
  }
  return 'BAD_SHAPE';
}

// Pure PRF nesting for the {credential} wire (unit-tested): a genuine
// 32-byte first (43-char unpadded base64url) nests under
// credential.clientExtensionResults.prf.results.first; anything else
// (absent/empty/malformed) leaves the credential untouched — never
// hardcoded or synthesized. The in-page ceremony passes only a
// byteLength-checked real first (kept in sync; pinned by the
// source-shape check in the .test.mjs).
export function nestPrf(credential, firstB64) {
  if (typeof firstB64 !== 'string' || firstB64.length !== 43
      || !/^[A-Za-z0-9_-]{43}$/.test(firstB64)) return credential;
  return {
    ...credential,
    clientExtensionResults: {prf: {results: {first: firstB64}}},
  };
}

// True one-ceremony-at-a-time serialization (unit-tested, no browser):
// `busy` is held until the underlying work REALLY settles. A timeout
// rejects the caller with CEREMONY_TIMEOUT while later callers keep
// getting CEREMONY_BUSY — no overlapping ceremonies/challenges ever.
export function createSerializer() {
  let busy = false;
  let generation = 0;
  return {
    isBusy: () => busy,
    async run(work, timeoutMs) {
      if (busy) throw Error(ERROR_CODES.CEREMONY_BUSY);
      busy = true;
      const my = ++generation;
      const release = () => { if (generation === my) busy = false; };
      let timer;
      try {
        return await Promise.race([
          Promise.resolve().then(work).then(
            value => { release(); return value; },
            err => { release(); throw err; },
          ),
          new Promise((_, reject) => {
            timer = setTimeout(() => reject(Error(ERROR_CODES.CEREMONY_TIMEOUT)), timeoutMs);
          }),
        ]);
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

// Explicit server bounds (unit-tested on an unbound server object):
// slow headers/bodies are killed by the server itself, and socket
// errors are destroyed coarsely with nothing logged.
export function applyServerBounds(server) {
  server.headersTimeout = HEADERS_TIMEOUT_MS;
  server.requestTimeout = REQUEST_TIMEOUT_MS + 5000;
  server.on('clientError', (err, socket) => {
    try { socket.destroy(); } catch { /* best effort */ }
  });
  return server;
}

const AUTHENTICATOR_OPTIONS = Object.freeze({
  protocol: 'ctap2', transport: 'usb', hasResidentKey: true, hasUserVerification: true,
  isUserVerified: true, automaticPresenceSimulation: true, hasPrf: true,
  defaultBackupEligibility: true, defaultBackupState: true,
});

let browser = null;
let page = null;

function failStartup(code, detail) {
  process.stderr.write(`pending-credential-client: refused: ${code}${detail ? ' ' + detail : ''}\n`);
  process.exit(2);
}

async function launchBrowser() {
  const modulePath = process.env.REMANENCE_PLAYWRIGHT_MODULE;
  if (!modulePath) failStartup(ERROR_CODES.PROVIDER_UNAVAILABLE, '(set REMANENCE_PLAYWRIGHT_MODULE)');
  let chromium;
  try {
    ({chromium} = await import(modulePath));
  } catch {
    failStartup(ERROR_CODES.PROVIDER_UNAVAILABLE, '(playwright module unreadable)');
  }
  const launchOptions = {headless: true};
  if (process.env.BROWSER_EXECUTABLE) launchOptions.executablePath = process.env.BROWSER_EXECUTABLE;
  try {
    browser = await chromium.launch(launchOptions);
    const context = await browser.newContext();
    page = await context.newPage();
    const cdp = await context.newCDPSession(page);
    await cdp.send('WebAuthn.enable');
    await cdp.send('WebAuthn.addVirtualAuthenticator', {options: {...AUTHENTICATOR_OPTIONS}});
    // No browser network destinations beyond the fixed sidecar origin.
    await page.route('**/*', route => {
      try {
        if (new URL(route.request().url()).origin === SIDECAR_ORIGIN) route.continue();
        else route.abort();
      } catch {
        route.abort();
      }
    });
    await page.goto(RECOVERY_PAGE);
  } catch {
    failStartup(ERROR_CODES.PROVIDER_UNAVAILABLE, '(browser launch failed)');
  }
}

// Fixed ceremony code only. `kind` is 'create'|'get' (never caller JS).
// Returns {credential} with genuine PRF nested at
// credential.clientExtensionResults.prf.results.first — 32-byte first
// only, omitted when unsupported. Mirrors exported nestPrf (kept in
// sync; pinned by the source-shape check in the .test.mjs).
async function ceremony(kind, options) {
  const result = await page.evaluate(async ({kind, options}) => {
    const {credentialOptions, publicCredential, b64} = await import('./crypto.mjs');
    const created = kind === 'create'
      ? await navigator.credentials.create({publicKey: credentialOptions(options)})
      : await navigator.credentials.get({publicKey: credentialOptions(options)});
    const credential = publicCredential(created);
    const first = created.getClientExtensionResults().prf?.results?.first;
    if (first && first.byteLength === 32) {
      credential.clientExtensionResults = {prf: {results: {first: b64(first)}}};
      try { new Uint8Array(first).fill(0); } catch { /* best effort */ }
    }
    return {credential};
  }, {kind, options});
  if (!result || typeof result !== 'object' || !result.credential || typeof result.credential !== 'object') {
    throw Error(ERROR_CODES.CEREMONY_FAILED);
  }
  return result;
}

const serializer = createSerializer();

function readBoundedBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let aborted = false;
    req.on('data', chunk => {
      if (aborted) return;
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        // Oversize: abort the connection with no response body. No 413
        // is claimed: destroying the socket prevents receiving (and
        // answering) reliably.
        aborted = true;
        try { req.destroy(); } catch { /* best effort */ }
        reject(Error(ERROR_CODES.TOO_LARGE));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (!aborted) resolve(Buffer.concat(chunks).toString('utf8'));
    });
    req.on('error', () => reject(Error(ERROR_CODES.BAD_JSON)));
    req.on('close', () => { if (!aborted) reject(Error(ERROR_CODES.BAD_JSON)); });
  });
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'});
  res.end(payload);
}

async function handleRequest(req, res) {
  if (!isLoopbackPeer(req.socket.remoteAddress)) {
    sendJson(res, 403, errorBody(ERROR_CODES.NONLOCAL_REFUSED));
    return;
  }
  let pathname;
  try {
    pathname = new URL(req.url, `http://${BIND_HOST}:${BIND_PORT}`).pathname;
  } catch {
    sendJson(res, 404, errorBody(ERROR_CODES.NOT_FOUND));
    return;
  }
  const route = routeFor(req.method, pathname);
  if (route === null) {
    sendJson(res, 404, errorBody(ERROR_CODES.NOT_FOUND));
    return;
  }
  if (route === 'health') {
    sendJson(res, 200, {
      ready: true,
      evidenceClass: 'CHROMIUM_VIRTUAL_AUTHENTICATOR',
      realProviderSync: 'NOT_TESTED',
    });
    return;
  }
  let parsed;
  try {
    parsed = JSON.parse(await readBoundedBody(req));
  } catch (err) {
    if (err.message === ERROR_CODES.TOO_LARGE) return; // aborted: no body claimed
    sendJson(res, 400, errorBody(ERROR_CODES.BAD_JSON));
    return;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)
      || Object.keys(parsed).length !== 1 || !parsed.options || typeof parsed.options !== 'object') {
    sendJson(res, 400, errorBody(ERROR_CODES.BAD_SHAPE));
    return;
  }
  const scopeError = checkOptionsScope(route, parsed.options);
  if (scopeError !== null) {
    sendJson(res, scopeError === ERROR_CODES.RP_SCOPE_REFUSED ? 422 : 400, errorBody(scopeError));
    return;
  }
  try {
    sendJson(res, 200, await serializer.run(() => ceremony(route, parsed.options), REQUEST_TIMEOUT_MS));
  } catch (err) {
    const code = Object.hasOwn(ERROR_CODES, err.message) ? err.message : ERROR_CODES.CEREMONY_FAILED;
    sendJson(res, code === ERROR_CODES.CEREMONY_TIMEOUT ? 504 : code === ERROR_CODES.CEREMONY_BUSY ? 503 : 500,
      errorBody(code));
  }
}

async function shutdown() {
  try { await browser?.close(); } catch { /* best effort */ }
  process.exit(0);
}

async function start() {
  await launchBrowser();
  const server = applyServerBounds(http.createServer((req, res) => {
    handleRequest(req, res).catch(() => {
      try { sendJson(res, 500, errorBody(ERROR_CODES.CEREMONY_FAILED)); } catch { /* best effort */ }
    });
  }));
  server.listen(BIND_PORT, BIND_HOST, () => {
    process.stdout.write(JSON.stringify({
      ready: true, bind: `${BIND_HOST}:${BIND_PORT}`,
      evidenceClass: 'CHROMIUM_VIRTUAL_AUTHENTICATOR', realProviderSync: 'NOT_TESTED',
    }) + '\n');
  });
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await start();
}
