/** Actual built normal PWA + real worker + GIS/WebAuthn adapters + client crypto.
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
assert.deepEqual(JSON.parse(await readFile(join(web, 'dist/app-config.json'), 'utf8')), { version: 1, atomicRegistration: false })
const shell = JSON.parse(await readFile(join(web, 'dist/shell-manifest.json'), 'utf8'))
const checks = [], errors = [], images = [], wire = []
const check = (name, ok) => { assert.ok(ok, name); checks.push({ name, ok: true }) }
const account = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', reservationId = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const flow = '11111111-1111-4111-8111-111111111111', credential = Buffer.alloc(16, 9).toString('base64url')
const deadline = '2031-01-01T00:00:00Z', clientId = 'synthetic-t428.apps.googleusercontent.com'
let saved = null, commits = 0, recordRelease = null, recordArrived = null, holdRecord = false, wrongRp = false, badClient = false, admissionClosed = false
let configMode = 'valid', appOrigin = '', apiOrigin = '', activeContexts = [], workerRevision = 0
const reservation = () => ({ id: reservationId, account_id: account, state: saved ? 'ACTIVE' : 'RESERVED', epoch: 1,
  expires_at: deadline, credential_ids: verifiedCredential ? [credential] : [], outcome: null, restarted_into: null })
let verifiedCredential = false
const login = () => ({ user: { user_id: account, handle: saved.handle }, active_key_bundle: saved.key_bundle,
  session_id: flow, access_token: 'pm_at_synthetic', refresh_token: 'pm_rt_synthetic', access_expires_at: deadline, refresh_expires_at: deadline })
function options(create) {
  return { challenge: Buffer.alloc(32, 3).toString('base64url'), userVerification: 'required',
    ...(create ? { rp: { id: wrongRp ? 'wrong.invalid' : '127.0.0.1', name: 'Synthetic normal entry' },
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
  if (path === '/app-config.json') {
    if (configMode === 'missing') { res.writeHead(404).end(); return }
    const config = configMode === 'disabled' ? { version: 1, atomicRegistration: false }
      : { version: 1, atomicRegistration: true, environment: configMode === 'prod' ? 'PROD' : 'DEV',
        appOrigin: configMode === 'wrong-origin' ? 'https://wrong.invalid' : appOrigin,
        apiOrigin, rpId: configMode === 'wrong-rp' ? 'wrong.invalid' : '127.0.0.1' }
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': configMode === 'cached' ? 'max-age=600' : 'no-store' }).end(JSON.stringify(config)); return
  }
  const relative = path === '/' ? 'index.html' : path.replace(/^\//, '')
  const root = join(web, 'dist'), file = resolve(root, relative)
  if (file.startsWith(root + '/')) {
    try {
      let bytes = await readFile(file)
      // Explicit synthetic update trigger: identical production worker program
      // plus a comment, not a second claimed production release.
      if (relative === 'sw.js' && workerRevision) bytes = Buffer.concat([bytes, Buffer.from('\n// synthetic waiting-update trigger ' + workerRevision + '\n')])
      res.writeHead(200, { 'content-type': { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.wasm': 'application/wasm', '.css': 'text/css', '.otf': 'font/otf', '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json' }[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' }).end(bytes); return
    } catch { /* No SPA fallback for config or missing files. */ }
  }
  res.writeHead(404).end()
})
await new Promise(done => appServer.listen(0, '127.0.0.1', done)); appOrigin = `http://127.0.0.1:${appServer.address().port}`
const browser = await chromium.launch({ executablePath: executable, headless: true })
let complete = false
async function fresh(path = '/') {
  const context = await browser.newContext({ viewport: { width: 390, height: 900 }, serviceWorkers: 'allow' })
  activeContexts.push(context)
  await context.addInitScript(({ id, expectedClient }) => {
    const state = { google: 0, create: 0, get: 0, noPrf: false, cancel: false, decoded: true }
    window.__t428Providers = state
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
  await page.waitForFunction(() => document.documentElement.dataset.remanenceBoot === 'ready')
  return page
}
const phase = (page, value) => page.waitForFunction(value => document.querySelector('[data-atomic-phase]')?.getAttribute('data-atomic-phase') === value && !document.querySelector('.atomic-account [aria-disabled=true]'), value)
const begin = async page => { await page.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(page, 'handle') }
const save = async page => { await page.locator('#atomic-handle').fill('ada'); await page.getByRole('button', { name: 'Save and verify keys', exact: true }).click() }
const reset = () => { saved = null; commits = 0; verifiedCredential = false; wrongRp = false; badClient = false; admissionClosed = false; holdRecord = false }
const shot = async (page, name) => { await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true }); images.push(name) }
const poll = async (fn, label) => {
  const end = Date.now() + 12000
  while (!(await fn())) { assert.ok(Date.now() < end, label); await new Promise(done => setTimeout(done, 50)) }
}
const control = async page => {
  await poll(() => page.evaluate(async () => (await navigator.serviceWorker.getRegistration('/'))?.active?.state === 'activated'), 'worker activation')
  await page.reload(); await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller) && document.documentElement.dataset.remanenceBoot === 'ready')
}
const cachePaths = page => page.evaluate(async () => (await Promise.all((await caches.keys()).map(async name => (await (await caches.open(name)).keys()).map(request => new URL(request.url).pathname)))).flat())
try {
  for (const mode of ['missing', 'disabled']) {
    configMode = mode; reset(); const page = await fresh()
    await page.getByRole('button', { name: 'continue with Google', exact: true }).click()
    await page.getByText(/new account setup is temporarily paused/i).waitFor()
    check(mode + ': normal entry remains gated, no atomic or legacy registration', await page.locator('[data-atomic-phase]').count() === 0 && wire.at(-1).atomicVersion === null && !wire.some(x => x.path === '/v1/auth/google/register'))
    if (mode === 'disabled') await shot(page, 'default-gated')
    await page.context().close()
  }
  for (const mode of ['wrong-origin', 'wrong-rp', 'prod', 'cached']) {
    configMode = mode; const before = wire.length, page = await fresh()
    await page.locator('[data-deployment-unavailable]').waitFor()
    await page.getByRole('button', { name: 'continue with Google', exact: true }).click()
    check(mode + ': static App remains usable; no API or provider dispatch', wire.length === before && await page.locator('[data-atomic-phase]').count() === 0 && await page.evaluate(() => window.__t428Providers.google === 0))
    if (mode === 'wrong-origin') await shot(page, 'invalid-config')
    await page.context().close()
  }
  configMode = 'valid'; reset(); let page = await fresh()
  await control(page); await phase(page, 'idle')
  check('actual normal root is worker controlled and explicitly atomic; no TEST overlay or eager prompt', await page.evaluate(() => Boolean(navigator.serviceWorker.controller) && window.__t428Providers.google === 0) && await page.getByText(/TEST ONLY/).count() === 0)
  await begin(page); await page.locator('#atomic-handle').fill('ada')
  await page.evaluate(() => { window.__t428Document = 'live-ceremony' })
  workerRevision++
  await page.evaluate(async () => (await navigator.serviceWorker.getRegistration('/')).update())
  await poll(() => page.evaluate(async () => (await navigator.serviceWorker.getRegistration('/'))?.waiting?.state === 'installed'), 'waiting update')
  check('waiting worker does not activate/reload or replace live handle/ceremony', await page.locator('#atomic-handle').inputValue() === 'ada' && await page.evaluate(() => window.__t428Document === 'live-ceremony' && window.__t428Providers.google === 1))
  await shot(page, 'waiting-live-handle')
  const reached = new Promise(done => { recordArrived = done }); holdRecord = true
  await save(page); await reached
  check('normal PWA commit waits for authenticated permanent-record readback before READY', commits === 1 && await page.getByText('Your saved keys have been checked. You are ready.', { exact: true }).count() === 0)
  holdRecord = false; recordRelease(); await phase(page, 'ready')
  check('actual adapters preserve options, PRF-local rule and configured atomic/API target', wire.some(x => x.path.startsWith('/v2/registration/')) && wire.some(x => x.path === '/v1/recovery/record') && await page.evaluate(() => window.__t428Providers.decoded && window.__t428Providers.create === 1 && window.__t428Providers.get === 1))
  const identity = { account: saved.record.account_id, bundle: saved.key_bundle.key_bundle_id, record: JSON.stringify(saved.record) }
  await shot(page, 'normal-ready')
  const paths = await cachePaths(page)
  check('worker cache equals static graph only, no config/API/private data', paths.length === shell.entries.length && paths.every(path => shell.entries.some(entry => entry.path === path)) && !paths.some(path => /config|\/v[12]\//.test(path)))
  check('no account keys or records in persistent browser stores', await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await indexedDB.databases()).length === 0))
  await page.context().close()
  page = await fresh(); await control(page)
  await page.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(page, 'existing')
  await page.getByRole('button', { name: 'open with a passkey', exact: true }).click()
  await page.getByText('Your saved keys have been checked. You are ready.', { exact: true }).waitFor()
  check('fresh controlled document restores original identity, no replacement create/finalize', commits === 1 && identity.account === saved.record.account_id && identity.bundle === saved.key_bundle.key_bundle_id && identity.record === JSON.stringify(saved.record) && await page.evaluate(() => window.__t428Providers.create === 0 && window.__t428Providers.get === 1))
  await shot(page, 'fresh-original-restored')
  const configs = staticRequests.filter(path => path === '/app-config.json').length
  configMode = 'disabled'; await page.reload()
  await page.getByRole('button', { name: 'continue with Google', exact: true }).waitFor()
  check('fresh document fetches new disabled config despite controlling worker', await page.locator('[data-atomic-phase]').count() === 0 && staticRequests.filter(path => path === '/app-config.json').length === configs + 1)
  await page.context().setOffline(true); await page.reload()
  await page.locator('[data-deployment-unavailable]').waitFor()
  const offlineWire = wire.length
  await page.getByRole('button', { name: 'continue with Google', exact: true }).click()
  check('cold offline worker shell remains usable but cannot reuse config or dispatch account operations', wire.length === offlineWire && await page.locator('[data-atomic-phase]').count() === 0)
  await shot(page, 'offline-failclosed')
  await page.context().close(); configMode = 'valid'
  for (const kind of ['noPrf', 'cancel']) {
    reset(); page = await fresh(); await control(page); await begin(page); await page.evaluate(kind => { window.__t428Providers[kind] = true }, kind); await save(page); await phase(page, 'handle')
    const expected = kind === 'noPrf' ? /this passkey manager cannot open/ : /passkey prompt was dismissed/i
    check(kind + ': provider failure cannot commit or become READY', commits === 0 && saved === null && await page.locator('.atomic-account [role=alert]').filter({ hasText: expected }).count() === 1)
    await shot(page, kind); await page.context().close()
  }
  reset(); wrongRp = true; page = await fresh(); await control(page); await begin(page); await save(page); await phase(page, 'handle')
  check('backend RP mismatch fails before native credential create', await page.locator('[data-deployment-unavailable]').count() === 1 && await page.evaluate(() => window.__t428Providers.create === 0) && commits === 0)
  await page.context().close()
  reset(); badClient = true; page = await fresh(); await page.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(page, 'idle')
  check('backend Google ID mismatch fails before GIS prompt', await page.locator('[data-deployment-unavailable]').count() === 1 && await page.evaluate(() => window.__t428Providers.google === 0))
  await page.context().close()
  reset(); admissionClosed = true; page = await fresh(); await page.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(page, 'idle')
  check('server admission gate never falls back to legacy registration', await page.locator('#atomic-handle').count() === 0 && !wire.some(x => x.path === '/v1/auth/google/register'))
  await page.context().close()
  reset(); page = await fresh(); await control(page)
  for (const [lang, name] of [['en', 'English'], ['ru', 'Русский'], ['uk', 'Українська']]) {
    await page.locator('.bar .language-trigger').click(); await page.getByRole('option', { name, exact: true }).click()
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
    check(lang + ': large-text normal App remains localized with no document horizontal overflow', await page.evaluate(lang => document.documentElement.lang === lang && document.documentElement.scrollWidth <= innerWidth, lang))
    await shot(page, lang + '-large-normal')
  }
  check('no uncaught browser or synthetic HTTP fixture errors', errors.length === 0)
  complete = true

} finally {
  recordRelease?.()
  await Promise.all(activeContexts.map(context => context.close())); await browser.close()
  await Promise.all([new Promise(done => apiServer.close(done)), new Promise(done => appServer.close(done))])
  await writeFile(join(proof, 'report.json'), JSON.stringify({ complete, source: process.env.T428_TARGET ?? 'unspecified', actualBuiltEntry: true, actualNormalPwa: true, syntheticWorkerUpdateMarker: true,
    realApp: true, realClientCrypto: true, realBrowserAdapters: true, syntheticGoogleCredentialAndHTTP: true, realProviders: false, realServerPG: false,
    executableSha256, checks, errors, images, wire, closedFixtureListeners: true }, null, 2))
}
console.log(`normalDeployment PASS ${checks.length} checks`)
