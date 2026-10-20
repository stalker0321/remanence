import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createInterface } from 'node:readline'
import { fixtureHost } from './fixtureHost.mjs'

const [web, proof] = process.argv.slice(2)
const lines = createInterface({ input: process.stdin })[Symbol.asyncIterator]()
const next = async () => { const line = await lines.next(); assert.ok(!line.done); return JSON.parse(line.value) }
const first = await next(); assert.equal(first.phase, 'start')
assert.ok(Number.isInteger(first.apiPort) && first.apiPort > 0 && first.apiPort < 65536)
const checks = [], errors = [], requests = [], intents = []
let dropCommit = false, dropped = 0, saved = null
const check = (name, ok) => { assert.ok(ok, name); checks.push({ name, ok: true }) }
const host = await fixtureHost(web, proof, 'realAtomic.page.tsx', { async handle(req, res) {
  const path = new URL(req.url, 'http://fixture.invalid').pathname
  if (!path.startsWith('/v1/') && !path.startsWith('/v2/') && !path.startsWith('/__fixture/')) return false
  assert.match(path, /^(?:\/v1\/(?:auth\/google\/(?:options|verify)|recovery\/(?:status|record|setup\/(?:options|verify)))|\/v2\/registration\/reservations\/[0-9a-f-]{36}(?:\/(?:credential\/(?:options|verify)|setup\/(?:options|verify)|finalize|leave|cancel|restart))?|\/__fixture\/(?:google|passkey))$/)
  assert.ok(['POST', 'GET'].includes(req.method))
  const chunks = []; let size = 0
  for await (const chunk of req) { size += chunk.length; assert.ok(size <= 262144); chunks.push(chunk) }
  const body = Buffer.concat(chunks)
  const headers = { accept: 'application/json', 'x-remanence-profile-version': '2' }
  if (req.headers.authorization) headers.authorization = req.headers.authorization
  if (body.length) headers['content-type'] = 'application/json'
  if (path.endsWith('/finalize')) {
    const input = JSON.parse(body)
    // Never retain final grant/token, JWT, provider private key or raw identity.
    intents.push({ record: input.record, key_bundle: input.key_bundle, operation_id: input.operation_id, handle: input.handle })
  }
  const upstream = await fetch(`http://127.0.0.1:${first.apiPort}${path}`, { method: req.method, headers, body: body.length ? body : undefined, redirect: 'error' })
  const bytes = new Uint8Array(await upstream.arrayBuffer()); assert.ok(bytes.length <= 262144)
  if (!path.startsWith('/__fixture/')) requests.push({ method: req.method, path, status: upstream.status, noStore: upstream.headers.get('cache-control') === 'no-store' })
  if (path.endsWith('/finalize') && upstream.status === 201 && dropCommit) {
    dropCommit = false; dropped++; res.destroy(); return true // Real committed reply consumed, never delivered.
  }
  res.writeHead(upstream.status, { 'content-type': upstream.headers.get('content-type') ?? 'application/json', 'cache-control': 'no-store' }).end(bytes)
  return true
} })
const page = await host.browser.newPage({ viewport: { width: 360, height: 900 } })
page.on('pageerror', (error) => errors.push(String(error)))
await page.route('**/*', (route) => new URL(route.request().url()).origin === new URL(host.url).origin ? route.continue() : route.abort())
const state = () => page.evaluate(() => window.realAtomic.state())
const phase = (value) => page.waitForFunction((value) => window.realAtomic.state().setup === value && !document.querySelector('[aria-disabled=true]'), value)
async function fresh() { await page.goto(`${host.url}#/account`); await page.reload(); await page.waitForFunction(() => window.realAtomic) }
async function sign() { await page.getByRole('button', { name: 'continue with Google', exact: true }).click() }
async function save(handle) { await page.locator('#atomic-handle').fill(handle); await page.getByRole('button', { name: 'Save and verify keys', exact: true }).click() }
async function restore() {
  await page.reload(); await page.waitForFunction(() => window.realAtomic); await sign(); await phase('existing')
  await page.getByRole('button', { name: 'open with a passkey', exact: true }).click()
  await page.waitForFunction(() => window.realAtomic.state().keys)
  check('fresh document decrypts precommit ciphertext with original stored identity', await page.evaluate((encrypted) => window.realAtomic.prove(encrypted), saved.encrypted))
  check('restored original account/bundle', (await state()).accountId === saved.candidate.accountId && (await state()).bundleId === saved.candidate.bundleId)
}
function ack(phase, data = {}) { process.stdout.write(JSON.stringify({ phase, ok: true, ...data }) + '\n') }
let complete = false
try {
  ack('start', { browserPort: Number(new URL(host.url).port) })
  for (;;) {
    const input = await next()
    if (input.phase === 'reject') {
      await fresh(); await sign(); await phase('handle'); await save('lost_reply')
      await page.waitForFunction(() => window.realAtomic.state().setup === 'handle' && !document.querySelector('[aria-disabled=true]') && document.querySelector('[data-atomic-phase] [role=alert]'))
      check('real rejected ES256 assertion never finalizes or creates an account locally', intents.length === 0 && (await state()).phase === 'signed-out' && !(await state()).keys)
      ack(input.phase)
    } else if (input.phase === 'lost') {
      dropCommit = true; await save('lost_reply'); await phase('unknown')
      saved = await page.evaluate(() => window.realAtomic.challenge())
      check('actual committed 201 dropped, controller UNKNOWN and not READY', dropped === 1 && (await state()).phase === 'signed-out' && !(await state()).keys)
      await page.screenshot({ path: join(proof, 'lost-reply-unknown.png'), fullPage: true })
      ack(input.phase, { intent: intents.at(-1), candidate: saved.candidate })
    } else if (input.phase === 'restore' || input.phase === 'normal-reload') {
      await restore(); check('PRF owned buffers wiped', (await state()).wiped)
      await page.screenshot({ path: join(proof, `${input.phase}-ready.png`), fullPage: true })
      ack(input.phase, { state: await state() })
    } else if (input.phase === 'normal') {
      await fresh(); await sign(); await phase('handle'); await save('normal_reply'); await phase('ready')
      saved = await page.evaluate(() => window.realAtomic.challenge())
      check('normal 201 reached READY only through real permanent readback', (await state()).keys && requests.slice(-2).map((r) => r.path).join(',') === '/v1/recovery/status,/v1/recovery/record')
      check('normal same-document private identity decrypts prior ciphertext', await page.evaluate((encrypted) => window.realAtomic.prove(encrypted), saved.encrypted))
      ack(input.phase, { intent: intents.at(-1), state: await state() })
    } else if (input.phase === 'finish') {
      check('no durable private/setup persistence', await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await indexedDB.databases()).length === 0 && (await caches.keys()).length === 0))
      check('all real API responses no-store', requests.every((r) => r.noStore))
      assert.deepEqual(errors, []); complete = true; ack('finish'); break
    } else throw new Error('unknown phase')
  }
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ outcome: complete ? 'PASS' : 'FAILED', realApp: true, realApi: true, syntheticProviders: true,
    executableSha256: host.executableSha256, droppedCommittedReplies: dropped, checks, requests, errors }, null, 2))
  await host.close()
}
