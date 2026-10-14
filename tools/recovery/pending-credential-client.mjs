// Test-only virtual-credential client shim (T190). Small extension of the
// existing browser-e2e CDP tooling — NOT a QA framework.
//
// An Android TEST client POSTs genuine production WebAuthn options; this
// shim runs them against a real headless-Chromium virtual CTAP2
// authenticator (resident/UV/verified, hasPrf:true) on the fixed
// sidecar origin page and returns the genuine serialized response
// (plus only genuine PRF bytes, base64url, when present). Native
// PasskeyRecovery strips the extension secret before the production
// recovery API; no bearer/account-private keys ever reach this shim.
//
// Bounds: fixed bind 127.0.0.1:18044; bounded bodies; per-request
// timeout; one ceremony at a time (concurrent callers get a coarse
// 503 and retry); fixed ceremony code only (no caller JS eval/RPC);
// the page may reach ONLY the fixed sidecar origin. All keys/PRF live
// in process memory; nothing is exported, logged, or written to disk.
// JS immutable strings cannot be reliably zeroized — ArrayBuffers are
// cleared best-effort and this is documented, not claimed.
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

const AUTHENTICATOR_OPTIONS = Object.freeze({
  protocol: 'ctap2', transport: 'usb', hasResidentKey: true, hasUserVerification: true,
  isUserVerified: true, automaticPresenceSimulation: true, hasPrf: true,
  defaultBackupEligibility: true, defaultBackupState: true,
});

let browser = null;
let page = null;
let busy = false;

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
// Returns {credential} plus `prf` (base64url) ONLY when a genuine
// 32-byte PRF first is present — never hardcoded/synthesized.
async function ceremony(kind, options) {
  const result = await page.evaluate(async ({kind, options}) => {
    const {credentialOptions, publicCredential, b64} = await import('./crypto.mjs');
    const created = kind === 'create'
      ? await navigator.credentials.create({publicKey: credentialOptions(options)})
      : await navigator.credentials.get({publicKey: credentialOptions(options)});
    const out = {credential: publicCredential(created)};
    const first = created.getClientExtensionResults().prf?.results?.first;
    if (first && first.byteLength === 32) {
      out.prf = b64(first);
      try { new Uint8Array(first).fill(0); } catch { /* best effort */ }
    }
    return out;
  }, {kind, options});
  if (!result || typeof result !== 'object' || !result.credential || typeof result.credential !== 'object') {
    throw Error(ERROR_CODES.CEREMONY_FAILED);
  }
  if (result.prf !== undefined && typeof result.prf !== 'string') throw Error(ERROR_CODES.CEREMONY_FAILED);
  return result;
}

function withTimeout(promise) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(Error(ERROR_CODES.CEREMONY_TIMEOUT)), REQUEST_TIMEOUT_MS);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function readBoundedBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        req.destroy();
        reject(Error(ERROR_CODES.TOO_LARGE));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', () => reject(Error(ERROR_CODES.BAD_JSON)));
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
    sendJson(res, err.message === ERROR_CODES.TOO_LARGE ? 413 : 400,
      errorBody(err.message === ERROR_CODES.TOO_LARGE ? ERROR_CODES.TOO_LARGE : ERROR_CODES.BAD_JSON));
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
  if (busy) {
    sendJson(res, 503, errorBody(ERROR_CODES.CEREMONY_BUSY));
    return;
  }
  busy = true;
  try {
    // The page call below cannot be cancelled mid-flight; on timeout we
    // respond 504 and release the mutex while the orphaned ceremony
    // resolves harmlessly in the page (virtual UV never hangs in practice).
    sendJson(res, 200, await withTimeout(ceremony(route, parsed.options)));
  } catch (err) {
    const code = Object.hasOwn(ERROR_CODES, err.message) ? err.message : ERROR_CODES.CEREMONY_FAILED;
    sendJson(res, code === ERROR_CODES.CEREMONY_TIMEOUT ? 504 : 500, errorBody(code));
  } finally {
    busy = false;
  }
}

async function shutdown() {
  try { await browser?.close(); } catch { /* best effort */ }
  process.exit(0);
}

async function start() {
  await launchBrowser();
  const server = http.createServer((req, res) => {
    handleRequest(req, res).catch(() => {
      try { sendJson(res, 500, errorBody(ERROR_CODES.CEREMONY_FAILED)); } catch { /* best effort */ }
    });
  });
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
