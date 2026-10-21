/** T433 author regression derived from frozen T430 own-browser.mjs.
 * Attributed reuse, not independent acceptance. Actual archived normal HTML/JS/worker.
 * Synthetic HTTP DTOs and GIS/native ports follow pinned T428's fixture schema;
 * independent scenarios, assertions and capture. No App substitution/instrumentation.
 */
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, join, extname } from 'node:path'
const [root, oldRoot, previousRoot, testRoot, proof] = process.argv.slice(2).map(value => resolve(value))
await mkdir(proof, { recursive: true })
const { chromium } = createRequire('/srv/remanence/tools/browser-smoke/package.json')('playwright-core')
const executable = '/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const sha = value => createHash('sha256').update(value).digest('hex')
assert.equal(sha(await readFile(executable)), '4fa61f0ba0b4f89b7bec242c32ca16d3bccf69dbfef0651924a9ba8fede5f9d6')
const manifest = JSON.parse(await readFile(join(root, 'shell-manifest.json')))
for (const entry of manifest.entries) assert.equal(sha(await readFile(join(root, entry.path))), entry.sha256)
const cases = [], wire = [], images = [], uncaught = [], fixtureErrors = [], configRequests = []
let scenario = '', graph = root, mode = 'valid', appOrigin, apiOrigin, saved, verified, commits, holdPath, release, arrived, dropFinal, corrupt, admissionClosed
const contexts = new Set()
const account = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', reservationId = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee', flow = '11111111-1111-4111-8111-111111111111'
const credential = Buffer.alloc(16, 9).toString('base64url'), deadline = '2031-01-01T00:00:00Z', clientId = 't430-synthetic.apps.googleusercontent.com'
const reservation = () => ({ id: reservationId, account_id: account, state: saved ? 'ACTIVE' : 'RESERVED', epoch: 1, expires_at: deadline, credential_ids: verified ? [credential] : [], outcome: null, restarted_into: null })
const login = () => ({ user: { user_id: account, handle: saved.handle }, active_key_bundle: saved.key_bundle, session_id: flow, access_token: 'pm_at_synthetic', refresh_token: 'pm_rt_synthetic', access_expires_at: deadline, refresh_expires_at: deadline })
const options = create => ({ challenge: Buffer.alloc(32, 3).toString('base64url'), userVerification: 'required', ...(create ? {
  rp: { id: '127.0.0.1', name: 'T430 fixture' }, user: { id: Buffer.from(account.replaceAll('-', ''), 'hex').toString('base64url'), name: 'fixture', displayName: 'Fixture' },
  pubKeyCredParams: [{ type: 'public-key', alg: -7 }], authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
} : { rpId: '127.0.0.1', allowCredentials: [{ id: credential, type: 'public-key' }], extensions: { prf: { eval: { first: Buffer.alloc(32, 4).toString('base64url') } } } }) })
async function api(req, res) {
  res.setHeader('access-control-allow-origin', appOrigin); res.setHeader('access-control-allow-headers', 'content-type,authorization,x-remanence-profile-version'); res.setHeader('access-control-allow-methods', 'GET,POST,OPTIONS'); res.setHeader('cache-control', 'no-store')
  if (req.method === 'OPTIONS') return res.writeHead(204).end()
  try {
    const chunks = []; for await (const chunk of req) chunks.push(chunk)
    const raw = Buffer.concat(chunks).toString(), body = raw ? JSON.parse(raw) : {}, path = new URL(req.url, apiOrigin).pathname
    const receipt = { scenario, method: req.method, path, version: body.onboarding_version ?? null }; wire.push(receipt)
    assert.equal(req.headers['x-remanence-profile-version'], '2')
    let data = {}, status = 200
    if (path === '/v1/auth/google/options') data = { flow, nonce: 'A'.repeat(43), client_id: clientId }
    else if (path === '/v1/auth/google/verify') {
      assert.equal(body.id_token, 'T430-synthetic-google'); assert.equal(body.nonce, 'A'.repeat(43))
      if (saved) data = { status: 'SIGNED_IN', login: login() }
      else if (body.onboarding_version === undefined) data = { status: 'NEEDS_HANDLE', flow, registration_token: 'A'.repeat(43) }
      else if (admissionClosed) { status = 403; data = { reason: 'REGISTRATION_ADMISSION_CLOSED' } }
      else { assert.equal(body.onboarding_version, 1); data = { status: 'SETUP_REQUIRED', reservation: reservation(), setup_token: `rgs_${'A'.repeat(43)}`, setup_session_id: flow, setup_expires_at: deadline } }
    } else if (path.startsWith('/v2/registration/')) {
      assert.equal(req.headers.authorization, `Bearer rgs_${'A'.repeat(43)}`)
      const action = path.slice(`/v2/registration/reservations/${reservationId}`.length).replace(/^\//, '')
      if (!action) data = { reservation: reservation() }
      else if (action === 'credential/options' || action === 'setup/options') data = { flow, options: options(action === 'credential/options'), expires_at: deadline }
      else if (action === 'credential/verify' || action === 'setup/verify') {
        assert.deepEqual(body.response.clientExtensionResults, {}); assert.equal(body.response.id, credential); assert.equal(body.response.rawId, credential)
        if (action === 'credential/verify') { verified = true; data = { credential_id: credential, registered: true, recoverable: false } }
        else data = { credential_id: credential, authentication_verified: true, final_authorization: `rgf_${'A'.repeat(43)}`, expires_at: deadline }
      } else if (action === 'finalize') {
        receipt.intentSha256 = sha(JSON.stringify(body))
        if (!saved) { saved = structuredClone(body); commits++; status = 201 } else assert.deepEqual(body, saved, 'UNKNOWN replay must keep exact intent')
        data = { status: 'ACTIVE', outcome: { operation_id: body.operation_id, account_id: account, bundle_id: body.key_bundle.key_bundle_id, credential_id: credential, generation: 1, handle: body.handle }, ...(status === 201 ? { login: login() } : { reauthenticate: true }) }
        if (dropFinal) { receipt.droppedCommittedReply = true; req.socket.destroy(); return }
      } else if (action === 'leave') { data = { left: true }; receipt.exactOldToken = true }
      else throw new Error(`Unexpected action ${action}`)
    } else if (path.startsWith('/v1/recovery/')) {
      assert.equal(req.headers.authorization, 'Bearer pm_at_synthetic'); assert.ok(saved)
      if (path.endsWith('/status')) data = { state: 'STORED', credential_id: credential }
      else if (path.endsWith('/setup/options')) data = { flow, options: options(false) }
      else if (path.endsWith('/setup/verify')) { assert.deepEqual(body.response.clientExtensionResults, {}); data = { credential_id: credential, authentication_verified: true } }
      else if (path.endsWith('/record')) { assert.equal(req.method, 'GET'); data = { record: saved.record, encryption_public_keyset: saved.key_bundle.encryption_public_keyset, signing_public_keyset: saved.key_bundle.signing_public_keyset }; if (corrupt) data = { ...data, signing_public_keyset: saved.key_bundle.encryption_public_keyset }; receipt.recordSha256 = sha(JSON.stringify(saved.record)) }
      else throw new Error(`Unexpected recovery ${path}`)
    } else throw new Error(`Unexpected API ${path}`)
    if (holdPath && path.endsWith(holdPath)) { receipt.held = true; arrived?.(); await new Promise(done => { release = done }); receipt.released = true }
    receipt.status = status; res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(data))
  } catch (error) { fixtureErrors.push({ scenario, message: String(error) }); res.writeHead(500).end() }
}
const apiServer = createServer(api); await new Promise(done => apiServer.listen(0, '127.0.0.1', done)); apiOrigin = `http://127.0.0.1:${apiServer.address().port}`
const appServer = createServer(async (req, res) => {
  const path = new URL(req.url, 'http://fixture.invalid').pathname
  if (/^\/v[12]\//.test(path)) return api(req, res)
  if (path === '/app-config.json') {
    configRequests.push({ scenario, mode }); if (mode === 'missing') return res.writeHead(404).end()
    if (mode === 'redirect') return res.writeHead(302, { location: '/redirected-config.json' }).end()
    const data = mode === 'disabled' ? { version: 1, atomicRegistration: false } : { version: 1, atomicRegistration: true, environment: mode === 'prod' ? 'PROD' : 'TEST', appOrigin: mode === 'origin' ? 'https://wrong.invalid' : appOrigin, apiOrigin: mode === 'api-path' ? apiOrigin + '/x' : mode === 'insecure-api' ? 'http://api.invalid' : apiOrigin, rpId: mode === 'rp' ? 'wrong.invalid' : '127.0.0.1' }
    return res.writeHead(200, { 'content-type': mode === 'html' ? 'text/html' : 'application/json', 'cache-control': mode === 'cached' ? 'max-age=60' : 'no-store', age: mode === 'age' ? '9' : '0' }).end(mode === 'malformed' ? '{' : mode === 'oversize' ? JSON.stringify('x'.repeat(4100)) : JSON.stringify(data))
  }
  const test = path.startsWith('/test-entry/'), active = test ? testRoot : graph
  const relative = test ? path.slice('/test-entry/'.length) || 'index.html' : ['/', '/fresh-document.html'].includes(path) ? 'index.html' : path.slice(1)
  const file = resolve(active, relative)
  if (file.startsWith(active + '/')) try { const bytes = await readFile(file); return res.writeHead(200, { 'content-type': ({ '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.css': 'text/css', '.otf': 'font/otf', '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json' })[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' }).end(bytes) } catch {}
  res.writeHead(404).end()
})
await new Promise(done => appServer.listen(0, '127.0.0.1', done)); appOrigin = `http://127.0.0.1:${appServer.address().port}`
const browser = await chromium.launch({ executablePath: executable, headless: true })
async function fresh(path = '/#/account') {
  const context = await browser.newContext({ viewport: { width: 390, height: 900 }, serviceWorkers: 'allow' }); contexts.add(context)
  await context.addInitScript(({ credential, clientId }) => {
    const p = window.__t430 = { google: 0, create: 0, get: 0, noPrf: false, cancel: false, hold: false, decoded: true, document: crypto.randomUUID() }
    let initialized
    window.google = { accounts: { id: { initialize(o) { if (o.client_id !== clientId || o.nonce !== 'A'.repeat(43)) throw new Error('Wrong GIS target'); initialized = o }, disableAutoSelect() {}, cancel() {}, prompt() { p.google++; initialized.callback({ credential: 'T430-synthetic-google' }) } } } }
    const bytes = (n, v) => new Uint8Array(n).fill(v).buffer
    async function native(o, create) {
      create ? p.create++ : p.get++
      p.decoded &&= o.publicKey.challenge instanceof Uint8Array && (create ? o.publicKey.user.id instanceof Uint8Array : o.publicKey.extensions.prf.eval.first instanceof Uint8Array)
      if (p.hold) await new Promise(done => { p.release = done }) // deliberately late/ignores AbortSignal
      if (p.cancel) throw new DOMException('fixture dismissal', 'NotAllowedError')
      return { id: credential, rawId: bytes(16, 9), type: 'public-key', response: create ? { clientDataJSON: new TextEncoder().encode('{}').buffer, attestationObject: bytes(8, 2), getTransports: () => ['internal'] } : { clientDataJSON: new TextEncoder().encode('{}').buffer, authenticatorData: bytes(37, 3), signature: bytes(64, 4), userHandle: null }, getClientExtensionResults: () => create || p.noPrf ? {} : { prf: { results: { first: bytes(32, 7) } } } }
    }
    Object.defineProperty(navigator, 'credentials', { configurable: true, value: { create: o => native(o, true), get: o => native(o, false) } })
  }, { credential, clientId })
  await context.route(url => ![appOrigin, apiOrigin].includes(url.origin), route => route.abort())
  const page = await context.newPage(); page.setDefaultTimeout(8000); page.on('pageerror', error => uncaught.push({ scenario, message: String(error), stack: error.stack }))
  await page.goto(appOrigin + path); await page.waitForFunction(() => document.documentElement.dataset.remanenceBoot === 'ready'); return page
}
const phase = (p, value) => p.waitForFunction(value => document.querySelector('[data-atomic-phase]')?.dataset.atomicPhase === value && !document.querySelector('.atomic-account [aria-disabled=true]'), value)
const begin = async p => { await p.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(p, 'handle') }
const save = async p => { await p.locator('#atomic-handle').fill('t430_review'); await p.getByRole('button', { name: 'Save and verify keys', exact: true }).click() }
const ready = p => p.getByText('Your saved keys have been checked. You are ready.', { exact: true })
const shot = async (p, name) => { await p.screenshot({ path: join(proof, name + '.png'), fullPage: true }); images.push(name) }
const poll = async (fn, label) => { const end = Date.now() + 10000; while (!(await fn())) { assert.ok(Date.now() < end, label); await new Promise(done => setTimeout(done, 50)) } }
const control = async p => { await poll(() => p.evaluate(async () => (await navigator.serviceWorker.getRegistration('/'))?.active?.state === 'activated'), 'worker activation'); await p.reload(); await p.waitForFunction(() => !!navigator.serviceWorker.controller && document.documentElement.dataset.remanenceBoot === 'ready') }
const stores = p => p.evaluate(async () => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage), db: (await indexedDB.databases()).map(x => x.name), caches: await Promise.all((await caches.keys()).map(async name => ({ name, entries: await Promise.all((await (await caches.open(name)).keys()).map(async request => ({ path: new URL(request.url).pathname, sha256: Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await (await (await caches.open(name)).match(request)).arrayBuffer())), b => b.toString(16).padStart(2, '0')).join('') }))) }))) }))
async function cleanStores(p) { const s = await stores(p); assert.deepEqual(s.local, []); assert.deepEqual(s.session, []); assert.deepEqual(s.db, []); for (const cache of s.caches) for (const e of cache.entries) assert.ok(manifest.entries.some(x => x.path === e.path && x.sha256 === e.sha256)); return s }
async function run(name, fn) {
  if (process.env.T433_CASE && name !== process.env.T433_CASE) return
  scenario = name; saved = null; verified = false; commits = 0; mode = 'valid'; graph = root; holdPath = null; release = null; arrived = null; dropFinal = false; corrupt = false; admissionClosed = false
  const at = wire.length, errorsAt = uncaught.length
  try { await fn(); assert.equal(fixtureErrors.length, 0, 'fixture protocol assertion'); assert.equal(uncaught.length, errorsAt, 'no uncaught application exception'); cases.push({ name, result: 'PASS', requests: wire.length - at }) }
  catch (error) { cases.push({ name, result: 'RED', error: String(error), stack: error.stack, requests: wire.length - at }); for (const c of contexts) for (const p of c.pages()) await shot(p, name + '-failure').catch(() => {}) }
  finally { release?.(); for (const c of contexts) await c.close(); contexts.clear(); await writeFile(join(proof, 'checkpoint.json'), JSON.stringify({ cases, uncaught, fixtureErrors }, null, 2)); console.log(JSON.stringify(cases.at(-1))) }
}
try {
  for (const config of ['missing', 'disabled']) await run('default-' + config, async () => { mode = config; const p = await fresh(); await p.getByRole('button', { name: 'continue with Google', exact: true }).click(); await p.getByText(/new account setup is temporarily paused/i).waitFor(); assert.equal(await p.locator('[data-atomic-phase]').count(), 0); assert.ok(!wire.some(x => x.path.includes('/register') || x.path.startsWith('/v2/'))); await cleanStores(p) })
  for (const config of ['origin', 'rp', 'api-path', 'insecure-api', 'prod', 'cached', 'age', 'html', 'malformed', 'oversize', 'redirect']) await run('reject-' + config, async () => { mode = config; const at = wire.length, p = await fresh(); await p.locator('[data-deployment-unavailable]').waitFor(); await p.getByRole('button', { name: 'continue with Google', exact: true }).click(); assert.equal(wire.length, at); assert.equal(await p.evaluate(() => window.__t430.google), 0); assert.equal(await p.getByText('nothing needs attention', { exact: true }).count(), 0); if (config === 'origin') await shot(p, 'invalid-config') })
  await run('readback-ready-direct-restore', async () => {
    const p = await fresh(); await control(p); await phase(p, 'idle'); assert.equal(await p.evaluate(() => window.__t430.google), 0); await begin(p)
    holdPath = '/record'; const arrivedPromise = new Promise(done => { arrived = done }); await save(p); await arrivedPromise
    assert.equal(commits, 1); assert.equal(await ready(p).count(), 0); await shot(p, 'readback-held'); holdPath = null; release(); await phase(p, 'ready'); await shot(p, 'ready')
    assert.ok(await p.evaluate(() => window.__t430.decoded)); const original = sha(JSON.stringify(saved)); const s = await cleanStores(p); assert.equal(s.caches.length, 1); assert.equal(s.caches[0].entries.length, manifest.entries.length); await writeFile(join(proof, 'cache-inventory.json'), JSON.stringify(s, null, 2))
    const google = await p.evaluate(() => window.__t430.google); await p.getByRole('button', { name: 'lock keys', exact: true }).click(); await p.getByRole('button', { name: 'open with a passkey', exact: true }).click(); await ready(p).waitFor(); assert.equal(await p.evaluate(() => window.__t430.google), google); assert.equal(sha(JSON.stringify(saved)), original); assert.equal(commits, 1)
  })
  await run('online-signout-control', async () => { const p = await fresh(); await control(p); await begin(p); await save(p); await phase(p, 'ready'); await p.getByRole('button', { name: 'sign out', exact: true }).click(); await phase(p, 'idle'); assert.equal(await ready(p).count(), 0); assert.equal(await p.getByRole('button', { name: 'sign out', exact: true }).count(), 0); await cleanStores(p) })
  await run('offline-signout', async () => { const p = await fresh(); await control(p); await begin(p); await save(p); await phase(p, 'ready'); await p.context().setOffline(true); await p.waitForFunction(() => !navigator.onLine); await p.getByRole('button', { name: 'sign out', exact: true }).click(); await shot(p, 'offline-signout-after-click'); assert.equal(await ready(p).count(), 0, 'sign out must clear local unlocked state while offline'); assert.equal(await p.getByRole('button', { name: 'sign out', exact: true }).count(), 0); await cleanStores(p) })
  await run('corrupt-readback', async () => { corrupt = true; const p = await fresh(); await control(p); await begin(p); await save(p); await phase(p, 'restore-required'); assert.equal(commits, 1); assert.equal(await ready(p).count(), 0); assert.equal(await p.locator('#atomic-handle').count(), 0); await shot(p, 'corrupt-restore-required'); await cleanStores(p) })
  for (const kind of ['noPrf', 'cancel']) await run(kind, async () => { const p = await fresh(); await control(p); await begin(p); await p.evaluate(k => { window.__t430[k] = true }, kind); await save(p); await phase(p, 'handle'); assert.equal(commits, 0); assert.equal(saved, null); await p.locator('.atomic-account [role=alert]').filter({ hasText: kind === 'noPrf' ? /this passkey manager cannot open/ : /passkey prompt was dismissed/i }).waitFor(); await shot(p, kind); await cleanStores(p) })
  await run('late-native-back', async () => { const p = await fresh('/#/home'); await control(p); await p.evaluate(() => { location.hash = '#/account' }); await phase(p, 'idle'); await begin(p); await p.evaluate(() => { window.__t430.hold = true }); await save(p); await p.waitForFunction(() => window.__t430.create === 1); await p.goBack(); await p.waitForFunction(() => !document.querySelector('[data-atomic-phase]')); await p.evaluate(() => window.__t430.release()); await p.evaluate(() => { location.hash = '#/account' }); await phase(p, 'idle'); assert.equal(commits, 0); assert.equal(await p.locator('#atomic-handle').count(), 0); assert.ok(wire.some(x => x.scenario === scenario && x.path.endsWith('/leave') && x.exactOldToken)); await cleanStores(p) })
  await run('lost-reply-reload', async () => { dropFinal = true; const p = await fresh(); await control(p); await begin(p); await save(p); await phase(p, 'unknown'); assert.equal(commits, 1); const original = sha(JSON.stringify(saved)), beforeRetry = wire.filter(x => x.scenario === scenario && x.intentSha256).length; await p.getByRole('button', { name: 'Retry the same save', exact: true }).click(); await phase(p, 'unknown'); const attempts = wire.filter(x => x.scenario === scenario && x.intentSha256); assert.ok(attempts.length > beforeRetry, 'explicit retry actually dispatched'); assert.ok(attempts.every(x => x.intentSha256 === original), 'every network delivery retains frozen intent, including transport retransmission after socket loss'); await shot(p, 'unknown'); assert.equal(await ready(p).count(), 0); await p.reload(); await phase(p, 'idle'); assert.equal(await ready(p).count(), 0); await p.getByRole('button', { name: 'continue with Google', exact: true }).click(); await phase(p, 'existing'); await p.getByRole('button', { name: 'open with a passkey', exact: true }).click(); await ready(p).waitFor(); assert.equal(sha(JSON.stringify(saved)), original); assert.equal(commits, 1); assert.equal(await p.evaluate(() => window.__t430.create), 0); await shot(p, 'reload-original-restored'); await cleanStores(p) })
  await run('fresh-config-cold-offline', async () => { const p = await fresh(); await control(p); await phase(p, 'idle'); const count = configRequests.length; mode = 'disabled'; await p.reload(); await p.getByRole('button', { name: 'continue with Google', exact: true }).waitFor(); assert.equal(await p.locator('[data-atomic-phase]').count(), 0); assert.equal(configRequests.length, count + 1); await p.context().setOffline(true); await p.reload(); await p.locator('[data-deployment-unavailable]').waitFor(); const at = wire.length; await p.getByRole('button', { name: 'continue with Google', exact: true }).click(); assert.equal(wire.length, at); assert.equal(await ready(p).count(), 0); await shot(p, 'cold-offline'); await cleanStores(p) })
  await run('old-worker-refusal', async () => { graph = oldRoot; const p = await fresh(); await control(p); const cachesBefore = await p.evaluate(() => caches.keys()), count = configRequests.length; graph = root; await p.goto(appOrigin + '/fresh-document.html#/account'); await p.locator('[data-deployment-unavailable]').waitFor(); assert.equal(configRequests.length, count); assert.equal(await p.locator('[data-atomic-phase]').count(), 0); assert.ok(await p.evaluate(async old => { const now = await caches.keys(); return old.every(x => now.includes(x)) }, cachesBefore)); await shot(p, 'old-worker-refused') })
  await run('waiting-actual-graphs', async () => { graph = previousRoot; const p = await fresh(); await control(p); await begin(p); await p.locator('#atomic-handle').fill('unsubmitted_handle'); const document = await p.evaluate(() => window.__t430.document); graph = root; await p.evaluate(async () => (await navigator.serviceWorker.getRegistration('/')).update()); await poll(() => p.evaluate(async () => (await navigator.serviceWorker.getRegistration('/'))?.waiting?.state === 'installed'), 'waiting worker'); assert.equal(await p.locator('#atomic-handle').inputValue(), 'unsubmitted_handle'); assert.equal(await p.evaluate(() => window.__t430.document), document); assert.equal(await p.evaluate(() => window.__t430.google), 1); await shot(p, 'waiting-live-handle'); const c = p.context(); await p.close(); const next = await c.newPage(); await next.goto(appOrigin + '/#/account'); await poll(() => next.evaluate(async version => { const r = await navigator.serviceWorker.getRegistration('/'); return r?.active?.state === 'activated' && !r.waiting && (await caches.keys()).some(x => x.endsWith(version)) }, manifest.version), 'natural activation'); await next.reload(); await phase(next, 'idle') })
  await run('dedicated-test-refusal', async () => { const p = await fresh(); await control(p); const at = wire.length, keys = await p.evaluate(() => caches.keys()); await p.goto(appOrigin + '/test-entry/#/account'); await p.getByText(/controlled by an existing service worker/).waitFor(); assert.equal(wire.length, at); assert.deepEqual(await p.evaluate(() => caches.keys()), keys); await shot(p, 'dedicated-test-refused') })
  await run('locale-large-config-notice', async () => { mode = 'origin'; const p = await fresh(); await p.locator('[data-deployment-unavailable]').waitFor(); for (const [code, label] of [['en','English'],['ru','Русский'],['uk','Українська']]) { await p.locator('.bar .language-trigger').click(); await p.getByRole('option', { name: label, exact: true }).click(); await p.evaluate(() => { document.documentElement.style.fontSize = '200%' }); assert.ok(await p.evaluate(code => document.documentElement.lang === code && document.documentElement.scrollWidth <= innerWidth, code)); await shot(p, code + '-notice-large') } })
} finally {
  release?.(); for (const c of contexts) await c.close(); await browser.close(); apiServer.closeAllConnections(); appServer.closeAllConnections(); await Promise.all([new Promise(done => apiServer.close(done)), new Promise(done => appServer.close(done))])
  await writeFile(join(proof, 'report.json'), JSON.stringify({ pin: process.env.T433_TARGET, archiveSha256: process.env.T433_ARCHIVE_SHA ?? null, authorAttributedT430Runner: true, realBuiltApp: true, realWorker: true, realClientCrypto: true, syntheticProviderAndHttp: true, realProvider: false, realApiPG: false, closedOwnedListeners: true, cases, wire, configRequests, uncaught, fixtureErrors, images }, null, 2))
}
process.exitCode = cases.some(x => x.result !== 'PASS') ? 1 : 0
