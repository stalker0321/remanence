/** Actual built TEST root + real GIS/WebAuthn adapter code + real client crypto.
 * Google/global navigator credentials and HTTP backend below are EXPLICIT
 * synthetic boundaries. No server verification, real provider or PG claim. */
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { join, resolve, extname } from 'node:path'

const [webPath, proof] = process.argv.slice(2), web = resolve(webPath)
await mkdir(proof, { recursive: true })
const requireTool = createRequire('/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')
const executable = '/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const executableSha256 = createHash('sha256').update(await readFile(executable)).digest('hex')
assert.equal(executableSha256, '4fa61f0ba0b4f89b7bec242c32ca16d3bccf69dbfef0651924a9ba8fede5f9d6')
assert.deepEqual(JSON.parse(await readFile(join(web, 'atomic-test-dist/atomic-test-config.json'), 'utf8')), { environment: 'UNCONFIGURED' })
const checks = [], errors = [], images = [], wire = []
const check = (name, ok) => { assert.ok(ok, name); checks.push({ name, ok: true }) }
const account = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', reservationId = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const flow = '11111111-1111-4111-8111-111111111111', credential = Buffer.alloc(16, 9).toString('base64url')
const deadline = '2031-01-01T00:00:00Z', clientId = 'synthetic-t418.apps.googleusercontent.com'
let saved = null, commits = 0, recordRelease = null, recordArrived = null, holdRecord = false, wrongRp = false, badClient = false, admissionClosed = false
let configMode = 'valid', appOrigin = '', apiOrigin = '', activeContexts = []
const reservation = () => ({ id: reservationId, account_id: account, state: saved ? 'ACTIVE' : 'RESERVED', epoch: 1,
  expires_at: deadline, credential_ids: verifiedCredential ? [credential] : [], outcome: null, restarted_into: null })
let verifiedCredential = false
const login = () => ({ user: { user_id: account, handle: saved.handle }, active_key_bundle: saved.key_bundle,
  session_id: flow, access_token: 'pm_at_synthetic', refresh_token: 'pm_rt_synthetic', access_expires_at: deadline, refresh_expires_at: deadline })
function options(create) {
  return { challenge: Buffer.alloc(32, 3).toString('base64url'), userVerification: 'required',
    ...(create ? { rp: { id: wrongRp ? 'wrong.invalid' : '127.0.0.1', name: 'Synthetic TEST' },
      user: { id: Buffer.from(account.replaceAll('-', ''), 'hex').toString('base64url'), name: 'synthetic', displayName: 'Synthetic' },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }], authenticatorSelection: { residentKey: 'required', userVerification: 'required' } }
      : { rpId: wrongRp ? 'wrong.invalid' : '127.0.0.1', allowCredentials: [{ id: credential, type: 'public-key' }], extensions: { prf: { eval: { first: Buffer.alloc(32, 4).toString('base64url') } } } }) }
}
async function api(req, res) {
  res.setHeader('access-control-allow-origin', appOrigin)
  res.setHeader('access-control-allow-headers', 'content-type,authorization,x-remanence-profile-version')
  res.setHeader('access-control-allow-methods', 'GET,POST,OPTIONS')
  res.setHeader('cache-control', 'no-store')
  if (req.method === 'OPTIONS') { res.writeHead(204).end(); return }
  try {
    const chunks = []; for await (const chunk of req) chunks.push(chunk)
    const raw = Buffer.concat(chunks).toString(), body = raw ? JSON.parse(raw) : {}
    const path = new URL(req.url, apiOrigin).pathname
    wire.push({ method: req.method, path, profile: req.headers['x-remanence-profile-version'], atomicVersion: body.onboarding_version ?? null })
    assert.equal(req.headers['x-remanence-profile-version'], '2')
    let result = {}, status = 200
    if (path === '/v1/auth/google/options') result = { flow, nonce: 'A'.repeat(43), client_id: badClient ? 'unconfigured' : clientId }
    else if (path === '/v1/auth/google/verify') {
      assert.equal(body.id_token, 'synthetic-Google-token'); assert.equal(body.nonce, 'A'.repeat(43))
      if (saved) result = { status: 'SIGNED_IN', login: login() }
      else if (body.onboarding_version === undefined) result = { status: 'NEEDS_HANDLE', flow, registration_token: 'A'.repeat(43) }
      else if (admissionClosed) { status = 403; result = { reason: 'REGISTRATION_ADMISSION_CLOSED' } }
      else { assert.equal(body.onboarding_version, 1); result = { status: 'SETUP_REQUIRED', reservation: reservation(), setup_token: `rgs_${'A'.repeat(43)}`, setup_session_id: flow, setup_expires_at: deadline } }
    } else if (path.startsWith('/v2/registration/')) {
      assert.equal(req.headers.authorization, `Bearer rgs_${'A'.repeat(43)}`)
      const action = path.slice(`/v2/registration/reservations/${reservationId}`.length).replace(/^\//, '')
      if (!action) result = { reservation: reservation() }
      else if (action === 'credential/options' || action === 'setup/options') result = { flow, options: options(action === 'credential/options'), expires_at: deadline }
      else if (action === 'credential/verify' || action === 'setup/verify') {
        assert.deepEqual(body.response.clientExtensionResults, {}); assert.equal(body.response.id, credential)
        assert.equal(body.response.rawId, credential); assert.equal(body.response.type, 'public-key')
        assert.equal(body.response.response.clientDataJSON, 'e30')
        if (action === 'credential/verify') { verifiedCredential = true; result = { credential_id: credential, registered: true, recoverable: false } }
        else result = { credential_id: credential, authentication_verified: true, final_authorization: `rgf_${'A'.repeat(43)}`, expires_at: deadline }
      } else if (action === 'finalize') {
        assert.equal(saved, null); saved = structuredClone(body); commits++; status = 201
        result = { status: 'ACTIVE', outcome: { operation_id: body.operation_id, account_id: account, bundle_id: body.key_bundle.key_bundle_id, credential_id: credential, generation: 1, handle: body.handle }, login: login() }
      } else if (action === 'leave') result = { left: true }
      else throw new Error(`Unexpected TEST action ${action}`)
    } else if (path.startsWith('/v1/recovery/')) {
      assert.equal(req.headers.authorization, 'Bearer pm_at_synthetic'); assert.ok(saved)
      if (path.endsWith('/status')) result = { state: 'STORED', credential_id: credential }
      else if (path.endsWith('/setup/options')) result = { flow, options: options(false) }
      else if (path.endsWith('/setup/verify')) { assert.deepEqual(body.response.clientExtensionResults, {}); result = { credential_id: credential, authentication_verified: true } }
      else if (path.endsWith('/record')) {
        assert.equal(req.method, 'GET')
        if (holdRecord) { recordArrived?.(); await new Promise(done => { recordRelease = done }) }
        result = { record: saved.record, encryption_public_keyset: saved.key_bundle.encryption_public_keyset, signing_public_keyset: saved.key_bundle.signing_public_keyset }
      } else throw new Error('Unexpected recovery request')
    } else throw new Error(`Unexpected API request ${path}`)
    res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(result))
  } catch (error) { errors.push(String(error)); res.writeHead(500).end() }
}
const apiServer = createServer(api)
await new Promise(done => apiServer.listen(0, '127.0.0.1', done)); apiOrigin = `http://127.0.0.1:${apiServer.address().port}`
const staticRequests = []
const appServer = createServer(async (req, res) => {
  const path = new URL(req.url, 'http://fixture.invalid').pathname; staticRequests.push(path)
  if (path.startsWith('/v1/')) return api(req, res) // normal entry negative control, same-origin API convention
  if (path === '/atomic-test-config.json') {
    const config = configMode === 'unconfigured' ? { environment: 'UNCONFIGURED' }
      : { environment: 'TEST', appOrigin: configMode === 'wrong-origin' ? 'https://wrong.invalid' : appOrigin, apiOrigin, rpId: '127.0.0.1' }
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify(config)); return
  }
  const relative = path === '/' ? 'index.html' : path.replace(/^\/normal\/?/, '').replace(/^\//, '') || 'index.html'
  for (const output of path.startsWith('/normal') ? ['dist'] : ['atomic-test-dist', 'dist']) {
    const root = join(web, output), file = resolve(root, relative)
    if (!file.startsWith(`${root}/`)) break
    try {
      const bytes = await readFile(file)
      res.writeHead(200, { 'content-type': { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.wasm': 'application/wasm', '.css': 'text/css', '.otf': 'font/otf', '.svg': 'image/svg+xml', '.png': 'image/png' }[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' }).end(bytes); return
    } catch { /* Try the other immutable build graph; never SPA fallback. */ }
  }
  res.writeHead(404).end()
})
await new Promise(done => appServer.listen(0, '127.0.0.1', done)); appOrigin = `http://127.0.0.1:${appServer.address().port}`
const browser = await chromium.launch({ executablePath: executable, headless: true })
let complete = false
async function fresh(path = '/') {
  const context = await browser.newContext({ viewport: { width: 390, height: 900 }, serviceWorkers: 'block' })
  activeContexts.push(context)
  await context.addInitScript(({ id, expectedClient }) => {
    const state = { google: 0, create: 0, get: 0, noPrf: false, cancel: false, decoded: true }
    window.__t418Providers = state
    let initialized
    window.google = { accounts: { id: {
      initialize(options) { if (options.client_id !== expectedClient || options.nonce !== 'A'.repeat(43)) throw new Error('Wrong GIS configuration'); initialized = options },
      disableAutoSelect() {}, cancel() {}, prompt() { state.google++; initialized.callback({ credential: 'synthetic-Google-token' }) },
    } } }
    const bytes = (n, value) => new Uint8Array(n).fill(value).buffer
    const reply = create => ({ id, rawId: bytes(16, 9), type: 'public-key',
      response: create ? { clientDataJSON: new TextEncoder().encode('{}').buffer, attestationObject: bytes(8, 2), getTransports: () => ['internal'] }
        : { clientDataJSON: new TextEncoder().encode('{}').buffer, authenticatorData: bytes(37, 3), signature: bytes(64, 4), userHandle: null },
      getClientExtensionResults: () => create || state.noPrf ? {} : { prf: { results: { first: bytes(32, 7) } } },
    })
    const read = async (options, create) => {
      if (create) state.create++; else state.get++
      const pk = options.publicKey
      state.decoded &&= pk.challenge instanceof Uint8Array && (create ? pk.user.id instanceof Uint8Array : pk.allowCredentials[0].id instanceof Uint8Array && pk.extensions.prf.eval.first instanceof Uint8Array)
      if (state.cancel) throw new DOMException('synthetic user cancel', 'NotAllowedError')
      return reply(create)
    }
    Object.defineProperty(navigator, 'credentials', { value: { create: o => read(o, true), get: o => read(o, false) }, configurable: true })
  }, { id: credential, expectedClient: clientId })
  await context.route('**/*', route => [appOrigin, apiOrigin].includes(new URL(route.request().url()).origin) ? route.continue() : route.abort())
  const page = await context.newPage(); page.on('pageerror', error => errors.push(String(error)))
  await page.goto(`${appOrigin}${path}#/account`)
  return page
}
const phase = (page, value) => page.waitForFunction(value => document.querySelector('[data-atomic-phase]')?.getAttribute('data-atomic-phase') === value && !document.querySelector('.atomic-account [aria-disabled=true]'), value)
const begin = async page => { await page.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(page, 'handle') }
const save = async page => { await page.locator('#atomic-handle').fill('ada'); await page.getByRole('button', { name: 'Save and verify keys', exact: true }).click() }
const reset = () => { saved = null; commits = 0; verifiedCredential = false; wrongRp = false; badClient = false; admissionClosed = false; holdRecord = false }
const shot = async (page, name) => { await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true }); images.push(name) }
try {
  for (const mode of ['unconfigured', 'wrong-origin']) {
    configMode = mode; const before = wire.length, page = await fresh()
    await page.locator('[data-test-configuration-error]').waitFor()
    check(`${mode}: configuration failure before App/API/provider`, wire.length === before && await page.getByRole('button').count() === 0 && (await page.evaluate(() => window.__t418Providers.google)) === 0)
    await shot(page, mode)
  }
  configMode = 'valid'; reset(); let page = await fresh()
  await phase(page, 'idle')
  check('built TEST entry is explicit; no eager provider calls', await page.getByText(/TEST ONLY/).count() === 1 && (await page.evaluate(() => window.__t418Providers.google)) === 0)
  await begin(page)
  check('server public ID and nonce reach real GIS adapter, not a coupled passkey create', await page.evaluate(() => window.__t418Providers.google === 1 && window.__t418Providers.create === 0))
  const recordReached = new Promise(done => { recordArrived = done }); holdRecord = true
  await save(page); await recordReached
  check('actual normal commit is not READY while permanent GET is held', commits === 1 && await page.getByText('Your saved keys have been checked. You are ready.', { exact: true }).count() === 0)
  holdRecord = false; recordRelease(); await phase(page, 'ready')
  check('same configured API handles setup and authenticated GET; native adapter decodes WebAuthn options', wire.some(x => x.path.startsWith('/v2/registration/')) && wire.some(x => x.path === '/v1/recovery/record') && await page.evaluate(() => window.__t418Providers.decoded && window.__t418Providers.create === 1 && window.__t418Providers.get === 1))
  const identity = { account: saved.record.account_id, bundle: saved.key_bundle.key_bundle_id, record: JSON.stringify(saved.record) }
  await shot(page, 'normal-ready')
  await page.getByRole('button', { name: 'sign out', exact: true }).click(); await phase(page, 'idle')
  check('logout hides ready; no private browser stores added', await page.getByText('Your saved keys have been checked. You are ready.', { exact: true }).count() === 0 && await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await indexedDB.databases()).length === 0 && (await caches.keys()).length === 0))
  page = await fresh(); await page.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(page, 'existing')
  await page.getByRole('button', { name: 'open with a passkey', exact: true }).click()
  await page.getByText('Your saved keys have been checked. You are ready.', { exact: true }).waitFor()
  check('fresh document existing-account restore unwraps original record without create/finalize', commits === 1 && identity.account === saved.record.account_id && identity.bundle === saved.key_bundle.key_bundle_id && identity.record === JSON.stringify(saved.record) && await page.evaluate(() => window.__t418Providers.create === 0 && window.__t418Providers.get === 1))
  await shot(page, 'fresh-existing-restored')
  for (const kind of ['noPrf', 'cancel']) {
    reset(); page = await fresh(); await begin(page); await page.evaluate(kind => { window.__t418Providers[kind] = true }, kind); await save(page); await phase(page, 'handle')
    const expected = kind === 'noPrf' ? /this passkey manager cannot open/ : /passkey step did not finish/i
    check(`${kind}: explicit provider failure, no permanent commit or READY`, commits === 0 && saved === null && await page.locator('.atomic-account [role=alert]').filter({ hasText: expected }).count() === 1)
    await shot(page, kind)
  }
  reset(); wrongRp = true; page = await fresh(); await begin(page); await save(page); await phase(page, 'handle')
  check('RP mismatch is clear before native credential creation', await page.locator('[data-test-configuration-error]').filter({ hasText: /RP ID differs/ }).count() === 1 && await page.evaluate(() => window.__t418Providers.create === 0) && commits === 0)
  await shot(page, 'wrong-rp')
  reset(); badClient = true; page = await fresh(); await page.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(page, 'idle')
  check('invalid Google options ID is clear before GIS request', await page.locator('[data-test-configuration-error]').count() === 1 && await page.evaluate(() => window.__t418Providers.google === 0) && commits === 0)
  reset(); admissionClosed = true; page = await fresh(); await page.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(page, 'idle')
  check('closed admission never falls back to old registration', await page.locator('#atomic-handle').count() === 0 && !wire.some(x => x.path === '/v1/auth/google/register'))
  reset(); page = await fresh('/normal/'); await page.getByRole('button', { name: 'continue with Google', exact: true }).click()
  await page.getByText(/new account setup is temporarily paused/i).waitFor()
  check('ordinary built entry remains gated despite adjacent TEST config', await page.locator('[data-atomic-phase]').count() === 0 && wire.at(-1).atomicVersion === null && !wire.some(x => x.path === '/v1/auth/google/register'))
  await shot(page, 'normal-public-gated')
  check('atomic wire version is sent, PRF stays local; no worker install or GIS network request', wire.filter(x => x.path === '/v1/auth/google/verify').slice(0, -1).every(x => x.atomicVersion === 1) && !staticRequests.includes('/sw.js'))
  assert.deepEqual(errors, []); complete = true
} finally {
  recordRelease?.()
  await Promise.all(activeContexts.map(context => context.close())); await browser.close()
  await Promise.all([new Promise(done => apiServer.close(done)), new Promise(done => appServer.close(done))])
  await writeFile(join(proof, 'report.json'), JSON.stringify({ complete, source: process.env.T418_TARGET ?? 'unspecified', actualBuiltEntry: true,
    realApp: true, realClientCrypto: true, realBrowserAdapters: true, syntheticGoogleCredentialAndHTTP: true, realProviders: false, realServerPG: false,
    executableSha256, checks, errors, images, wire, closedFixtureListeners: true }, null, 2))
}
console.log(`atomicTestEntry PASS ${checks.length} checks`)
